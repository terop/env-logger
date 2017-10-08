(ns env-logger.ruuvitag
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]
            [clojure.tools.logging :as log]
            [env-logger.db :refer [format-datetime]]
            [env-logger.config :refer [get-conf-value]])
  (:import org.influxdb.dto.Query
           org.influxdb.InfluxDBFactory
           java.util.Calendar))

(defn map-db-and-rt-obs
  "Returns the mapped DB and RuuviTag observations which lie closest to
  each other for each DB observation."
  [db-obs rt-obs]
  (let [make-range (fn [idx-start idx-end current length]
                     (let [offset (int (/ length 2))]
                       (range (if (< current length)
                                (+ idx-start current)
                                (- current offset))
                              (if (< (- idx-end current) length)
                                (+ (- current offset) offset 1)
                                (+ current offset 1)))))
        comp-fn (fn [rt-ob db-ob]
                  {:diff (Math/abs (- (c/to-long (f/parse
                                                  (f/formatter
                                                   :date-hour-minute-second)
                                                  (:recorded db-ob)))
                                      (c/to-long (f/parse
                                                  (f/formatter
                                                   :date-hour-minute-second)
                                                  (:recorded rt-ob)))))
                   :obs rt-ob})
        range-end (min (count db-obs)
                       (count rt-obs))]
    (for [index (range 0 (count db-obs))]
      (merge (nth db-obs index)
             (dissoc (:obs (first (sort-by :diff
                                           (for [rt-subset
                                                 (for [idx (make-range 0
                                                                       range-end
                                                                       index 3)]
                                                   (nth rt-obs idx))]
                                             (comp-fn rt-subset (nth db-obs
                                                                     index))))))
                     :recorded)))))

(defn get-utc-offset
  "Returns the offset to UTC for the given date."
  [date]
  (let [ms-to-h #(/ % 3600000)
        cal (Calendar/getInstance)]
    (.setTime cal (c/to-date date))
    (+ (ms-to-h (.get cal Calendar/ZONE_OFFSET))
       (ms-to-h (.get cal Calendar/DST_OFFSET)))))

(defn get-rt-obs
  "Returns RuuviTag observations lying between the provided timestamps."
  [connection-params start end]
  (try
    (let [conn (InfluxDBFactory/connect (:url connection-params)
                                        (:username connection-params)
                                        (:password connection-params))
          query (new Query (format (str "SELECT time, temperature, humidity "
                                        "FROM observations "
                                        "WHERE location = 'indoor' "
                                        "AND time >= '%s' "
                                        "AND time <= '%s'")
                                   (t/minus start
                                            (t/hours (get-utc-offset start)))
                                   (t/minus end
                                            (t/hours (get-utc-offset end))))
                     (:database connection-params))
          response (.query conn query)]
      (if-not (nil? (.getSeries (.get (.getResults response) 0)))
        (let [observations (.getValues (.get
                                        (.getSeries (.get (.getResults
                                                           response) 0)) 0))]
          (map (fn [obs]
                 {:recorded (format-datetime (f/parse (f/formatter :date-time)
                                                      (nth obs 0))
                                             :date-hour-minute-second)
                  :rt-temperature (nth obs 1)
                  :rt-humidity (nth obs 2)})
               observations))
        []))
    (catch org.influxdb.InfluxDBException ie
      (log/error "Exception when querying RuuviTag observations:"
                 (.getMessage ie))
      [])))
