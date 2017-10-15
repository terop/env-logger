(ns env-logger.ruuvitag
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]
            [clojure.tools.logging :as log]
            [env-logger.db :refer [format-datetime]]
            [env-logger.config :refer [get-conf-value]])
  (:import org.influxdb.dto.Query
           org.influxdb.InfluxDBFactory))

(defn map-to-closest-db-observation
  "Returns the closest DB observation with the RuuviTag data attached
  matching the given RuuviTag observations."
  [db-observations rt-observation]
  (let [abs #(if (neg? %) (- %) %)
        comp-fn (fn [observation]
                  {:diff (abs (- (c/to-long (f/parse (f/formatter
                                                      :date-hour-minute-second)
                                                     (:recorded observation)))
                                 (c/to-long (f/parse (f/formatter
                                                      :date-hour-minute-second)
                                                     (:recorded
                                                      rt-observation)))))
                   :obs observation})]
    (merge (:obs (first (sort-by #(:diff %)
                                 (map comp-fn db-observations))))
           (dissoc rt-observation :recorded))))

(defn get-ruuvitag-observations
  "Returns RuuviTag observations lying between the provided timestamps."
  [connection-params start end]
  (try
    (let [parse-dt-str #(t/to-time-zone (f/parse (f/formatter :date-time)
                                                 %)
                                        (t/time-zone-for-id
                                         (get-conf-value :timezone)))
          conn (InfluxDBFactory/connect (:url connection-params)
                                        (:username connection-params)
                                        (:password connection-params))
          query (new Query (format (str "SELECT time, temperature, humidity "
                                        "FROM observations "
                                        "WHERE location = 'indoor' "
                                        "AND time >= '%s' "
                                        "AND time <= '%s'") start end)
                     (:database connection-params))
          response (.query conn query)]
      (if-not (nil? (.getSeries (.get (.getResults response) 0)))
        (let [observations (.getValues (.get
                                        (.getSeries (.get (.getResults
                                                           response) 0)) 0))]
          (map (fn [obs]
                 {:recorded (format-datetime (parse-dt-str (nth obs 0))
                                             :date-hour-minute-second)
                  :rt-temperature (nth obs 1)
                  :rt-humidity (nth obs 2)})
               observations))
        []))
    (catch org.influxdb.InfluxDBException ie
      (log/error "Exception when querying RuuviTag observations:"
                 (.getMessage ie))
      [])))
