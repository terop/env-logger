(ns env-logger.weather
  "Namespace for weather fetching code"
  (:require [clojure.core.cache.wrapped :as c]
            [clojure.tools.logging :as log]
            [clojure.string :as s]
            [clojure.xml :refer [parse]]
            [clojure.zip :refer [xml-zip]]
            [clojure.data.zip.xml :as zx]
            [cheshire.core :refer [parse-string]]
            [clj-http.client :as client]
            [next.jdbc :as jdbc]
            [java-time :as t]
            [env-logger
             [config :refer [get-conf-value]]
             [db :refer [rs-opts]]])
  (:import java.util.Date
           java.sql.Timestamp))

(def weather-cache (c/basic-cache-factory {:data nil :recorded nil}))

;; FMI

(defn extract-weather-data
  "Parses and returns various weather data values from the given XML
  data. It is assumed that there only one set of values in the XML data."
  [parsed-xml]
  (when (>= (count (:content parsed-xml)) 3)
    (let [root (xml-zip parsed-xml)
          values (for [m (zx/xml-> root
                                   :wfs:member
                                   :BsWfs:BsWfsElement
                                   :BsWfs:ParameterValue)]
                   (zx/text m))
          date-text (zx/text (zx/xml1-> root
                                        :wfs:member
                                        :BsWfs:BsWfsElement
                                        :BsWfs:Time))]
      {:date (new Timestamp (.toEpochMilli (t/instant date-text)))
       :temperature (Float/parseFloat (nth values 0))
       :cloudiness (Math/round (Float/parseFloat (nth values 1)))
       :wind-speed (Float/parseFloat (nth values 2))})))

