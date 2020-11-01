(ns env-logger.grabber
  "Namespace for data grabbing functions"
  (:require [cheshire.core :refer [parse-string]]
            [clj-http.client :as client]
            [clj-time
             [coerce :as e]
             [core :as t]
             [format :as f]]
            [clojure.java.jdbc :as j]
            [clojure.tools.logging :as log]
            [clojure.xml :as xml]))

(defn parse-xml
  "Parse the provided string as XML."
  [^String s]
  (xml/parse
   (java.io.ByteArrayInputStream. (.getBytes s))))

(defn extract-data
  "Parses and returns temperature and cloud cover information from the XML
  document body. It is assumed that there only one set of data in the body."
  [xml-body]
  (when-not (not= (count xml-body) 3)
    (let [data (for [elem xml-body]
                 (map #(->> %
                            :content
                            first)
                      (->> elem
                           :content
                           first
                           :content
                           (filter #(contains?
                                     #{:BsWfs:Time
                                       :BsWfs:ParameterName
                                       :BsWfs:ParameterValue}
                                     (:tag %))))))]
      {:date (nth (first data) 0)
       :temperature (Float/parseFloat (nth (first data) 2))
       :cloudiness (int (Float/parseFloat (nth (nth data 1) 2)))})))

(defn calculate-start-time
  "Calculates the start time for the data request and returns it as a
  DateTime object. The time is the closest even ten minutes in the past,
  example: for 08:27 it would be 08:20."
  []
  (let [curr-minute (t/minute (t/now))
        start-time (t/minus (t/now)
                            (t/minutes (- curr-minute
                                          (- curr-minute
                                             (mod curr-minute 10))))
                            (t/seconds (t/second (t/now))))]
    start-time))

(defn -get-latest-fmi-weather-data-wfs
  "Fetches the latest FMI data from the FMI WFS for the given weather
  observation station. If fetching or parsing failed, nil is returned."
  [station-id]
  (let [url (format (str "https://opendata.fmi.fi/wfs?service=WFS&version=2.0.0"
                         "&request=getFeature&storedquery_id="
                         "fmi::observations::weather::simple&fmisid=%d&"
                         "parameters=t2m,n_man,p_sea&starttime=%s") station-id
                    (f/unparse (f/formatters :date-time-no-ms)
                               (calculate-start-time)))
        response (try
                   (client/get url)
                   (catch Exception e
                     (log/error (str "FMI weather data fetch failed, status: "
                                     (str e)))))]
    (when-not (nil? response)
      (try
        (extract-data (:content (parse-xml (:body response))))
        (catch org.xml.sax.SAXParseException e
          (log/error "FMI weather data XML parsing failed")
          nil)))))

(defn -get-latest-fmi-weather-data-json
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
                        (zero? (count (get json-resp "TotalCloudCover"))))))
      {:date (f/unparse (f/formatter :date-time-no-ms)
                        (t/from-time-zone
                         (e/from-long (get json-resp "latestObservationTime"))
                         (t/time-zone-for-id (get json-resp "timeZoneId"))))
       :temperature ((last (get json-resp "t2m")) 1)
       :cloudiness (int ((last (get json-resp "TotalCloudCover")) 1))})))

(defn get-latest-fmi-weather-data
  "Fetches the latest FMI weather data either using
  1) HTTTP request in JSON
  2) WFS
  for the given weather observation station. If fetching or parsing failed,
  nil is returned."
  [station-id]
  (or (-get-latest-fmi-weather-data-json station-id)
      (-get-latest-fmi-weather-data-wfs station-id)))

(defn weather-query-ok?
  "Tells whether it is OK to query the FMI API for weather observations.
  Criteria for being OK is that the last observation's timestamp does not lie
  within the [now-waittime,now] interval. Wait time must be provided in
  minutes."
  [db-con wait-time]
  (let [obs-recorded (:recorded (first
                                 (j/query db-con
                                          "SELECT recorded FROM observations
                                          WHERE id = (SELECT obs_id FROM
                                          weather_data ORDER BY id DESC
                                          LIMIT 1)")))]
    (if-not (nil? obs-recorded)
      (not (t/within? (t/interval (t/minus (t/now) (t/minutes wait-time))
                                  (t/now))
                      obs-recorded))
      true)))
