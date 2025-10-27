(ns env-logger.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [tupelo.core :refer [rel=]]
            [clojure.string :as str]
            [java-time.api :as jt]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as js]
            [env-logger.db
             :refer
             [db-conf
              convert->epoch-ms
              -convert-time->iso8601-str
              get-db-password
              get-elec-data-day
              get-elec-data-hour
              get-elec-consumption-interval-start
              get-elec-fees
              get-elec-price-interval-end
              get-elec-price-minute
              get-last-obs-id
              get-latest-elec-consumption-record-time
              get-midnight-dt
              get-elec-price-minute-interval-start
              get-month-avg-elec-price
              get-month-elec-consumption
              get-obs-date-interval
              get-obs-days
              get-obs-interval
              get-observations
              get-ruuvitag-obs
              get-tz-offset
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
              validate-date
              round-number]])
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
(def current-dt (jt/local-date-time))
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
              {:recorded (jt/minus current-dt (jt/days 4))
               :inside_light 5})
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
                       (jt/format (jt/formatter "y-MM-dd HH:mmxxx")
                                  (jt/minus (jt/offset-date-time)
                                            minus-time))
                       (jt/format (jt/formatter "y-MM-dd HH:mmxxx")
                                  (jt/offset-date-time)))
                     " " "T") ".jpg")))

