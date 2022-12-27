(ns env-logger.weather
  "Namespace for weather fetching code"
  (:require [clojure.xml :refer [parse]]
            [clojure.zip :refer [xml-zip]]
            [clojure.data.zip.xml :as zx]
            [clojure.string :as s]
            [config.core :refer [env]]
            [taoensso.timbre :refer [error info]]
            [jsonista.core :as j]
            [clj-http.client :as client]
            [next.jdbc :as jdbc]
            [java-time.api :as t]
            [env-logger
             [db :refer [rs-opts -convert-to-iso8601-str]]])
  (:import java.time.temporal.ChronoUnit
           org.postgresql.util.PSQLException))
(refer-clojure :exclude '[filter for group-by into partition-by set update])
(require '[honey.sql :as sql])

(def fmi-current (atom {}))
(def fmi-forecast (atom nil))
(def owm (atom nil))

(def retry-count 3)

;; Utilities

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

(defn -convert-to-tz-iso8601-str
  "Formats and returns a datetime as an ISO 8601 formatted start time string
  having the :weather-zone value timezone before ISO 8601 conversion."
  [datetime]
  (-convert-to-iso8601-str (t/with-zone
                             datetime
                             (:weather-timezone env))))

(defn store-weather-data?
  "Tells whether to store FMI weather data.
  The criteria for storing is that the candidate datetime to be stored comes
  after the latest stored weather observation. Storing is also allowed if there
  is no latest stored observation."
  [db-con cand-dt]
  (try
    (if-not cand-dt
      false
      (let [latest-stored (:time (jdbc/execute-one!
                                  db-con
                                  (sql/format {:select :time
                                               :from :weather_data
                                               :order-by [[:id :desc]]
                                               :limit 1})
                                  rs-opts))]
        (if latest-stored
          (t/after? (t/local-date-time cand-dt)
                    (t/local-date-time latest-stored))
          true)))
    (catch PSQLException pe
      (error pe "Weather data storage check failed")
      false)))

;; FMI

(defn get-wd-str
  "Returns a dict with the long and short form of the given wind direction
  in degrees."
  [wind-direction]
  (if-not wind-direction
    {:short "invalid"
     :long "invalid"}
    (cond
      (and (>= wind-direction 0)
           (< wind-direction 25.0)) {:short "N"
                                     :long "north"}
      (and (>= wind-direction 25.0)
           (< wind-direction 65.0)) {:short "NE"
                                     :long "north east"}
      (and (>= wind-direction 65.0)
           (< wind-direction 115.0)) {:short "E"
                                      :long "east"}
      (and (>= wind-direction 115.0)
           (< wind-direction 155.0)) {:short "SE"
                                      :long "south east"}
      (and (>= wind-direction 155.0)
           (< wind-direction 205.0)) {:short "S"
                                      :long "south"}
      (and (>= wind-direction 205.0)
           (< wind-direction 245.0)) {:short "SW"
                                      :long "south west"}
      (and (>= wind-direction 245.0)
           (< wind-direction 295.0)) {:short "W"
                                      :long "west"}
      (and (>= wind-direction 295.0)
           (< wind-direction 335.0)) {:short "NW"
                                      :long "north west"}
      (and (>= wind-direction 335.0)
           (<= wind-direction 360.0)) {:short "N"
                                       :long "north"})))

(defn extract-weather-data
  "Parses and returns various weather data values from the given XML
  data. It is assumed that there only one set of values in the XML data."
  [parsed-xml]
  (when (>= (count (:content parsed-xml)) 4)
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
      {:time (t/sql-timestamp
              (t/local-date-time (t/instant date-text)
                                 (:weather-timezone env)))
       :temperature (Float/parseFloat (nth values 0))
       :cloudiness (Math/round (Float/parseFloat (nth values 1)))
       :wind-speed (Float/parseFloat (nth values 2))
       :wind-direction (get-wd-str (Float/parseFloat
                                    (nth values 3)))})))

(defn extract-forecast-data
  "Parses and returns forecast data values from the given XML data."
  [parsed-xml]
  (when (>= (count (:content parsed-xml)) 4)
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
      {:time (t/sql-timestamp
              (t/local-date-time (t/instant date-text)
                                 (:weather-timezone env)))
       :temperature (Float/parseFloat (format "%.1f" (Float/parseFloat
                                                      (nth values 0))))
       :wind-speed (Float/parseFloat (format "%.1f" (Float/parseFloat
                                                     (nth values 1))))
       :cloudiness (Math/round (Float/parseFloat (nth values 2)))
       :wind-direction (get-wd-str (Float/parseFloat
                                    (nth values 3)))
       :precipitation (Float/parseFloat (format "%.1f" (Float/parseFloat
                                                        (nth values 4))))})))

