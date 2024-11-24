(ns env-logger.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [java-time.api :as t]
            [next.jdbc :as jdbc]
            [next.jdbc [result-set :as rs] [sql :as js]]
            [env-logger.db
             :refer
             [db-conf
              convert->epoch-ms
              -convert-time->iso8601-str
              get-db-password
              get-elec-data-day
              get-elec-data-hour
              get-elec-consumption-interval-start
              get-elec-price-interval-end
              get-last-obs-id
              get-latest-elec-consumption-record-time
              get-midnight-dt
              get-month-avg-elec-price
              get-month-elec-consumption
              get-obs-date-interval
              get-obs-days
              get-obs-interval
              get-observations
              get-ruuvitag-obs
              get-tz-offset
              get-weather-obs-days
              get-weather-obs-interval
              get-weather-observations
              image-age-check
              insert-beacon
              insert-elec-consumption-data
              insert-observation
              insert-plain-observation
              insert-ruuvitag-observations
              insert-tb-image-name
              insert-wd
              make-local-dt
              add-tz-offset-to-dt
              test-db-connection
              validate-date]])
  (:import (org.postgresql.util PSQLException
                                PSQLState)
           (java.time LocalDateTime
                      ZonedDateTime)
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
      db-password (get-db-password)]
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
(def date-fmt :iso-local-date)

(defn clean-test-database
  "Cleans the test database before and after running tests. Also inserts one
  observation before running tests."
  [test-fn]
  (jdbc/execute! test-ds (sql/format {:delete-from :observations}))
  (jdbc/execute! test-ds (sql/format {:delete-from :electricity_price}))
  (jdbc/execute! test-ds (sql/format {:delete-from :electricity_consumption}))
  (js/insert! test-ds
              :observations
              {:recorded (t/minus current-dt (t/days 4))
               :brightness 5})
  (test-fn)
  (jdbc/execute! test-ds (sql/format {:delete-from :beacons}))
  (jdbc/execute! test-ds (sql/format {:delete-from :electricity_price}))
  (jdbc/execute! test-ds (sql/format {:delete-from :electricity_consumption}))
  (jdbc/execute! test-ds (sql/format {:delete-from :observations}))
  (jdbc/execute! test-ds (sql/format {:delete-from :ruuvitag_observations}))
  (jdbc/execute! test-ds (sql/format {:delete-from :weather_data})))

;; Fixture run at the start and end of tests
(use-fixtures :once clean-test-database)

(defn get-image-name
  "Returns a valid Testbed image name using the current datetime
  or the current datetime minus an optional time unit."
  ([minus-time]
   (str "testbed-"
        (str/replace (if minus-time
                       (t/format (t/formatter "Y-MM-dd HH:mmxxx")
                                 (t/minus (t/offset-date-time)
                                          minus-time))
                       (t/format (t/formatter "Y-MM-dd HH:mmxxx")
                                 (t/offset-date-time)))
                     " " "T") ".jpg")))

