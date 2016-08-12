(ns env-logger.grabber
  "Namespace for (XML) data grabbing functions"
  (:require [clj-http.client :as client]
            [clojure.xml :as xml]
            [clj-time.core :as t]
            [clj-time.format :as f]))

(defn parse-xml
  "Parse the provided string as XML"
  [s]
  (xml/parse
   (java.io.ByteArrayInputStream. (.getBytes s))))

(defn extract-data
  "Parses and returns temperature and cloud cover information from the XML
  document body. It is assumed that there only one set of data in the body."
  [xml-body]
  (if (not= (count xml-body) 2)
    ;; Invalid data
    nil
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
  "Calculates the start time for the data request. The time is the closest
  even ten minutes in the past, example: for 08:27 it would be 08:20."
  []
  (let [curr-minute (t/minute (t/now))
        start-time (f/unparse (f/formatters :date-time-no-ms)
                              (t/minus (t/now)
                                       (t/minutes (- curr-minute
                                                     (- curr-minute
                                                        (mod curr-minute 10))))
                                       (t/seconds (t/second (t/now)))))]
    start-time))

(defn get-latest-fmi-data
  "Fetches and returns the latest FMI data from the given weather
  observation station."
  [fmi-api-key station-id]
  (let [url (format (str "http://data.fmi.fi/fmi-apikey/%s/wfs?request="
                         "getFeature&storedquery_id=fmi::observations::"
                         "weather::simple&fmisid=%d&parameters=t2m,n_man"
                         "&starttime=%s") fmi-api-key station-id
                    (calculate-start-time))]
    (extract-data (:content (parse-xml (:body (client/get url)))))))
