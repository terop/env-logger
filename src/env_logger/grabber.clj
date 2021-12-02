(ns env-logger.grabber
  "Namespace for data grabbing functions"
  (:require [clojure.tools.logging :as log]
            [clojure.string :as s]
            [clojure.xml :refer [parse]]
            [clojure.zip :refer [xml-zip]]
            [clojure.data.zip.xml :as zx]
            [cheshire.core :refer [parse-string]]
            [clj-http.client :as client]
            [next.jdbc :as jdbc]
            [java-time :as t]
            [env-logger.db :refer [rs-opts]])
  (:import java.util.Date
           java.sql.Timestamp))

(defn extract-data
  "Parses and returns temperature and cloud cover information from the XML
  document body. It is assumed that there only one set of data in the body."
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
       :cloudiness (int (Float/parseFloat (nth values 1)))
       :wind-speed (Float/parseFloat (nth values 2))})))

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
      (extract-data (parse url))
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
