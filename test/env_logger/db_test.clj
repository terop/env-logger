(ns env-logger.db-test
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as tco]
            [clj-time.local :as tl]
            [clj-time.format :as tf]
            [clj-time.jdbc]
            [clojure.test :refer :all]
            [env-logger.config :refer [db-conf]]
            [env-logger.db :refer [format-datetime get-all-obs
                                   get-last-n-days-obs
                                   insert-observation
                                   get-obs-within-interval]]
            [korma.core :as kc]
            [korma.db :refer [defdb postgres]]))

(defdb db (postgres {:db "env_logger_test"
                     :user (db-conf :username)
                     :password (db-conf :password)}))
(kc/defentity observations)
(kc/defentity beacons)

;; Current datetime used in tests
(def current-dt (tco/now))
(def formatter :date-hour-minute-second)

(defn clean-test-database
  "Cleans the test database before and after running tests."
  [test-fn]
  (kc/delete observations)
  (kc/insert observations
             (kc/values {:recorded (tl/to-local-date-time
                                    (tco/minus current-dt
                                               (tco/days 4)))
                         :brightness 5
                         :temperature 20}))
  (test-fn)
  (kc/delete observations))

;; Fixture run at the start and end of tests
(use-fixtures :once clean-test-database)

(deftest insert-observations
  (testing "Observation insertion"
    (is (true? (insert-observation {:timestamp
                                    (str (tl/to-local-date-time current-dt))
                                    :inside_light 0
                                    :inside_temp 20
                                    :beacons [{:rssi -68,
                                               :mac "7C:EC:79:3F:BE:97"}]})))
    (is (false? (insert-observation {})))))

(deftest date-formatting
  (testing "Date formatting function"
    (is (= (tl/format-local-time current-dt formatter)
           (format-datetime (tl/to-local-date-time current-dt)
                            :date-hour-minute-second)))))

(deftest all-observations
  (testing "Selecting all observations"
    ;; Temperature offset is *currently* 9
    (let [all-obs (get-all-obs)]
      (is (= (count all-obs) 2))
      (is (= {:brightness 0
              :recorded (tl/format-local-time current-dt formatter)
              :temperature 11.0}
             (nth all-obs 1))))))

(deftest n-days-observations
  (testing "Selecting observations from N days"
    (is (= {:brightness 0
            :recorded (tl/format-local-time current-dt formatter)
            :temperature 11.0}
           (first (get-last-n-days-obs 3))))))

(deftest date-interval-select
  (testing "Select observations between one or two dates"
    (let [formatter (tf/formatter "d.M.y")]
      (is (= (count (get-obs-within-interval nil nil)) 2))
      (is (= (count (get-obs-within-interval
                     (tf/unparse formatter (tco/minus current-dt (tco/days 1)))
                     nil)) 1))
      (is (= (count (get-obs-within-interval
                     nil
                     (tf/unparse formatter (tco/minus current-dt
                                                      (tco/days 2)))))
             1))
      (is (= (count (get-obs-within-interval
                     (tf/unparse formatter (tco/minus current-dt (tco/days 6)))
                     (tf/unparse formatter current-dt))) 2))
      (is (zero? (count (get-obs-within-interval "foobar" nil))))
      (is (zero? (count (get-obs-within-interval nil "foobar"))))
      (is (zero? (count (get-obs-within-interval "bar" "foo")))))))
