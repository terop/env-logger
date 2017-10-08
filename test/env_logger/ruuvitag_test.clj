(ns env-logger.ruuvitag-test
  (:require [clj-time.core :as t]
            [clj-time.coerce :as c]
            [clj-time.format :as f]
            [clojure.test :refer :all]
            [env-logger.ruuvitag :refer :all])
  (:import java.util.concurrent.TimeUnit
           org.influxdb.dto.Query
           org.influxdb.InfluxDBFactory
           org.influxdb.dto.Point
           java.util.Calendar))

(def test-influx {:url "http://localhost:8086"
                  :username "notneeded"
                  :password "notneeded"
                  :database "env_logger_test"})
(def conn (.setDatabase (InfluxDBFactory/connect (:url test-influx)
                                                 (:username test-influx)
                                                 (:password test-influx))
                        (:database test-influx)))

(defn prepare-db
  "Prepares the InfluxDB for running tests and cleans it up after tests
  are run."
  [test-fn]
  (.query conn (new Query "DELETE FROM observations"
                    (:database test-influx)))
  (test-fn)
  (.query conn (new Query "DELETE FROM observations"
                    (:database test-influx))))

;; Fixture run at the start and end of tests
(use-fixtures :once prepare-db)

(defn add-utc-offset
  "Adds the UTC offset to the given date."
  [date]
  (t/plus date (t/hours (get-utc-offset date))))

(defn influxdb-insert
  "A function for inserting InfluxDB rows for testing."
  []
  (.write conn (-> (Point/measurement "observations")
                   (.time (c/to-long (t/minus (t/now) (t/minutes 3)))
                          (TimeUnit/MILLISECONDS))
                   (.addField "location" "indoor")
                   (.addField "temperature" 20.0)
                   (.addField "humidity" 38.1)
                   (.build)))
  (.write conn (-> (Point/measurement "observations")
                   (.time (c/to-long (t/minus (t/now) (t/minutes 1)))
                          (TimeUnit/MILLISECONDS))
                   (.addField "location" "indoor")
                   (.addField "temperature" 20.2)
                   (.addField "humidity" 38.0)
                   (.build))))

(deftest utc-offset
  (testing "UTC offset calculation"
    (is (= 3 (get-utc-offset (f/parse (f/formatter "d.M.y")
                                      "1.10.2017"))))
    (is (= 2 (get-utc-offset (f/parse (f/formatter "d.M.y")
                                      "30.10.2017"))))))

(deftest rt-to-db-mapping
  (testing "RuuviTag to DB observation mapping"
    (influxdb-insert)
    (let [db-obs (list {:recorded (f/unparse (f/formatters
                                              :date-hour-minute-second)
                                             (t/minus (t/now)
                                                      (t/minutes 5)))}
                       {:recorded (f/unparse (f/formatters
                                              :date-hour-minute-second)
                                             (t/minus (t/now)
                                                      (t/seconds 30)))})
          rt-obs (get-rt-obs test-influx
                             (add-utc-offset (t/minus (t/now)
                                                      (t/minutes
                                                       5)))
                             (add-utc-offset (t/now)))]
      (is (= (list {:rt-temperature 20.0
                    :rt-humidity 38.1
                    :recorded (f/unparse (f/formatters :date-hour-minute-second)
                                         (t/minus (t/now)
                                                  (t/minutes 5)))}
                   {:rt-temperature 20.2
                    :rt-humidity 38.0
                    :recorded (f/unparse (f/formatters :date-hour-minute-second)
                                         (t/minus (t/now)
                                                  (t/seconds 30)))})
             (map-db-and-rt-obs db-obs rt-obs))))))

(deftest observation-query
  (testing "Querying of observations"
    (.query conn (new Query "DELETE FROM observations"
                      (:database test-influx)))
    (is (empty? (get-rt-obs test-influx
                            (add-utc-offset (t/minus (t/now)
                                                     (t/minutes 30)))
                            (add-utc-offset (t/minus (t/now)
                                                     (t/minutes 10))))))
    (is (empty? (get-rt-obs test-influx
                            (add-utc-offset (t/minus (t/now)
                                                     (t/minutes 5)))
                            (add-utc-offset (t/now)))))
    (influxdb-insert)
    (let [observation (get-rt-obs test-influx
                                  (add-utc-offset (t/minus (t/now)
                                                           (t/minutes 2)))
                                  (add-utc-offset (t/now)))]
      (is (= (count observation) 1))
      (is (= (count (nth observation 0)) 3))
      (is (re-matches (re-pattern (str (f/unparse (f/formatter :date)
                                                  (t/now)) "T.+"))
                      (:recorded (nth observation 0))))
      (is (= {:rt-temperature 20.2
              :rt-humidity 38.0}
             (dissoc (nth observation 0) :recorded))))
    (is (= (count (get-rt-obs test-influx
                              (add-utc-offset (t/minus (t/now)
                                                       (t/minutes 5)))
                              (add-utc-offset (t/now))))
           2))))