(deftest insert-observations
  (testing "Full observation insert"
    (let [observation {:timestamp (jt/zoned-date-time)
                       :co2 600
                       :insideLight 0
                       :outsideLight 150
                       :insideTemperature 21
                       :vocIndex 100
                       :noxIndex 1
                       :beacon {:mac "7C:EC:79:3F:BE:97"
                                :rssi -68
                                :battery_level nil}}
          weather-data {:time (jt/sql-timestamp current-dt)
                        :temperature 20
                        :cloudiness 2
                        :wind-speed 5.0}]
      (is (true? (insert-observation test-ds
                                     (merge observation
                                            {:timestamp (jt/minus (:timestamp
                                                                   observation)
                                                                  (jt/seconds 5))
                                             :outsideTemperature 5
                                             :weather-data weather-data}))))
      (is (true? (insert-observation test-ds
                                     (merge observation
                                            {:timestamp (jt/minus (:timestamp
                                                                   observation)
                                                                  (jt/seconds 10))
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
    (is (= {:inside-light 0
            :inside-temperature 21.0
            :co2 600
            :cloudiness 2
            :wind-speed 5.0
            :fmi-temperature 20.0
            :outside-temperature 5.0
            :beacon-name "7C:EC:79:3F:BE:97"
            :beacon-rssi -68
            :beacon-battery nil
            :tb-image-name nil}
           (-> (nth (get-obs-days test-ds 3) 2)
               (dissoc :recorded)
               (dissoc :weather-recorded))))))

(deftest obs-interval-select
  (testing "Select observations between one or two dates"
    (is (= 6 (count (get-obs-interval
                     test-ds
                     {:start nil
                      :end nil}))))
    (is (= 4 (count (get-obs-interval
                     test-ds
                     {:start (jt/format date-fmt
                                        (jt/minus (jt/local-date)
                                                  (jt/days 2)))
                      :end nil}))))
    (is (= 2 (count (get-obs-interval
                     test-ds
                     {:start nil
                      :end (jt/format date-fmt
                                      (jt/minus (jt/local-date)
                                                (jt/days 2)))}))))
    (is (= 6 (count (get-obs-interval
                     test-ds
                     {:start (jt/format date-fmt
                                        (jt/minus (jt/local-date)
                                                  (jt/days 6)))
                      :end (jt/format date-fmt (jt/local-date-time))}))))
    (is (zero? (count (get-obs-interval
                       test-ds
                       {:start (jt/format date-fmt
                                          (jt/minus (jt/local-date)
                                                    (jt/days 11)))
                        :end (jt/format date-fmt
                                        (jt/minus (jt/local-date)
                                                  (jt/days 10)))}))))
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
    (is (= {:start (jt/format date-fmt (jt/minus current-dt
                                                 (jt/days 4)))
            :end (jt/format date-fmt current-dt)}
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
    (let [orig-dt (jt/local-date-time)]
      (is (= orig-dt (add-tz-offset-to-dt orig-dt))))))

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
          weather-data {:time (jt/local-date-time)
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
                  (jt/zoned-date-time "2019-01-25T20:45:18+02:00")
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
                                        {:timestamp (jt/zoned-date-time)
                                         :co2 600
                                         :insideLight 0
                                         :outsideLight 100
                                         :insideTemperature 21
                                         :outsideTemperature 5
                                         :vocIndex 100
                                         :noxIndex 1})))
    (is (pos? (insert-plain-observation test-ds
                                        {:timestamp (jt/zoned-date-time)
                                         :co2 600
                                         :insideLight 0
                                         :outsideLight nil
                                         :insideTemperature 21
                                         :outsideTemperature 5
                                         :vocIndex 100
                                         :noxIndex nil})))))

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
    (is (false? (image-age-check (get-image-name (jt/minutes 10))
                                 (jt/zoned-date-time)
                                 9)))
    (is (true? (image-age-check (get-image-name nil)
                                (jt/zoned-date-time)
                                1)))
    (is (true? (image-age-check (get-image-name nil)
                                (jt/zoned-date-time)
                                5)))
    (is (true? (image-age-check (get-image-name nil)
                                (jt/minus (jt/zoned-date-time)
                                          (jt/minutes 10))
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
                                            (jt/minus (jt/local-date-time)
                                                      (jt/minutes 5))
                                            (jt/local-date-time)
                                            ["balcony"]))
                   :recorded)))
    (is (= 2 (count (get-ruuvitag-obs test-ds
                                      (jt/minus (jt/local-date-time)
                                                (jt/minutes 5))
                                      (jt/local-date-time)
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
                {:start_time (jt/sql-timestamp (jt/zoned-date-time 2022 10 8
                                                                   18 0 0))
                 :price 10.0})
    (js/insert! test-ds
                :electricity_price
                {:start_time (jt/sql-timestamp (jt/zoned-date-time 2022 10 7
                                                                   22 0 0))
                 :price 4.0})
    (js/insert! test-ds
                :electricity_consumption
                {:time (jt/sql-timestamp (jt/zoned-date-time 2022 10 7
                                                             22 0 0))
                 :consumption 1.0})
    ;; Hour data tests
    (let [res (get-elec-data-hour test-ds
                                  (make-local-dt "2022-10-08" "start")
                                  (make-local-dt "2022-10-08" "end"))
          row (first res)]
      (is (= 1 (count res)))
      (is (rel= 15.89 (:price row) :tol 0.01))
      (is (nil? (:consumption row))))
    (let [res (get-elec-data-hour test-ds
                                  (make-local-dt "2022-10-07" "start")
                                  (make-local-dt "2022-10-08" "end"))
          row (first res)]
      (is (= 2 (count res)))
      (is (rel= 9.89 (:price row) :tol 0.01))
      (is (rel= 1.0 (:consumption row) :tol 0.01)))
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
    (let [result (get-elec-data-day test-ds "2022-10-07" "2022-10-08")
          one (first result)
          two (nth result 1)]
      (is (= {:consumption 1.0 :date "2022-10-07"} (dissoc one :price)))
      (is (rel= 9.89 (:price one) :tol 0.01))
      (is (= {:consumption nil :date "2022-10-08"} (dissoc two :price)))
      (is (rel= 15.89 (:price two) :tol 0.01)))
    (let [result (get-elec-data-day test-ds "2022-10-07" nil)
          one (first result)
          two (nth result 1)]
      (is (= {:consumption 1.0 :date "2022-10-07"} (dissoc one :price)))
      (is (rel= 9.89 (:price one) :tol 0.01))
      (is (= {:consumption nil :date "2022-10-08"} (dissoc two :price)))
      (is (rel= 15.89 (:price two) :tol 0.01)))
    (is (nil? (first (get-elec-data-day test-ds "2022-10-10" "2022-10-10"))))
    (with-redefs [jdbc/execute-one! (fn [_ _ _]
                                      (throw (PSQLException.
                                              "Test exception"
                                              PSQLState/COMMUNICATION_ERROR)))]
      (is (nil? (first (get-elec-data-day test-ds "2022-10-08" nil)))))
    (jdbc/execute! test-ds (sql/format {:delete-from :electricity_consumption}))
    (jdbc/execute! test-ds (sql/format {:delete-from :electricity_price}))))

(deftest get-elec-fees-test
  (testing "Electricity fee calculation"
    (is (rel= 5.89 (get-elec-fees) :tol 0.01))))

(deftest get-tz-offset-test
  (testing "Timezone offset calculation"
    (let [in-daylight? (TimeZone/.inDaylightTime
                        (TimeZone/getTimeZone "Europe/Helsinki")
                        (Date.))]
      (is (= (if in-daylight? 3 2) (get-tz-offset "Europe/Helsinki")))
      (is (zero? (get-tz-offset "UTC"))))))

(deftest get-midnight-dt-test
  (testing "Datetime at midnight calculation"
    (let [ref (jt/local-date-time 2023 2 21 0 0 0)]
      (with-redefs [jt/local-date-time (fn [] (LocalDateTime/of 2023 2 22
                                                                21 15 16))]
        (is (= ref (get-midnight-dt 1)))))))

(deftest convert->epoch-ms-test
  (testing "Unix epoch time calculation"
    (let [tz-offset (get-tz-offset "UTC")]
      (is (= 1620734400000
             (convert->epoch-ms tz-offset
                                (jt/sql-timestamp
                                 (jt/local-date-time 2021 5 11 12 0)))))
      (is (= 1609593180000
             (convert->epoch-ms tz-offset
                                (jt/sql-timestamp
                                 (jt/local-date-time 2021 1 2 13 13))))))))

(deftest test-iso8601-str-generation
  (testing "ZonedDateTime to ISO 8601 string conversion"
    (let [now (ZonedDateTime/now (jt/zone-id "UTC"))]
      (is (= (str (first (str/split (str now) #"\.")) "Z")
             (-convert-time->iso8601-str now))))))

(deftest test-elec-consumption-data-insert
  (testing "Insert of electricity consumption data"
    (let [consumption-data [[current-dt 0.1]
                            [(jt/plus current-dt (jt/hours 1)) 0.12]]]
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
    (let [consumption-data [[(jt/local-date-time 2023 2 7 19 50) 0.1]]]
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
    (let [consumption-data [[(jt/local-date-time 2023 2 22 19 50) 0.1]]]
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
                {:start_time (jt/sql-timestamp (jt/zoned-date-time 2023 9 4
                                                                   18 0 0))
                 :price 10.0})
    (js/insert! test-ds
                :electricity_price
                {:start_time (jt/sql-timestamp (jt/zoned-date-time 2023 9 5
                                                                   22 0 0))
                 :price 4.0})
    (is (= "2023-09-05" (get-elec-price-interval-end test-ds)))
    (jdbc/execute! test-ds (sql/format {:delete-from :electricity_price}))))

(deftest test-get-minute-elec-price-interval-start
  (testing "Fetching of 15 minute electricity prices interval start"
    (with-redefs [jdbc/execute-one!
                  (fn [_ _]
                    (throw (PSQLException.
                            "Test exception"
                            PSQLState/COMMUNICATION_ERROR)))]
      (is (nil? (get-elec-price-minute-interval-start test-ds))))
    (is (nil? (get-elec-price-minute-interval-start test-ds)))

    (js/insert! test-ds
                :electricity_price_minute
                {:start_time (jt/sql-timestamp (jt/zoned-date-time 2025 10 25
                                                                   0 0 0))
                 :price 4.0})
    (is (= "2025-10-25" (get-elec-price-minute-interval-start test-ds)))
    (jdbc/execute! test-ds (sql/format {:delete-from :electricity_price_minute}))))

(deftest test-get-elec-price-minute
  (testing "Fetching of 15 minute resolution electricity prices"
    (let [start (make-local-dt "2025-10-25" "start")
          end (make-local-dt "2025-10-25" "end")]
      (with-redefs [jdbc/execute-one!
                    (fn [_ _]
                      (throw (PSQLException.
                              "Test exception"
                              PSQLState/COMMUNICATION_ERROR)))]
        (is (nil? (get-elec-price-minute test-ds start end))))
      (is (nil? (get-elec-price-minute test-ds start end)))

      (js/insert! test-ds
                  :electricity_price_minute
                  {:start_time (jt/sql-timestamp (jt/zoned-date-time 2025 10 25
                                                                     0 0 0))
                   :price 4.0})
      (is (rel= 9.89
                (:price (first (get-elec-price-minute test-ds start end)))
                :tol 0.01))
      (is (nil? (get-elec-price-minute test-ds
                                       (jt/plus start (jt/days 1))
                                       (jt/plus end (jt/days 1)))))
      (jdbc/execute! test-ds (sql/format {:delete-from :electricity_price_minute})))))

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
                {:start_time (jt/sql-timestamp (jt/zoned-date-time))
                 :price 10.0})
    (js/insert! test-ds
                :electricity_price
                {:start_time (jt/sql-timestamp (jt/minus (jt/zoned-date-time)
                                                         (jt/hours 1)))
                 :price 4.0})
    (is (rel= 12.89 (get-month-avg-elec-price test-ds)
              :tol 0.01))
    ;; Check that prices before the current month are not used
    (js/insert! test-ds
                :electricity_price
                {:start_time (jt/sql-timestamp (jt/minus (jt/zoned-date-time)
                                                         (jt/days 40)))
                 :price 12.0})
    (is (rel= 12.89 (get-month-avg-elec-price test-ds)
              :tol 0.01))
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
                {:time (jt/sql-timestamp (jt/zoned-date-time))
                 :consumption 0.6})
    (js/insert! test-ds
                :electricity_consumption
                {:time (jt/sql-timestamp (jt/minus (jt/zoned-date-time)
                                                   (jt/hours 1)))
                 :consumption 1.4})
    (is (== 2.0 (get-month-elec-consumption test-ds)))
    ;; Check that prices before the current month are not used
    (js/insert! test-ds
                :electricity_consumption
                {:time (jt/sql-timestamp (jt/minus (jt/zoned-date-time)
                                                   (jt/days 35)))
                 :consumption 0.4})
    (is (== 2.0 (get-month-elec-consumption test-ds)))
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

(deftest test-round-number
  (testing "Rounding of number"
    (is (== 12.0 (round-number 12.00003456)))
    (is (== 42.0 (round-number 42.0)))
    (is (== 100.0 (round-number 100)))))