(deftest insert-observations
  (testing "Full observation insert"
    (let [observation {:timestamp (t/zoned-date-time)
                       :insideLight 0
                       :beacon {:mac "7C:EC:79:3F:BE:97"
                                :rssi -68
                                :battery_level nil}}
          weather-data {:time (t/sql-timestamp current-dt)
                        :temperature 20
                        :cloudiness 2
                        :wind-speed 5.0}]
      (is (true? (insert-observation test-ds
                                     (merge observation
                                            {:timestamp (t/minus (:timestamp observation)
                                                                 (t/seconds 5))
                                             :outsideTemperature 5
                                             :weather-data weather-data}))))
      (is (true? (insert-observation test-ds
                                     (merge observation
                                            {:timestamp (t/minus (:timestamp observation)
                                                                 (t/seconds 10))
                                             :outsideTemperature nil
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
      (with-redefs [insert-beacon (fn [_ _ _] '-1)]
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
            :cloudiness 2
            :wind-speed 5.0
            :fmi-temperature 20.0
            :o-temperature 5.0
            :beacon-name "7C:EC:79:3F:BE:97"
            :beacon-rssi -68
            :beacon-battery nil
            :tb-image-name nil}
           (-> (nth (get-obs-days test-ds 3) 1)
               (dissoc :recorded)
               (dissoc :weather-recorded))))))

(deftest obs-interval-select
  (testing "Select observations between one or two dates"
    (is (= 5 (count (get-obs-interval
                     test-ds
                     {:start nil
                      :end nil}))))
    (is (= 3 (count (get-obs-interval
                     test-ds
                     {:start (t/format date-fmt
                                       (t/minus (t/local-date)
                                                (t/days 2)))
                      :end nil}))))
    (is (= 2 (count (get-obs-interval
                     test-ds
                     {:start nil
                      :end (t/format date-fmt
                                     (t/minus (t/local-date)
                                              (t/days 2)))}))))
    (is (= 5 (count (get-obs-interval
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
    (with-redefs [jdbc/execute-one! (fn [_ _] {:start nil
                                               :end nil})]
      (is (= {:start nil
              :end nil}
             (get-obs-date-interval test-ds))))
    (with-redefs [jdbc/execute-one!
                  (fn [_ _]
                    (throw (PSQLException.
                            "Test exception"
                            PSQLState/COMMUNICATION_ERROR)))]
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

(deftest test-add-tz-offset-to-dt
  (testing "TZ offset adding"
    (let [orig-dt (t/local-date-time)]
      (is (= orig-dt (add-tz-offset-to-dt orig-dt))))))

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
                           weather-data)))
      (is (pos? (insert-wd test-ds
                           obs-id
                           (assoc weather-data :cloudiness nil)))))))

(deftest ruuvitag-observation-insert
  (testing "Insert of RuuviTag observation"
    (let [ruuvitag-obs {:name "indoor"
                        :temperature 21
                        :pressure 1100
                        :humidity 25
                        :battery_voltage 2.921
                        :rssi -72}]
      (is (true? (insert-ruuvitag-observations test-ds
                                               nil
                                               [ruuvitag-obs])))
      (is (true? (insert-ruuvitag-observations
                  test-ds
                  (t/zoned-date-time "2019-01-25T20:45:18+02:00")
                  [ruuvitag-obs])))
      (is (true? (insert-ruuvitag-observations
                  test-ds
                  nil
                  [(assoc ruuvitag-obs
                          :pressure
                          nil)
                   (assoc ruuvitag-obs
                          :temperature 25)])))
      (is (false? (insert-ruuvitag-observations test-ds
                                                nil
                                                [(dissoc ruuvitag-obs
                                                         :temperature)
                                                 (assoc ruuvitag-obs
                                                        :temperature 25)]))))))

(deftest beacon-insert
  (testing "Insert of a beacon"
    (let [obs-id (:min (jdbc/execute-one! test-ds
                                          (sql/format {:select [:%min.id]
                                                       :from :observations})))]
      (is (pos? (insert-beacon test-ds
                               obs-id
                               {:beacon {:rssi -68
                                         :mac nil}})))
      (is (pos? (insert-beacon test-ds
                               obs-id
                               {:beacon {:rssi 13.3
                                         :mac "7C:EC:79:3F:BE:97"}})))
      (is (pos? (insert-beacon test-ds
                               obs-id
                               {:beacon {:rssi -68
                                         :mac "7C:EC:79:3F:BE:97"
                                         :battery "foo"}})))
      (let [beacons-id (insert-beacon test-ds
                                      obs-id
                                      {:beacon {:rssi -68
                                                :mac "7C:EC:79:3F:BE:97"
                                                :battery nil}})]
        (is (pos? beacons-id))
        (let [result (jdbc/execute-one! test-ds
                                        (sql/format {:select [:mac_address
                                                              :rssi
                                                              :battery_level]
                                                     :from :beacons
                                                     :where [:= :id
                                                             beacons-id]}))]
          (is (= "7C:EC:79:3F:BE:97" (:mac_address result)))
          (is (= -68 (:rssi result)))
          (is (nil? (:battery_level result))))
        ;; Remove beacon to not break other tests
        (jdbc/execute! test-ds
                       (sql/format {:delete-from :beacons
                                    :where [:= :id beacons-id]})))
      (let [beacons-id (insert-beacon test-ds
                                      obs-id
                                      {:beacon {:rssi -72
                                                :mac "7C:EC:79:3F:BE:97"
                                                :battery 95}})]
        (is (pos? beacons-id))
        (let [result (jdbc/execute-one! test-ds
                                        (sql/format {:select [:mac_address
                                                              :rssi
                                                              :battery_level]
                                                     :from :beacons
                                                     :where [:= :id
                                                             beacons-id]}))]
          (is (= -72 (:rssi result)))
          (is (= 95 (:battery_level result))))
        ;; Remove beacon to not break other tests
        (jdbc/execute! test-ds
                       (sql/format {:delete-from :beacons
                                    :where [:= :id beacons-id]}))))))

(deftest plain-observation-insert
  (testing "Insert of a row into the observations table"
    (is (pos? (insert-plain-observation test-ds
                                        {:timestamp (t/zoned-date-time)
                                         :insideLight 0
                                         :outsideTemperature 5})))))

(deftest db-connection-test
  (testing "Connection to the DB"
    (is (true? (test-db-connection test-ds)))
    (with-redefs [jdbc/execute-one!
                  (fn [_ _]
                    (throw (PSQLException.
                            "Test exception"
                            PSQLState/COMMUNICATION_ERROR)))]
      (is (false? (test-db-connection test-ds))))))

(deftest testbed-image-age-check-test
  (testing "Testbed image date checking"
    (is (false? (image-age-check (get-image-name (t/minutes 10))
                                 (t/zoned-date-time)
                                 9)))
    (is (true? (image-age-check (get-image-name nil)
                                (t/zoned-date-time)
                                1)))
    (is (true? (image-age-check (get-image-name nil)
                                (t/zoned-date-time)
                                5)))
    (is (true? (image-age-check (get-image-name nil)
                                (t/minus (t/zoned-date-time)
                                         (t/minutes 10))
                                9)))))

(deftest get-ruuvitag-obs-test
  (testing "RuuviTag observation fetching"
    (jdbc/execute! test-ds (sql/format {:delete-from :ruuvitag_observations}))
    (js/insert! test-ds
                :ruuvitag_observations
                {:name "indoor"
                 :temperature 22.0
                 :pressure 1024.0
                 :humidity 45.0
                 :battery_voltage 2.910
                 :rssi -72})
    (js/insert! test-ds
                :ruuvitag_observations
                {:name "indoor"
                 :temperature 21.0
                 :pressure 1023.0
                 :humidity 45.0
                 :battery_voltage 2.890
                 :rssi -66})
    (js/insert! test-ds
                :ruuvitag_observations
                {:name "balcony"
                 :temperature 15.0
                 :pressure 1024.0
                 :humidity 30.0
                 :battery_voltage 2.805
                 :rssi -75})
    (is (= '{:name "balcony"
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

(deftest get-elec-data-test
  (testing "Electricity data fetching"
    (jdbc/execute! test-ds (sql/format {:delete-from :electricity_price}))
    ;; Tests with no data for both day and hour
    (is (nil? (get-elec-data-hour test-ds
                                  (make-local-dt "2022-10-08" "start")
                                  (make-local-dt "2022-10-08" "end"))))
    (is (nil? (first (get-elec-data-day test-ds "2022-10-08" "2022-10-08"))))
    (is (nil? (first (get-elec-data-day test-ds
                                        "2022-10-08"
                                        nil))))
    (js/insert! test-ds
                :electricity_price
                {:start_time (t/sql-timestamp (t/zoned-date-time 2022 10 8
                                                                 18 0 0))
                 :price 10.0})
    (js/insert! test-ds
                :electricity_price
                {:start_time (t/sql-timestamp (t/zoned-date-time 2022 10 7
                                                                 22 0 0))
                 :price 4.0})
    (js/insert! test-ds
                :electricity_consumption
                {:time (t/sql-timestamp (t/zoned-date-time 2022 10 7
                                                           22 0 0))
                 :consumption 1.0})
    ;; Hour data tests
    (let [res (get-elec-data-hour test-ds
                                  (make-local-dt "2022-10-08" "start")
                                  (make-local-dt "2022-10-08" "end"))]
      (is (= 1 (count res)))
      (is (= {:price 10.0 :consumption nil}
             (dissoc (first res) :start-time))))
    (let [res (get-elec-data-hour test-ds
                                  (make-local-dt "2022-10-07" "start")
                                  (make-local-dt "2022-10-08" "end"))]
      (is (= 2 (count res)))
      (is (= {:price 4.0 :consumption 1.0}
             (dissoc (first res) :start-time))))
    (is (= 2 (count (get-elec-data-hour test-ds
                                        (make-local-dt "2022-10-07" "start")
                                        nil))))
    (is (nil? (get-elec-data-hour test-ds
                                  (make-local-dt "2022-10-10" "start")
                                  (make-local-dt "2022-10-10" "end"))))
    (with-redefs [jdbc/execute! (fn [_ _ _]
                                  (throw (PSQLException.
                                          "Test exception"
                                          PSQLState/COMMUNICATION_ERROR)))]
      (is (nil? (get-elec-data-hour test-ds
                                    (make-local-dt "2022-10-08" "start")
                                    (make-local-dt "2022-10-08" "end")))))
    ;; Day data tests
    (is (= [{:consumption 1.0 :price 4.0 :date "2022-10-07"}
            {:consumption nil :price 10.0 :date "2022-10-08"}]
           (get-elec-data-day test-ds "2022-10-07" "2022-10-08")))
    (is (= [{:consumption 1.0 :price 4.0 :date "2022-10-07"}
            {:consumption nil :price 10.0 :date "2022-10-08"}]
           (get-elec-data-day test-ds "2022-10-07" nil)))
    (is (nil? (first (get-elec-data-day test-ds "2022-10-10" "2022-10-10"))))
    (with-redefs [jdbc/execute-one! (fn [_ _ _]
                                      (throw (PSQLException.
                                              "Test exception"
                                              PSQLState/COMMUNICATION_ERROR)))]
      (is (nil? (first (get-elec-data-day test-ds "2022-10-08" nil)))))
    (jdbc/execute! test-ds (sql/format {:delete-from :electricity_consumption}))
    (jdbc/execute! test-ds (sql/format {:delete-from :electricity_price}))))

(deftest get-tz-offset-test
  (testing "Timezone offset calculation"
    (let [in-daylight? (TimeZone/.inDaylightTime
                        (TimeZone/getTimeZone "Europe/Helsinki")
                        (Date.))]
      (is (= (if in-daylight? 3 2) (get-tz-offset "Europe/Helsinki")))
      (is (zero? (get-tz-offset "UTC"))))))

(deftest get-midnight-dt-test
  (testing "Datetime at midnight calculation"
    (let [ref (t/local-date-time 2023 2 21 0 0 0)]
      (with-redefs [t/local-date-time (fn [] (LocalDateTime/of 2023 2 22
                                                               21 15 16))]
        (is (= ref (get-midnight-dt 1)))))))

(deftest convert->epoch-ms-test
  (testing "Unix epoch time calculation"
    (let [tz-offset (get-tz-offset "UTC")]
      (is (= 1620734400000
             (convert->epoch-ms tz-offset
                                (t/sql-timestamp
                                 (t/local-date-time 2021 5 11 12 0)))))
      (is (= 1609593180000
             (convert->epoch-ms tz-offset
                                (t/sql-timestamp
                                 (t/local-date-time 2021 1 2 13 13))))))))

(deftest test-iso8601-str-generation
  (testing "ZonedDateTime to ISO 8601 string conversion"
    (let [now (ZonedDateTime/now (t/zone-id "UTC"))]
      (is (= (str (first (str/split (str now) #"\.")) "Z")
             (-convert-time->iso8601-str now))))))

(deftest test-elec-consumption-data-insert
  (testing "Insert of electricity consumption data"
    (let [consumption-data [[current-dt 0.1]
                            [(t/plus current-dt (t/hours 1)) 0.12]]]
      (is (true? (insert-elec-consumption-data test-ds consumption-data)))
      (is (= (count consumption-data)
             (:count (jdbc/execute-one! test-ds
                                        (sql/format
                                         {:select [:%count.id]
                                          :from :electricity_consumption})))))
      (with-redefs [js/insert-multi! (fn [_ _ _ _ _]
                                       [{:id 1}])]
        (is (false? (insert-elec-consumption-data test-ds consumption-data))))
      (with-redefs [js/insert-multi!
                    (fn [_ _ _ _ _]
                      (throw (PSQLException.
                              "Test exception"
                              PSQLState/COMMUNICATION_ERROR)))]
        (is (false? (insert-elec-consumption-data test-ds consumption-data)))))))

(deftest test-get-latest-elec-consumption-record-time
  (testing "Fetching of latest electricity consumption time"
    (with-redefs [jdbc/execute-one!
                  (fn [_ _ _]
                    (throw (PSQLException.
                            "Test exception"
                            PSQLState/COMMUNICATION_ERROR)))]
      (is (nil? (get-latest-elec-consumption-record-time test-ds))))
    (is (nil? (get-latest-elec-consumption-record-time test-ds)))
    (let [consumption-data [[(t/local-date-time 2023 2 7 19 50) 0.1]]]
      (insert-elec-consumption-data test-ds consumption-data)
      (with-redefs [get-tz-offset (fn [_] 0)]
        (is (= "7.2.2023 19:50" (get-latest-elec-consumption-record-time test-ds))))
      (jdbc/execute! test-ds (sql/format {:delete-from :electricity_consumption})))))

(deftest test-get-elec-consumption-interval-start
  (testing "Fetching of electricity consumption interval start"
    (with-redefs [jdbc/execute-one!
                  (fn [_ _]
                    (throw (PSQLException.
                            "Test exception"
                            PSQLState/COMMUNICATION_ERROR)))]
      (is (nil? (get-elec-consumption-interval-start test-ds))))
    (is (nil? (get-elec-consumption-interval-start test-ds)))
    (let [consumption-data [[(t/local-date-time 2023 2 22 19 50) 0.1]]]
      (insert-elec-consumption-data test-ds consumption-data))
    (is (= "2023-02-22" (get-elec-consumption-interval-start test-ds)))
    (jdbc/execute! test-ds (sql/format {:delete-from :electricity_consumption}))))

(deftest test-get-elec-price-interval-end
  (testing "Fetching of electricity price interval end"
    (with-redefs [jdbc/execute-one!
                  (fn [_ _]
                    (throw (PSQLException.
                            "Test exception"
                            PSQLState/COMMUNICATION_ERROR)))]
      (is (nil? (get-elec-price-interval-end test-ds))))
    (is (nil? (get-elec-price-interval-end test-ds)))

    (js/insert! test-ds
                :electricity_price
                {:start_time (t/sql-timestamp (t/zoned-date-time 2023 9 4
                                                                 18 0 0))
                 :price 10.0})
    (js/insert! test-ds
                :electricity_price
                {:start_time (t/sql-timestamp (t/zoned-date-time 2023 9 5
                                                                 22 0 0))
                 :price 4.0})
    (is (= "2023-09-05" (get-elec-price-interval-end test-ds)))
    (jdbc/execute! test-ds (sql/format {:delete-from :electricity_price}))))

(deftest test-get-month-avg-elec-price
  (testing "Calculating the average electricity price of the current month"
    (with-redefs [jdbc/execute-one!
                  (fn [_ _ _]
                    (throw (PSQLException.
                            "Test exception"
                            PSQLState/COMMUNICATION_ERROR)))]
      (is (nil? (get-month-avg-elec-price test-ds))))
    (is (nil? (get-month-avg-elec-price test-ds)))

    (js/insert! test-ds
                :electricity_price
                {:start_time (t/sql-timestamp (t/zoned-date-time))
                 :price 10.0})
    (js/insert! test-ds
                :electricity_price
                {:start_time (t/sql-timestamp (t/minus (t/zoned-date-time)
                                                       (t/hours 1)))
                 :price 4.0})
    (is (= "7.0" (get-month-avg-elec-price test-ds)))
    ;; Check that prices before the current month are not used
    (js/insert! test-ds
                :electricity_price
                {:start_time (t/sql-timestamp (t/minus (t/zoned-date-time)
                                                       (t/days 40)))
                 :price 12.0})
    (is (= "7.0" (get-month-avg-elec-price test-ds)))
    (jdbc/execute! test-ds (sql/format {:delete-from :electricity_price}))))

(deftest test-get-month-elec-consumption
  (testing "Calculating the electricity consumption of the current month"
    (with-redefs [jdbc/execute-one!
                  (fn [_ _ _]
                    (throw (PSQLException.
                            "Test exception"
                            PSQLState/COMMUNICATION_ERROR)))]
      (is (nil? (get-month-elec-consumption test-ds))))
    (is (nil? (get-month-elec-consumption test-ds)))

    (js/insert! test-ds
                :electricity_consumption
                {:time (t/sql-timestamp (t/zoned-date-time))
                 :consumption 0.6})
    (js/insert! test-ds
                :electricity_consumption
                {:time (t/sql-timestamp (t/minus (t/zoned-date-time)
                                                 (t/hours 1)))
                 :consumption 1.1})
    (is (= "1.7" (get-month-elec-consumption test-ds)))
    ;; Check that prices before the current month are not used
    (js/insert! test-ds
                :electricity_consumption
                {:time (t/sql-timestamp (t/minus (t/zoned-date-time)
                                                 (t/days 35)))
                 :consumption 0.4})
    (is (= "1.7" (get-month-elec-consumption test-ds)))
    (jdbc/execute! test-ds (sql/format {:delete-from
                                        :electricity_consumption}))))

(deftest test-get-db-password
  (testing "Database password fetch"
    (with-redefs [db-conf (fn [_] nil)]
      (is (nil? (get-db-password))))
    (with-redefs [db-conf (fn [_] "foobar")]
      (is (= "foobar" (get-db-password))))
    ;; Reading the password from a file is not tested because of the difficulty
    ;; of setting environment variables in Clojure / Java
    ))
