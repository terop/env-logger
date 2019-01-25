(ns env-logger.db-test
  (:require [clojure.test :refer :all]
            [clj-time.core :as t]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [clj-time.jdbc]
            [clojure.java.jdbc :as j]
            [env-logger.config :refer [db-conf get-conf-value]]
            [env-logger.db :refer :all])
  (:import (org.postgresql.util PSQLException
                                PSQLState)))

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
  "Cleans the test database before and after running tests. Also inserts one
  observation before running tests."
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
  (j/execute! test-postgres "DELETE FROM observations")
  (j/execute! test-postgres "DELETE FROM yardcam_images"))

;; Fixture run at the start and end of tests
(use-fixtures :once clean-test-database)

(defn iso8601-dt-str
  "Returns the the current datetime in UTC ISO 8601 formatted."
  []
  (str (l/to-local-date-time current-dt)))

(deftest insert-observations
  (testing "Full observation insert"
    (let [observation {:timestamp (iso8601-dt-str)
                       :inside_light 0
                       :inside_temp 20
                       :beacons [{:rssi -68
                                  :mac "7C:EC:79:3F:BE:97"}]}
          weather-data {:date (iso8601-dt-str)
                        :temperature 20
                        :cloudiness 2
                        :pressure 1006.5}]
      (is (true? (insert-observation test-postgres
                                     (merge observation
                                            {:outside_temp 5
                                             :weather-data weather-data}))))
      (is (true? (insert-observation test-postgres
                                     (merge observation
                                            {:outside_temp nil
                                             :weather-data nil}))))
      (is (false? (insert-observation test-postgres {})))
      (with-redefs [insert-wd (fn [_ _ _] -1)]
        (let [obs-count (first (j/query test-postgres
                                        "SELECT COUNT(id) FROM observations"))]
          (is (false? (insert-observation test-postgres
                                          (merge observation
                                                 {:outside_temp 5
                                                  :weather-data
                                                  weather-data}))))
          (is (= obs-count
                 (first (j/query test-postgres
                                 "SELECT COUNT(id) FROM observations"))))))
      (with-redefs [insert-beacons (fn [_ _ _] '(-1))]
        (is (false? (insert-observation test-postgres
                                        (merge observation
                                               {:outside_temp nil
                                                :weather-data nil})))))
      (with-redefs [insert-plain-observation
                    (fn [_ _ _ _] (throw (PSQLException.
                                          "Test exception")))]
        (is (false? (insert-observation test-postgres observation)))))))

(deftest date-formatting
  (testing "Date formatting function"
    (is (= (l/format-local-time current-dt formatter)
           (format-datetime (l/to-local-date-time current-dt)
                            :date-hour-minute-second)))))

(deftest n-days-observations
  (testing "Selecting observations from N days"
    (is (= {:brightness 0
            :recorded (l/format-local-time current-dt formatter)
            :temperature 14.0
            :cloudiness 2
            :fmi_temperature 20.0
            :o_temperature 5.0
            :pressure 1006.5
            :yc_image_name "testimage.jpg"
            :name "7C:EC:79:3F:BE:97"
            :rssi -68
            :tb_image_name nil
            :temp_delta -15.0}
           (nth (get-obs-days test-postgres 3) 1)))))

(deftest obs-interval-select
  (testing "Select observations between one or two dates"
    (let [formatter (f/formatter "d.M.y")]
      (is (= 4 (count (get-obs-interval
                       test-postgres
                       {:start nil
                        :end nil}))))
      (is (= 3 (count (get-obs-interval
                       test-postgres
                       {:start (f/unparse formatter
                                          (t/minus current-dt
                                                   (t/days 1)))
                        :end nil}))))
      (is (= 1 (count (get-obs-interval
                       test-postgres
                       {:start nil
                        :end (f/unparse formatter
                                        (t/minus current-dt
                                                 (t/days 2)))}))))
      (is (= 4 (count (get-obs-interval
                       test-postgres
                       {:start (f/unparse formatter
                                          (t/minus current-dt
                                                   (t/days 6)))
                        :end (f/unparse formatter
                                        current-dt)}))))
      (is (zero? (count (get-obs-interval
                         test-postgres
                         {:start (f/unparse formatter
                                            (t/minus current-dt
                                                     (t/days 11)))
                          :end (f/unparse formatter
                                          (t/minus current-dt
                                                   (t/days 10)))}))))
      (is (zero? (count (get-obs-interval
                         test-postgres
                         {:start "foobar"
                          :end nil}))))
      (is (zero? (count (get-obs-interval
                         test-postgres
                         {:start nil
                          :end "foobar"}))))
      (is (zero? (count (get-obs-interval
                         test-postgres
                         {:start "bar"
                          :end "foo"})))))))

(deftest get-observations-tests
  (testing "Observation querying with arbitrary WHERE clause and LIMIT"
    (is (= 1 (count (get-observations test-postgres
                                      :where [:= :o.temperature 20]))))
    (is (= 1 (count (get-observations test-postgres
                                      :limit 1))))
    (is (zero? (count (get-observations test-postgres
                                        :limit 0))))))

(deftest start-and-end-date-query
  (testing "Selecting start and end dates of all observations"
    (let [formatter (f/formatter "d.M.y")]
      (is (= (f/unparse formatter (t/minus current-dt
                                           (t/days 4)))
             (:start (get-obs-start-date test-postgres))))
      (is (= (f/unparse formatter current-dt)
             (:end (get-obs-end-date test-postgres))))
      (with-redefs [j/query (fn [db query] '())]
        (is (= {:start ""}
               (get-obs-start-date test-postgres)))
        (is (= {:end ""}
               (get-obs-end-date test-postgres))))
      (with-redefs [j/query (fn [db query]
                              (throw (PSQLException.
                                      "Test exception"
                                      (PSQLState/COMMUNICATION_ERROR))))]
        (is (= {:error :db-error}
               (get-obs-start-date test-postgres)))
        (is (= {:error :db-error}
               (get-obs-end-date test-postgres)))))))

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
      (is (= (l/to-local-date-time (f/parse formatter "1.12.2016 00:00:00"))
             (make-local-dt "1.12.2016" "start")))
      (is (= (l/to-local-date-time (f/parse formatter "1.12.2016 23:59:59"))
             (make-local-dt "1.12.2016" "end"))))))

(deftest weather-obs-interval-select
  (testing "Select weather observations between one or two dates"
    (let [formatter (f/formatter "d.M.y")]
      (is (= 1 (count (get-weather-obs-interval test-postgres
                                                {:start nil
                                                 :end nil}))))
      (is (= 1 (count (get-weather-obs-interval
                       test-postgres
                       {:start (f/unparse formatter
                                          (t/minus current-dt
                                                   (t/days 1)))
                        :end nil}))))
      (is (zero? (count (get-weather-obs-interval
                         test-postgres
                         {:start nil
                          :end (f/unparse formatter
                                          (t/minus current-dt
                                                   (t/days 2)))}))))
      (is (zero? (count (get-weather-obs-interval
                         test-postgres
                         {:start (f/unparse formatter
                                            (t/minus current-dt
                                                     (t/days 5)))
                          :end (f/unparse formatter
                                          (t/minus current-dt
                                                   (t/days 3)))})))))))

(deftest weather-days-observations
  (testing "Selecting weather observations from N days"
    (is (= {:time (l/format-local-time current-dt formatter)
            :cloudiness 2
            :fmi_temperature 20.0
            :o_temperature 5.0
            :pressure 1006.5
            :tb_image_name nil
            :temp_delta -15.0}
           (first (get-weather-obs-days test-postgres 1))))))

(deftest weather-observation-select
  (testing "Selecting weather observations with an arbitrary WHERE clause"
    (is (zero? (count (get-weather-observations test-postgres
                                                :where [:= 1 0]))))))

(deftest yc-image-name-storage
  (testing "Storing of the latest Yardcam image name"
    (j/insert! test-postgres
               :yardcam_images
               {:image_name "testimage.jpg"})
    (j/insert! test-postgres
               :yardcam_images
               {:image_name "testimage2.jpg"})
    (is (= 2 (count (j/query test-postgres
                             "SELECT image_id FROM yardcam_images"))))
    (is (true? (insert-yc-image-name test-postgres "testimage.jpg")))
    (is (= 1 (count (j/query test-postgres
                             "SELECT image_id FROM yardcam_images"))))
    (is (false? (insert-yc-image-name test-postgres nil)))))

(deftest yc-image-name-query
  (testing "Querying of the yardcam image name"
    (j/execute! test-postgres "DELETE FROM yardcam_images")
    (is (nil? (get-yc-image test-postgres)))
    (j/insert! test-postgres
               :yardcam_images
               {:image_name "testimage.jpg"})
    (is (= "testimage.jpg" (get-yc-image test-postgres)))))

(deftest last-observation-id
  (testing "Query of last observation's ID"
    (let [last-id (first (j/query test-postgres
                                  "SELECT MAX(id) AS id FROM observations"
                                  {:row-fn #(:id %)}))]
      (is (= last-id (get-last-obs-id test-postgres))))))

(deftest testbed-image-storage
  (testing "Storage of a Testbed image"
    (is (true? (insert-tb-image-name test-postgres
                                     (get-last-obs-id test-postgres)
                                     "testbed-2017-04-21T19:16+0300.png")))))

(deftest wd-insert
  (testing "Insert of FMI weather data"
    (let [obs-id (first (j/query test-postgres
                                 "SELECT MIN(id) AS id FROM observations"
                                 {:row-fn #(:id %)}))
          weather-data {:date (iso8601-dt-str)
                        :temperature 20
                        :cloudiness 2
                        :pressure 1006.5}]
      (is (pos? (insert-wd test-postgres
                           obs-id
                           weather-data))))))

(deftest ruuvitag-observation-insert
  (testing "Insert of RuuviTag observation"
    (let [ruuvitag-obs {:location "indoor"
                        :temperature 21
                        :pressure 1100
                        :humidity 25}]
      (is (pos? (insert-ruuvitag-observation test-postgres
                                             ruuvitag-obs)))
      (= -1 (insert-ruuvitag-observation test-postgres
                                         (dissoc ruuvitag-obs :temperature))))))

(deftest beacon-insert
  (testing "Insert of beacon(s)"
    (let [obs-id (first (j/query test-postgres
                                 "SELECT MIN(id) AS id FROM observations"
                                 {:row-fn #(:id %)}))
          beacon {:rssi -68
                  :mac "7C:EC:79:3F:BE:97"}]
      (is (pos? (first (insert-beacons test-postgres
                                       obs-id
                                       {:beacons [beacon]})))))))

(deftest plain-observation-insert
  (testing "Insert of a row into the observations table"
    (is (pos? (insert-plain-observation test-postgres
                                        {:timestamp (str (l/to-local-date-time
                                                          current-dt))
                                         :inside_light 0
                                         :inside_temp 20
                                         :outside_temp 5
                                         :offset 6
                                         :image-name "testimage.jpg"})))))

(deftest db-connection-test
  (testing "Connection to the DB"
    (is (true? (test-db-connection test-postgres)))
    (with-redefs [j/query (fn [db query]
                            (throw (PSQLException.
                                    "Test exception"
                                    (PSQLState/COMMUNICATION_ERROR))))]
      (is (false? (test-db-connection test-postgres))))))
