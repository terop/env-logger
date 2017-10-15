(ns env-logger.ruuvitag-test
  (:require [clj-time.core :as t]
            [clj-time.coerce :as c]
            [clj-time.format :as f]
            [clojure.test :refer :all]
            [env-logger.influx :refer [get-ruuvitag-observations]])
  (:import java.util.concurrent.TimeUnit
           org.influxdb.dto.Query
           org.influxdb.InfluxDBFactory
           org.influxdb.dto.Point))

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

(deftest observation-query
  (testing "Querying of observations"
    (is (empty? (get-ruuvitag-observations test-influx
                                           (t/minus (t/now)
                                                    (t/minutes 30))
                                           (t/minus (t/now)
                                                    (t/minutes 10)))))
    (is (empty? (get-ruuvitag-observations test-influx
                                           (t/minus (t/now)
                                                    (t/minutes 5))
                                           "")))
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
                     (.build)))
    (let [observation (get-ruuvitag-observations test-influx
                                                 (t/minus (t/now) (t/minutes 2))
                                                 (t/now))]
      (is (= (count observation) 1))
      (is (= (count (nth observation 0)) 3))
      (is (re-matches (re-pattern (str (f/unparse (f/formatter :date)
                                                  (t/now)) "T.+"))
                      (:recorded (nth observation 0))))
      (is (= {:rt-temperature 20.2
              :rt-humidity 38.0}
             (dissoc (nth observation 0) :recorded))))
    (is (= (count (get-ruuvitag-observations test-influx
                                             (t/minus (t/now) (t/minutes 5))
                                             (t/now)))
           2))))

(deftest map-to-closest
  (testing "RuuviTag to DB observation mapping"
    (is (= 1 1))))