(defn -update-fmi-weather-data
  "Updates the latest FMI weather data from the FMI WFS for the given weather
  observation station."
  [station-id]
  (future
    (let [start-time-str (-convert-to-tz-iso8601-str (calculate-start-time))
          url (format (str "https://opendata.fmi.fi/wfs?service=WFS&version="
                           "2.0.0&request=getFeature&storedquery_id="
                           "fmi::observations::weather::simple&fmisid=%d&"
                           "parameters=t2m,n_man,ws_10min,wd_10min&"
                           "starttime=%s")
                      station-id
                      start-time-str)
          index (atom retry-count)]
      (try
        ;; The first check is to prevent pointless fetch attempts when data
        ;; is not yet available
        (while (and (>= (rem (.getMinute (t/local-date-time)) 10) 4)
                    (pos? @index)
                    (nil? (get @fmi-current start-time-str)))
          (let [wd (extract-weather-data (parse url))]
            (if wd
              (swap! fmi-current conj
                     {(-convert-to-iso8601-str (:time wd)) wd})
              (do
                (info (str "Retrying data fetch, attempt "
                           (- retry-count (dec @index)) " of " retry-count))
                (Thread/sleep 5000))))
          (swap! index dec))
        (catch org.xml.sax.SAXParseException spe
          (error spe "FMI weather data XML parsing failed")
          nil)
        (catch Exception ex
          (error ex "FMI weather data fetch failed")
          nil)))))

(defn -update-fmi-weather-data-json
  "Updates the latest FMI weather data from the FMI JSON for the given weather
  observation station."
  [station-id]
  (try
    ;; The first check is to prevent pointless fetch attempts when data
    ;; is not yet available
    (when (and (>= (rem (.getMinute (t/local-date-time)) 10) 4)
               (nil? (get @fmi-current (-convert-to-tz-iso8601-str
                                        (calculate-start-time)))))
      (let [url (format (str "https://www.ilmatieteenlaitos.fi/api/weather/"
                             "observations?fmisid=%s&observations=true")
                        station-id)
            parsed-json (j/read-value (:body (client/get url))
                                      (j/object-mapper
                                       {:decode-key-fn true}))]
        (when (:observations parsed-json)
          (let [obs (last (:observations parsed-json))
                local-dt (t/local-date-time "yMMddHHmmss"
                                            (s/replace (:localtime obs)
                                                       "T" ""))
                offset (.between ChronoUnit/HOURS
                                 (.atZone local-dt (t/zone-id (:localtz obs)))
                                 (.atZone local-dt (t/zone-id (:weather-timezone
                                                               env))))
                wd {:time (t/sql-timestamp
                           (t/minus
                            (t/zoned-date-time "yMMddHHmmss"
                                               (s/replace (:localtime obs)
                                                          "T" "")
                                               (:localtz obs))
                            (t/hours offset)))
                    :temperature (:t2m obs)
                    :cloudiness (:TotalCloudCover obs)
                    :wind-speed (:WindSpeedMS obs)
                    :wind-direction (get-wd-str (:WindDirection obs))}]
            (when (and wd
                       (not (nil? (:temperature wd))))
              (swap! fmi-current conj
                     {(-convert-to-iso8601-str (:time wd)) wd}))))))
    (catch Exception ex
      (error ex "FMI weather data (JSON) fetch failed")
      nil)))

