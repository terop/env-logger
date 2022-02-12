(ns env-logger.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as s]
            [java-time :as t]
            [next.jdbc :as jdbc]
            [next.jdbc
             [result-set :as rs]
             [sql :as js]]
            [env-logger.config :refer [db-conf get-conf-value]]
            [env-logger.db :refer :all])
  (:import (org.postgresql.util PSQLException
                                PSQLState)
           (java.util Date
                      TimeZone)))
(refer-clojure :exclude '[filter for group-by into partition-by set update])
(require '[honey.sql :as sql])

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
  (def test-postgres {:dbtype "postgresql"
                      :dbname db-name
                      :host db-host
                      :port db-port
                      :user db-user
                      :password db-password})
  (def test-ds (jdbc/with-options (jdbc/get-datasource test-postgres)
                 {:builder-fn rs/as-unqualified-lower-maps})))

;; Current datetime used in tests
(def current-dt (t/local-date-time))
(def current-dt-zoned (t/format (t/formatter :iso-offset-date-time)
                                (t/zoned-date-time)))
(def date-fmt (t/formatter :iso-local-date))

(defn clean-test-database
  "Cleans the test database before and after running tests. Also inserts one
  observation before running tests."
  [test-fn]
  (jdbc/execute! test-ds (sql/format {:delete-from :observations}))
  (js/insert! test-ds
              :observations
              {:recorded (t/minus current-dt (t/days 4))
               :brightness 5
               :temperature 20})
  (test-fn)
  (jdbc/execute! test-ds (sql/format {:delete-from :observations}))
  (jdbc/execute! test-ds (sql/format {:delete-from :yardcam_image})))

;; Fixture run at the start and end of tests
(use-fixtures :once clean-test-database)

(defn get-image-name
  "Returns a valid yardcam or Testbed image name using the current datetime
  or the current datetime minus an optional time unit."
  ([image-type]
   (get-image-name image-type nil))
  ([image-type minus-time]
   (str (if (= image-type "yardcam") "yc-" "testbed-")
        (s/replace (if minus-time
                     (t/format (t/formatter "Y-MM-dd HH:mmxxx")
                               (t/minus (t/offset-date-time)
                                        minus-time))
                     (t/format (t/formatter "Y-MM-dd HH:mmxxx")
                               (t/offset-date-time)))
                   " " "T") ".jpg")))

(deftest insert-observations
  (testing "Full observation insert"
    (let [observation {:timestamp current-dt-zoned
                       :insideLight 0
                       :insideTemperature 20
                       :beacons [{:rssi -68
                                  :mac "7C:EC:79:3F:BE:97"}]}
          weather-data {:time (t/sql-timestamp current-dt)
                        :temperature 20
                        :cloudiness 2
                        :wind-speed 5.0}]
      (is (true? (insert-observation test-ds
                                     (merge observation
                                            {:outsideTemperature 5
                                             :weather-data weather-data}))))
      (is (true? (insert-observation test-ds
                                     (merge observation
                                            {:outsideTemperature nil
                                             :weather-data nil}))))
      (is (false? (insert-observation test-ds {})))
      (with-redefs [insert-wd (fn [_ _ _] -1)]
        (let [obs-count (:count (jdbc/execute-one! test-ds
                                                   (sql/format
                                                    {:select [:%count.id]
                                                     :from :observations})))]
          (is (false? (insert-observation test-ds
                                          (merge observation
                                                 {:outsideTemperature 5
                                                  :weather-data
                                                  weather-data}))))
          (is (= obs-count
                 (:count (jdbc/execute-one! test-ds
                                            (sql/format
                                             {:select [:%count.id]
                                              :from :observations})))))))
      (with-redefs [insert-beacons (fn [_ _ _] '(-1))]
        (is (false? (insert-observation test-ds
                                        (merge observation
                                               {:outsideTemperature nil
                                                :weather-data nil})))))
      (with-redefs [insert-plain-observation
                    (fn [_ _ _ _] (throw (PSQLException.
                                          "Test exception")))]
        (is (false? (insert-observation test-ds observation)))))))

(deftest n-days-observations
  (testing "Selecting observations from N days"
    (is (= {:brightness 0
            :temperature 20.0
            :cloudiness 2
            :wind-speed 5.0
            :fmi-temperature 20.0
            :o-temperature 5.0
            :name "7C:EC:79:3F:BE:97"
            :rssi -68
            :tb-image-name nil
            :temp-delta -15.0
            :yc-image-name (get-image-name "yardcam")}
           (dissoc (nth (get-obs-days test-ds 3) 1) :recorded)))))

(deftest obs-interval-select
  (testing "Select observations between one or two dates"
    (is (= 4 (count (get-obs-interval
                     test-ds
                     {:start nil
                      :end nil}))))
    (is (= 3 (count (get-obs-interval
                     test-ds
                     {:start (t/format date-fmt
                                       (t/minus (t/local-date)
                                                (t/days 2)))
                      :end nil}))))
    (is (= 1 (count (get-obs-interval
                     test-ds
                     {:start nil
                      :end (t/format date-fmt
                                     (t/minus (t/local-date)
                                              (t/days 2)))}))))
    (is (= 4 (count (get-obs-interval
                     test-ds
                     {:start (t/format date-fmt
                                       (t/minus (t/local-date)
                                                (t/days 6)))
                      :end (t/format date-fmt (t/local-date-time))}))))
    (is (zero? (count (get-obs-interval
                       test-ds
                       {:start (t/format date-fmt
                                         (t/minus (t/local-date)
                                                  (t/days 11)))
                        :end (t/format date-fmt
                                       (t/minus (t/local-date)
                                                (t/days 10)))}))))
    (is (zero? (count (get-obs-interval
                       test-ds
                       {:start "foobar"
                        :end nil}))))
    (is (zero? (count (get-obs-interval
                       test-ds
                       {:start nil
                        :end "foobar"}))))
    (is (zero? (count (get-obs-interval
                       test-ds
                       {:start "bar"
                        :end "foo"}))))))

(deftest get-observations-tests
  (testing "Observation querying with arbitrary WHERE clause and LIMIT"
    (is (= 2 (count (get-observations test-ds
                                      :where [:= :o.temperature 20]))))
    (is (= 1 (count (get-observations test-ds
                                      :limit 1))))
    (is (zero? (count (get-observations test-ds
                                        :limit 0))))))

(deftest start-and-end-date-query
  (testing "Selecting start and end dates of all observations"
    (is (= {:start (t/format date-fmt (t/minus current-dt
                                               (t/days 4)))
            :end (t/format date-fmt current-dt)}
           (get-obs-date-interval test-ds)))
    (with-redefs [jdbc/execute-one! (fn [con query] nil)]
      (is (= {:start ""
              :end ""}
             (get-obs-date-interval test-ds))))
    (with-redefs [jdbc/execute-one!
                  (fn [con query]
                    (throw (PSQLException.
                            "Test exception"
                            (PSQLState/COMMUNICATION_ERROR))))]
      (is (= {:error :db-error}
             (get-obs-date-interval test-ds))))))

(deftest date-validation
  (testing "Tests for date validation"
    (is (true? (validate-date nil)))
    (is (false? (validate-date "foobar")))
    (is (false? (validate-date "202-08-25")))
    (is (true? (validate-date "2020-9-27")))
    (is (true? (validate-date "2020-09-27")))))

(deftest date-to-datetime
  (testing "Testing date to datetime conversion"
    (is (= "2020-09-27T00:00"
           (str (make-local-dt "2020-09-27" "start"))))
    (is (= "2020-09-27T23:59:59"
           (str (make-local-dt "2020-09-27" "end"))))))

(deftest weather-obs-interval-select
  (testing "Select weather observations between one or two dates"
    (is (= 1 (count (get-weather-obs-interval test-ds
                                              {:start nil
                                               :end nil}))))
    (is (= 1 (count (get-weather-obs-interval
                     test-ds
                     {:start (t/format date-fmt
                                       (t/minus current-dt
                                                (t/days 1)))
                      :end nil}))))
    (is (zero? (count (get-weather-obs-interval
                       test-ds
                       {:start nil
                        :end (t/format date-fmt
                                       (t/minus current-dt
                                                (t/days 2)))}))))
    (is (zero? (count (get-weather-obs-interval
                       test-ds
                       {:start (t/format date-fmt
                                         (t/minus current-dt
                                                  (t/days 5)))
                        :end (t/format date-fmt
                                       (t/minus current-dt
                                                (t/days 3)))}))))))

(deftest weather-days-observations
  (testing "Selecting weather observations from N days"
    (is (= {:cloudiness 2
            :wind-speed 5.0
            :fmi-temperature 20.0
            :tb-image-name nil}
           (dissoc (first (get-weather-obs-days test-ds 1))
                   :time)))))

(deftest weather-observation-select
  (testing "Selecting weather observations with an arbitrary WHERE clause"
    (is (zero? (count (get-weather-observations test-ds
                                                :where [:= 1 0]))))))

(deftest yc-image-name-storage
  (testing "Storing of the latest Yardcam image name"
    (jdbc/execute! test-ds (sql/format {:delete-from :yardcam_image}))
    (js/insert! test-ds
                :yardcam_image
                {:image_name (get-image-name "yardcam")})
    (js/insert! test-ds
                :yardcam_image
                {:image_name (get-image-name "yardcam" (t/minutes 5))})
    (is (= 2
           (:count (jdbc/execute-one! test-ds
                                      (sql/format {:select [:%count.image_id]
                                                   :from :yardcam_image})))))
    (is (true? (insert-yc-image-name test-ds
                                     (get-image-name "yardcam"))))
    (is (= 1
           (:count (jdbc/execute-one! test-ds
                                      (sql/format {:select [:%count.image_id]
                                                   :from :yardcam_image})))))))

(deftest yc-image-name-query
  (testing "Querying of the yardcam image name"
    (jdbc/execute! test-ds (sql/format {:delete-from :yardcam_image}))
    (is (nil? (get-yc-image test-ds)))
    (js/insert! test-ds
                :yardcam_image
                {:image_name (get-image-name "yardcam" (t/hours 4))})
    (is (nil? (get-yc-image test-ds)))
    (let [valid-name (get-image-name "yardcam")]
      (jdbc/execute! test-ds (sql/format {:delete-from :yardcam_image}))
      (js/insert! test-ds
                  :yardcam_image
                  {:image_name valid-name})
      (is (= valid-name (get-yc-image test-ds))))))

(deftest last-observation-id
  (testing "Query of last observation's ID"
    (let [last-id (:max (jdbc/execute-one! test-ds
                                           (sql/format {:select [:%max.id]
                                                        :from :observations})))]
      (is (= last-id (get-last-obs-id test-ds))))))

(deftest testbed-image-storage
  (testing "Storage of a Testbed image"
    (is (true? (insert-tb-image-name test-ds
                                     (get-last-obs-id test-ds)
                                     "testbed-2017-04-21T19:16+0300.png")))))

(deftest wd-insert
  (testing "Insert of FMI weather data"
    (let [obs-id (:min (jdbc/execute-one! test-ds
                                          (sql/format {:select [:%min.id]
                                                       :from :observations})))
          weather-data {:time (t/local-date-time)
                        :temperature 20
                        :cloudiness 2
                        :wind-speed 5.0}]
      (is (pos? (insert-wd test-ds
                           obs-id
                           weather-data))))))

(deftest ruuvitag-observation-insert
  (testing "Insert of RuuviTag observation"
    (let [ruuvitag-obs {:location "indoor"
                        :temperature 21
                        :pressure 1100
                        :humidity 25
                        :battery_voltage 2.921}]
      (is (pos? (insert-ruuvitag-observation test-ds
                                             ruuvitag-obs)))
      (is (pos? (insert-ruuvitag-observation
                 test-ds
                 (assoc ruuvitag-obs
                        :recorded
                        (t/zoned-date-time "2019-01-25T20:45:18+02:00")))))
      (is (pos? (insert-ruuvitag-observation
                 test-ds
                 (assoc ruuvitag-obs
                        :pressure
                        nil))))
      (= -1 (insert-ruuvitag-observation test-ds
                                         (dissoc ruuvitag-obs :temperature))))))

(deftest beacon-insert
  (testing "Insert of beacon(s)"
    (let [obs-id (:min (jdbc/execute-one! test-ds
                                          (sql/format {:select [:%min.id]
                                                       :from :observations})))
          beacon {:rssi -68
                  :mac "7C:EC:79:3F:BE:97"}]
      (is (pos? (first (insert-beacons test-ds
                                       obs-id
                                       {:beacons [beacon]})))))))

(deftest plain-observation-insert
  (testing "Insert of a row into the observations table"
    (is (pos? (insert-plain-observation test-ds
                                        {:timestamp current-dt-zoned
                                         :insideLight 0
                                         :insideTemperature 20
                                         :outsideTemperature 5
                                         :image-name (get-image-name
                                                      "yardcam")})))))

(deftest db-connection-test
  (testing "Connection to the DB"
    (is (true? (test-db-connection test-ds)))
    (with-redefs [jdbc/execute-one!
                  (fn [con query]
                    (throw (PSQLException.
                            "Test exception"
                            (PSQLState/COMMUNICATION_ERROR))))]
      (is (false? (test-db-connection test-ds))))))

(deftest yc-image-age-check-test
  (testing "Yardcam image date checking"
    (is (false? (image-age-check "yardcam"
                                 (get-image-name "yardcam" (t/minutes 10))
                                 (t/zoned-date-time)
                                 9)))
    (is (true? (image-age-check "yardcam"
                                (get-image-name "yardcam")
                                (t/zoned-date-time)
                                1)))
    (is (true? (image-age-check "yardcam"
                                (get-image-name "yardcam")
                                (t/zoned-date-time)
                                5)))
    (is (true? (image-age-check "yardcam"
                                (get-image-name "yardcam")
                                (t/minus (t/zoned-date-time)
                                         (t/minutes 10))
                                9)))))

(deftest testbed-image-age-check-test
  (testing "Testbed image date checking"
    (is (false? (image-age-check "testbed"
                                 (get-image-name "testbed" (t/minutes 10))
                                 (t/zoned-date-time)
                                 9)))
    (is (true? (image-age-check "testbed"
                                (get-image-name "testbed")
                                (t/zoned-date-time)
                                1)))
    (is (true? (image-age-check "testbed"
                                (get-image-name "testbed")
                                (t/zoned-date-time)
                                5)))
    (is (true? (image-age-check "testbed"
                                (get-image-name "testbed")
                                (t/minus (t/zoned-date-time)
                                         (t/minutes 10))
                                9)))))

(deftest get-ruuvitag-obs-test
  (testing "RuuviTag observation fetching"
    (jdbc/execute! test-ds (sql/format {:delete-from :ruuvitag_observations}))
    (js/insert! test-ds
                :ruuvitag_observations
                {:location "indoor"
                 :temperature 22.0
                 :pressure 1024.0
                 :humidity 45.0
                 :battery_voltage 2.910})
    (js/insert! test-ds
                :ruuvitag_observations
                {:location "indoor"
                 :temperature 21.0
                 :pressure 1023.0
                 :humidity 45.0
                 :battery_voltage 2.890})
    (js/insert! test-ds
                :ruuvitag_observations
                {:location "balcony"
                 :temperature 15.0
                 :pressure 1024.0
                 :humidity 30.0
                 :battery_voltage 2.805})
    (is (= '{:location "balcony"
             :temperature 15.0
             :humidity 30.0}
           (dissoc (first (get-ruuvitag-obs test-ds
                                            (t/minus (t/local-date-time)
                                                     (t/minutes 5))
                                            (t/local-date-time)
                                            ["balcony"]))
                   :recorded)))
    (is (= 2 (count (get-ruuvitag-obs test-ds
                                      (t/minus (t/local-date-time)
                                               (t/minutes 5))
                                      (t/local-date-time)
                                      ["indoor"]))))))

(deftest get-tz-offset-test
  (testing "Timezone offset calculation"
    (let [in-daylight? (.inDaylightTime (TimeZone/getTimeZone
                                         "Europe/Helsinki")
                                        (new Date))]
      (is (= (if in-daylight? 3 2) (get-tz-offset "Europe/Helsinki")))
      (is (zero? (get-tz-offset "UTC"))))))

(deftest convert-to-epoch-ms-test
  (testing "Unix epoch time calculation"
    (let [tz-offset (get-tz-offset (get-conf-value :display-timezone
                                                   :use-sample true))]
      (is (= 1620734400000
             (convert-to-epoch-ms tz-offset
                                  (t/to-sql-timestamp
                                   (t/local-date-time 2021 5 11 12 0)))))
      (is (= 1609593180000
             (convert-to-epoch-ms tz-offset
                                  (t/to-sql-timestamp
                                   (t/local-date-time 2021 1 2 13 13))))))))
