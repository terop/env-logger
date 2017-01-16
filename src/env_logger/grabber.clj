(ns env-logger.grabber
  "Namespace for data grabbing functions"
  (:require [clj-http.client :as client]
            [clojure.xml :as xml]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clojure.tools.logging :as log])
  (:import org.jsoup.Jsoup))

(defn parse-xml
  "Parse the provided string as XML"
  [s]
  (xml/parse
   (java.io.ByteArrayInputStream. (.getBytes s))))

(defn extract-data
  "Parses and returns temperature and cloud cover information from the XML
  document body. It is assumed that there only one set of data in the body."
  [xml-body]
  (when-not (not= (count xml-body) 2)
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

(defn get-latest-fmi-data
  "Fetches and returns the latest FMI data from the given weather
  observation station. If the fetch or parsing failed, {} will be returned."
  [fmi-api-key station-id]
  (let [url (format (str "http://data.fmi.fi/fmi-apikey/%s/wfs?request="
                         "getFeature&storedquery_id=fmi::observations::"
                         "weather::simple&fmisid=%d&parameters=t2m,n_man"
                         "&starttime=%s") fmi-api-key station-id
                    (f/unparse (f/formatters :date-time-no-ms)
                               (calculate-start-time)))
        response (try
                   (client/get url)
                   (catch Exception e
                     (log/error (str "FMI weather data fetch failed, status:"
                                     (:status (ex-data e))))))]
    (if (nil? response)
      {}
      (try
        (extract-data (:content (parse-xml (:body response))))
        (catch org.xml.sax.SAXParseException e
          (log/error "FMI weather data XML parsing failed")
          {})))))

(defn get-testbed-image
  "Fetches the latest precipitation image from FMI Testbed
  (http://testbed.fmi.fi) and returns it as a byte array. If the
  fetching fails, nil will be returned."
  []
  (let [tb-resp (try
                  (client/get "http://testbed.fmi.fi")
                  (catch Exception e
                    (log/error "Testbed image fetch failed: HTTP status code"
                               (:status (ex-data e)))))]
    (if (not= 200 (:status tb-resp))
      (log/error "Testbed page load failed")
      (let [jsoup-doc (Jsoup/parse (:body tb-resp))
            img-src (.attr (.select jsoup-doc "img[src^=data/area]") "src")]
        (if (empty? img-src)
          (log/error "Testbed image fetch failed: image element"
                     "src was empty")
          (let [resp (client/get (str "http://testbed.fmi.fi/" img-src)
                                 {:as :byte-array})]
            (if (not= 200 (:status resp))
              (log/error "Testbed image fetch failed: image download failed")
              (:body resp))))))))