(defn get-fmi-weather-data
  "Fetches the latest FMI weather data from the cache.
  If there is no data for the current or the previous check times nil
  is returned."
  []
  (or (get @fmi-current
           (-convert-to-tz-iso8601-str (calculate-start-time)))
      (get @fmi-current (-convert-to-tz-iso8601-str
                         (t/minus (calculate-start-time) (t/minutes 10))))))

(defn -update-fmi-weather-forecast
  "Updates the latest FMI HARMONIE weather forecast from the FMI WFS for the
  given weather observation station."
  [latitude longitude]
  (future
    (let [url (format (str "https://opendata.fmi.fi/wfs?service=WFS&version="
                           "2.0.0&request=getFeature&storedquery_id=fmi::"
                           "forecast::harmonie::surface::point::simple&latlon="
                           "%s&parameters=Temperature,WindSpeedMS,"
                           "TotalCloudCover,WindDirection,PrecipitationAmount"
                           "&starttime=%s&endtime=%s")
                      (str latitude "," longitude)
                      ;; Start time must always be ahead of the current time so
                      ;; that forecast for the next hour is fetched
                      (-convert-to-tz-iso8601-str (t/plus
                                                   (t/zoned-date-time)
                                                   (t/minutes 5)))
                      (-convert-to-tz-iso8601-str (t/plus
                                                   (t/zoned-date-time)
                                                   (t/hours 1))))
          index (atom retry-count)]
      (try
        ;; Fetch after x:56 seems to always fail so do not even attempt it
        (while (and (<= (.getMinute (t/local-date-time)) 56)
                    (pos? @index)
                    (or (nil? @fmi-forecast)
                        (< (abs (t/time-between (t/local-date-time
                                                 (:time @fmi-forecast))
                                                (t/local-date-time)
                                                :minutes)) 15)))
          (let [forecast (extract-forecast-data (parse url))]
            (if forecast
              (reset! fmi-forecast forecast)
              (do
                (info (str "Retrying forecast fetch, attempt "
                           (- retry-count (dec @index)) " of " retry-count))
                (Thread/sleep 5000))))
          (swap! index dec))
        (catch org.xml.sax.SAXParseException spe
          (error spe "FMI forecast parsing failed")
          nil)
        (catch Exception ex
          (error ex "FMI forecast fetch failed")
          nil)))))

;; OWM

(defn -fetch-owm-data
  "Fetch weather data from OpenWeatherMap, this data contains both current
  weather and forecast data."
  [app-id latitude longitude]
  (let [url (format (str "https://api.openweathermap.org/data/2.5/onecall?"
                         "lat=%s&lon=%s&exclude=minutely,daily,alerts&"
                         "units=metric&appid=%s")
                    (str latitude)
                    (str longitude)
                    app-id)]
    (when (or (nil? @owm)
              (> (abs (t/time-between
                       (t/local-date-time)
                       (:stored @owm)
                       :minutes)) 15))
      (let [resp (try
                   (client/get url)
                   (catch Exception ex
                     (error ex "OWM data fetch failed")
                     (reset! owm nil)))]
        (when (= 200 (:status resp))
          (let [all-data (j/read-value (:body resp)
                                       (j/object-mapper
                                        {:decode-key-fn true}))]
            (reset! owm
                    {:current (:current all-data)
                     :forecast (nth (:hourly all-data) 1)
                     :stored (t/local-date-time)})))))))

;; General

(defn fetch-all-weather-data
  "Fetches all (FMI current and forecast as well as OWM) weather data."
  []
  (when-not (-update-fmi-weather-data-json (:fmi-station-id env))
    (-update-fmi-weather-data (:fmi-station-id env)))
  (-update-fmi-weather-forecast (:weather-lat env)
                                (:weather-lon env))
  (-fetch-owm-data (:owm-app-id env)
                   (:weather-lat env)
                   (:weather-lon env)))

(defn get-weather-data
  "Get weather (FMI and OpenWeatherMap) weather data from cache if it is
  recent enough.
  Otherwise fetch updated data and store it in the cache. Always return
  the available data."
  []
  {:fmi {:current (get-fmi-weather-data)
         :forecast @fmi-forecast}
   :owm (dissoc @owm :stored)})
