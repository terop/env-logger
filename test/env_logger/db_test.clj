(ns env-logger.db-test
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as tco]
            [clj-time.local :as tl]
            [clj-time.jdbc]
            [clojure.test :refer :all]
            [env-logger.config :refer [get-conf-value]]
            [env-logger.db :refer [format-datetime get-all-obs
                                   get-last-n-days-obs
                                   insert-observation]]
            [korma.core :as kc]
            [korma.db :refer [defdb postgres]]))

(defdb db (postgres {:db "env_logger_test"
                     :user (get-conf-value :database :username)
                     :password (get-conf-value :database :password)}))
(kc/defentity observations)

;; Current datetime used in tests
(def current-dt (tco/today-at 12 1 1))
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
    (is (true? (insert-observation {:timestamp (format "%s+02:00"
                                                       (tl/format-local-time
                                                        current-dt formatter))
                                    :inside_light 0
                                    :inside_temp 20})))
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

;; Config reading test
(deftest read-configuration-value
  (testing "Configuration value reading"
    (is (false? (get-conf-value :in-production)))
    (is (true? (get-conf-value :correction :enabled)))))
