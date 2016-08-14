(ns env-logger.db-test
  (:require [clj-time.core :as t]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [clj-time.jdbc]
            [clojure.test :refer :all]
            [env-logger.config :refer [db-conf]]
            [env-logger.db :refer [format-datetime get-all-obs
                                   get-last-n-days-obs
                                   insert-observation
                                   get-obs-within-interval]]
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
                                               :mac "7C:EC:79:3F:BE:97"}]})))
    (is (false? (insert-observation test-postgres {})))))

(deftest date-formatting
  (testing "Date formatting function"
    (is (= (l/format-local-time current-dt formatter)
           (format-datetime (l/to-local-date-time current-dt)
                            :date-hour-minute-second)))))

(deftest all-observations
  (testing "Selecting all observations"
    ;; Temperature offset is *currently* 9
    (let [all-obs (get-all-obs test-postgres)]
      (is (= 2 (count all-obs)))
      (is (= {:brightness 0
              :recorded (l/format-local-time current-dt formatter)
              :temperature 11.0}
             (nth all-obs 1))))))

(deftest n-days-observations
  (testing "Selecting observations from N days"
    (is (= {:brightness 0
            :recorded (l/format-local-time current-dt formatter)
            :temperature 11.0}
           (first (get-last-n-days-obs test-postgres 3))))))

(deftest date-interval-select
  (testing "Select observations between one or two dates"
    (let [formatter (f/formatter "d.M.y")]
      (is (= 2 (count (get-obs-within-interval test-postgres nil nil))))
      (is (= 1 (count (get-obs-within-interval
                       test-postgres
                       (f/unparse formatter (t/minus current-dt
                                                     (t/days 1)))
                       nil))))
      (is (= 1 (count (get-obs-within-interval
                       test-postgres
                       nil
                       (f/unparse formatter (t/minus current-dt
                                                     (t/days 2)))))))
      (is (= 2 (count (get-obs-within-interval
                       test-postgres
                       (f/unparse formatter (t/minus current-dt
                                                     (t/days 6)))
                       (f/unparse formatter current-dt)))))
      (is (zero? (count (get-obs-within-interval test-postgres "foobar" nil))))
      (is (zero? (count (get-obs-within-interval test-postgres nil "foobar"))))
      (is (zero? (count (get-obs-within-interval test-postgres "bar"
                                                 "foo")))))))
