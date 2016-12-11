(ns env-logger.db-test
  (:require [clj-time.core :as t]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [clj-time.jdbc]
            [clojure.test :refer :all]
            [env-logger.config :refer [db-conf]]
            [env-logger.db :refer [format-datetime
                                   insert-observation
                                   get-obs-days
                                   get-obs-interval
                                   get-obs-start-and-end
                                   get-observations
                                   validate-date
                                   make-date-dt]]
            [clojure.java.jdbc :as j]))

(let [db-host (get (System/getenv)
                   "POSTGRESQL_DB_HOST"
                   (db-conf :host))
      db-port (get (System/getenv)
                   "POSTGRESQL_DB_PORT"
                   (db-conf :port))
      db-name "env_logger_test"
      db-user (get (System/getenv)
                   "POSTGRESQL_DB_USERNAME"
                   (db-conf :username))
      db-password (get (System/getenv)
                       "POSTGRESQL_DB_PASSWORD"
                       (db-conf :password))]
  (def test-postgres {:classname "org.postgresql.Driver"
                      :subprotocol "postgresql"
                      :subname (format "//%s:%s/%s"
                                       db-host db-port db-name)
                      :user db-user
                      :password db-password}))

;; Current datetime used in tests
(def current-dt (t/now))
(def formatter :date-hour-minute-second)

(defn clean-test-database
  "Cleans the test database before and after running tests."
  [test-fn]
  (j/execute! test-postgres "DELETE FROM observations")
  (j/insert! test-postgres
             :observations
             {:recorded (l/to-local-date-time
                         (t/minus current-dt
                                  (t/days 4)))
              :brightness 5
              :temperature 20})
  (test-fn)
  (j/execute! test-postgres "DELETE FROM observations"))

;; Fixture run at the start and end of tests
(use-fixtures :once clean-test-database)

(deftest insert-observations
  (testing "Observation insertion"
    (is (true? (insert-observation test-postgres
                                   {:timestamp
                                    (str (l/to-local-date-time current-dt))
                                    :inside_light 0
                                    :inside_temp 20
                                    :beacons [{:rssi -68,
                                               :mac "7C:EC:79:3F:BE:97"}]
                                    :weather-data {:date
                                                   "2016-08-12T17:10:00Z"
                                                   :temperature 20
                                                   :cloudiness 2}})))
    (is (true? (insert-observation test-postgres
                                   {:timestamp
                                    (str (l/to-local-date-time current-dt))
                                    :inside_light 0
                                    :inside_temp 20
                                    :beacons [{:rssi -68,
                                               :mac "7C:EC:79:3F:BE:97"}]
                                    :weather-data {}})))
    (is (false? (insert-observation test-postgres {})))))

(deftest date-formatting
  (testing "Date formatting function"
    (is (= (l/format-local-time current-dt formatter)
           (format-datetime (l/to-local-date-time current-dt)
                            :date-hour-minute-second)))))

(deftest n-days-observations
  (testing "Selecting observations from N days"
    (is (= {:brightness 0
            :recorded (l/format-local-time current-dt formatter)
            :temperature 11.0
            :cloudiness 2
            :o_temperature 20.0}
           (first (get-obs-days test-postgres 3))))))

(deftest date-interval-select
  (testing "Select observations between one or two dates"
    (let [formatter (f/formatter "d.M.y")]
      (is (= 3 (count (get-obs-interval test-postgres nil nil))))
      (is (= 2 (count (get-obs-interval
                       test-postgres
                       (f/unparse formatter (t/minus current-dt
                                                     (t/days 1)))
                       nil))))
      (is (= 1 (count (get-obs-interval
                       test-postgres
                       nil
                       (f/unparse formatter (t/minus current-dt
                                                     (t/days 2)))))))
      (is (= 3 (count (get-obs-interval
                       test-postgres
                       (f/unparse formatter (t/minus current-dt
                                                     (t/days 6)))
                       (f/unparse formatter current-dt)))))
      (is (zero? (count (get-obs-interval test-postgres "foobar" nil))))
      (is (zero? (count (get-obs-interval test-postgres nil "foobar"))))
      (is (zero? (count (get-obs-interval test-postgres "bar" "foo")))))))

(deftest get-observations-tests
  (testing "Observation querying with arbitrary WHERE clause"
    (is (= 1 (count (get-observations test-postgres
                                      [:= :o.temperature 20]))))))

(deftest start-and-end-date-query
  (testing "Selecting start and end dates of all observations"
    (let [formatter (f/formatter "d.M.y")]
      (is (= (f/unparse formatter (t/minus current-dt
                                           (t/days 4)))
             (:start (get-obs-start-and-end test-postgres))))
      (is (= (f/unparse formatter current-dt)
             (:end (get-obs-start-and-end test-postgres)))))))

(deftest date-validation
  (testing "Tests for date validation"
    (is (true? (validate-date nil)))
    (is (false? (validate-date "foobar")))
    (is (false? (validate-date "1.12.201")))
    (is (true? (validate-date "1.12.2016")))
    (is (true? (validate-date "01.12.2016")))))

(deftest date-to-datetime
  (testing "Testing date to datetime conversion"
    (let [formatter (f/formatter "d.M.y H:m:s")]
      (is (= (f/parse formatter "1.12.2016 00:00:00")
             (make-date-dt "1.12.2016" "start")))
      (is (= (f/parse formatter "1.12.2016 23:59:59")
             (make-date-dt "1.12.2016" "end"))))))