(defn extract-forecast-data
  "Parses forecast data values from the given XML data."
  [parsed-xml]
  (let [root (xml-zip parsed-xml)
        raw-data (s/trim (zx/text (zx/xml1-> root
                                             :wfs:member
                                             :omso:GridSeriesObservation
                                             :om:result
                                             :gmlcov:MultiPointCoverage
                                             :gml:rangeSet
                                             :gml:DataBlock
                                             :gml:doubleOrNilReasonTupleList)))
        split-data (s/split raw-data #" ")]
    (when (>= (count split-data) 3)
      {:temperature (Float/parseFloat (format "%.1f" (Float/parseFloat
                                                      (nth split-data 0))))
       :wind-speed (Float/parseFloat (format "%.1f" (Float/parseFloat
                                                     (nth split-data 1))))
       :cloudiness (Math/round (Float/parseFloat (nth split-data 2)))})))

(defn calculate-start-time
  "Calculates the start time for the data request and returns it as a
  DateTime object. The time is the closest even ten minutes in the past,
  example: for 08:27 it would be 08:20."
  []
  (let [curr-minute (.getMinute (t/zoned-date-time))
        start-time (t/minus (t/zoned-date-time)
                            (t/minutes (- curr-minute
                                          (- curr-minute
                                             (mod curr-minute 10))))
                            (t/seconds (.getSecond (t/zoned-date-time))))]
    start-time))

(defn -get-fmi-weather-data-wfs
  "Fetches the latest FMI data from the FMI WFS for the given weather
  observation station. If fetching or parsing failed, nil is returned."
  [station-id]
  (let [url (format (str "https://opendata.fmi.fi/wfs?service=WFS&version=2.0.0"
                         "&request=getFeature&storedquery_id="
                         "fmi::observations::weather::simple&fmisid=%d&"
                         "parameters=t2m,n_man,ws_10min&starttime=%s")
                    station-id
                    (str (first (s/split
                                 (str (t/instant (t/with-zone
                                                   (calculate-start-time)
                                                   "Europe/Helsinki")))
                                 #"\.\d+")) "Z"))]
    (try
      (extract-weather-data (parse url))
      (catch org.xml.sax.SAXParseException _
        (log/error "FMI weather data XML parsing failed")
        nil)
      (catch Exception e
        (log/error (str "FMI weather data fetch failed, status: "
                        (str e)))
        nil))))

(defn -get-fmi-weather-data-json
  "Fetches the latest FMI weather data from the observation data in JSON for the
  given weather observation station. If fetching or parsing failed, nil is
  returned."
  [station-id]
  (let [url (str "https://ilmatieteenlaitos.fi/observation-data?station="
                 station-id)
        resp (try
               (client/get url)
               (catch Exception e
                 (log/error (str "FMI JSON weather data fetch failed, "
                                 "status:" (:status (ex-data e))))))
        json-resp (try
                    (parse-string (:body resp))
                    (catch com.fasterxml.jackson.core.JsonParseException e
                      (log/error (str "FMI JSON weather data parsing failed: "
                                      (str e)))))]
    (when (and json-resp
               (not (or (zero? (count (get json-resp "t2m")))
                        (zero? (count (get json-resp "TotalCloudCover")))
                        (zero? (count (get json-resp "WindSpeedMS"))))))
      {:date (t/sql-timestamp (t/zoned-date-time
                               (str (.toInstant
                                     (new Date
                                          (get json-resp
                                               "latestObservationTime"))))))
       :temperature ((last (get json-resp "t2m")) 1)
       :cloudiness (int ((last (get json-resp "TotalCloudCover")) 1))
       :wind-speed ((last (get json-resp "WindSpeedMS")) 1)})))

(defn get-fmi-weather-data
  "Fetches the latest FMI weather data either using
  1) HTTTP request in JSON
  2) WFS
  for the given weather observation station. If fetching or parsing failed,
  nil is returned."
  [station-id]
  (or (-get-fmi-weather-data-json station-id)
      (-get-fmi-weather-data-wfs station-id)))

(defn weather-query-ok?
  "Tells whether it is OK to query the FMI API for weather observations.
  Criteria for being OK is that the last observation's timestamp does not lie
  within the [now-waittime,now] interval. Wait time must be provided in
  minutes."
  [db-con wait-time]
  (let [obs-recorded (:recorded
                      (jdbc/execute-one! db-con
                                         ["SELECT recorded FROM observations
                                          WHERE id = (SELECT obs_id FROM
                                          weather_data ORDER BY id DESC
                                          LIMIT 1)"]
                                         rs-opts))]
    (if-not (nil? obs-recorded)
      (not (t/contains? (t/interval (t/minus (t/offset-date-time)
                                             (t/minutes wait-time))
                                    (t/offset-date-time))
                        obs-recorded))
      true)))

(defn -get-fmi-weather-forecast
  "Fetches the latest FMI HIRLAM weather forecasrt from the FMI WFS for the
  given weather  observation station. If fetching or parsing failed, nil is
  returned."
  [station-id]
  (let [url (format (str "https://opendata.fmi.fi/wfs?service=WFS&version=2.0.0"
                         "&request=getFeature&storedquery_id=fmi::forecast::"
                         "hirlam::surface::point::multipointcoverage&fmisid=%d"
                         "&parameters=Temperature,WindSpeedMS,TotalCloudCover"
                         "&starttime=%s")
                    station-id
                    (str (first (s/split
                                 (str (t/instant (t/with-zone
                                                   (calculate-start-time)
                                                   "Europe/Helsinki")))
                                 #"\.\d+")) "Z"))]
    (try
      (extract-forecast-data (parse url))
      (catch org.xml.sax.SAXParseException _
        (log/error "FMI forecast parsing failed")
        nil)
      (catch Exception e
        (log/error (str "FMI forecast fetch failed, status: "
                        (str e)))
        nil))))

;; OWM

(defn fetch-owm-data
  "Fetch weather data from OpenWeatherMap, this data contains both current
  weather and forecast data. Returns nil map if query failed."
  [app-id latitude longitude]
  (let [url (format (str "https://api.openweathermap.org/data/2.5/onecall?"
                         "lat=%s&lon=%s&exclude=minutely,daily,alerts&"
                         "units=metric&appid=%s")
                    (str latitude)
                    (str longitude)
                    app-id)
        resp (try
               (client/get url)
               (catch Exception e
                 (log/error (str "OWM data fetch failed, status: " (str e)))
                 nil))]
    (when (= 200 (:status resp))
      (let [all-data (parse-string (:body resp) true)]
        {:current (:current all-data)
         :forecast (nth (:hourly all-data) 1)}))))

;; Cache

(defn -cache-set-value
  "Set a value for the given item in a given cache."
  [cache item value]
  (c/evict cache item)
  (c/through-cache cache item (constantly value)))

(defn- update-cache-data
  "Updates the weather data in the cache."
  []
  (-cache-set-value weather-cache
                    :data
                    {:fmi (-get-fmi-weather-forecast (get-conf-value
                                                      :station-id))
                     :owm (fetch-owm-data (get-conf-value :owm-app-id)
                                          (get-conf-value :forecast-lat)
                                          (get-conf-value :forecast-lon))})
  (-cache-set-value weather-cache
                    :recorded
                    (str (t/local-date-time))))

;; General

(defn get-weather-data
  "Get weather (FMI and OpenWeatherMap) weather data from cache if it is
  recent enough.
  Otherwise fetch updated data and store it in the cache. Always return
  the available data."
  []
  (if-not (nil? (c/lookup weather-cache :recorded))
    (when (>= (t/time-between (t/local-date-time
                               (c/lookup weather-cache :recorded))
                              (t/local-date-time)
                              :minutes)
              (get-conf-value :weather-query-threshold))
      (update-cache-data))
    (update-cache-data))
  (c/lookup weather-cache :data))
