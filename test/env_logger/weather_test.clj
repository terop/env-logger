(ns env-logger.weather-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.xml :refer [parse]]
            [config.core :refer [env]]
            [clj-http.fake :refer [with-fake-routes]]
            [jsonista.core :as j]
            [java-time.api :as t]
            [next.jdbc :as jdbc]
            [env-logger
             [db :refer [get-tz-offset]]
             [weather
              :refer
              [-convert-dt->tz-iso8601-str
               calculate-start-time
               extract-forecast-data
               -fetch-astronomy-data
               -update-fmi-weather-forecast
               -update-fmi-weather-data-json
               -update-fmi-weather-data-ts
               get-fmi-weather-data
               get-wd-str
               get-weather-data
               store-weather-data?
               wd-has-empty-values?]]])
  (:import java.time.ZonedDateTime
           (org.postgresql.util PSQLException
                                PSQLState)))

;; Utilities

(deftest test-iso8601-and-tz-str-formatting
  (testing "Date and time to ISO 8601 with timezone string conversion"
    (let [now (ZonedDateTime/now (t/zone-id "Europe/Helsinki"))]
      (is (= (str (first (str/split
                          (str (ZonedDateTime/.minusHours
                                now
                                (if (= "UTC" (:weather-timezone env))
                                  0
                                  (get-tz-offset "Europe/Helsinki"))))
                          #"\.")) "Z")
             (-convert-dt->tz-iso8601-str now))))))

;; FMI

(deftest test-forecast-data-extraction
  (testing "Forecast data extraction function tests"
    (is (= {:temperature 17.0
            :wind-speed 4.0
            :cloudiness 0
            :wind-direction {:long "south east", :short "SE"}
            :precipitation 1.0
            :humidity 93.0
            :time #inst "2022-06-07T16:00:00.000000000-00:00"}
           (extract-forecast-data
            (load-file "test/env_logger/wfs_forecast_data.txt"))))))

(deftest test-start-time-calculation
  (testing "Tests the start time calculation"
    (with-redefs [t/zoned-date-time (fn []
                                      (ZonedDateTime/of 2020 12 28
                                                        9 3 50 0
                                                        (t/zone-id
                                                         "Europe/Helsinki")))]
      (is (= "2020-12-28T09:00"
             (first (str/split (str (t/local-date-time (calculate-start-time)))
                               #"\.")))))
    (with-redefs [t/zoned-date-time (fn []
                                      (ZonedDateTime/of 2020 12 28
                                                        9 9 50 0
                                                        (t/zone-id
                                                         "Europe/Helsinki")))]
      (is (= "2020-12-28T09:00"
             (first (str/split (str (t/local-date-time (calculate-start-time)))
                               #"\[")))))))

(deftest test-forecast-data-update
  (testing "Tests FMI forecast data update"
    (with-redefs [parse (fn [_]
                          (load-file "test/env_logger/wfs_forecast_data.txt"))
                  t/zoned-date-time (fn []
                                      (ZonedDateTime/of 2022 6 7
                                                        19 0 0 0
                                                        (t/zone-id
                                                         "Europe/Helsinki")))]
      (let [res (-update-fmi-weather-forecast 12.34 56.78)]
        (is (nil? @res))))
    (with-redefs [parse (fn [_]
                          {:content "garbage"})]
      (let [res (-update-fmi-weather-forecast 12.34 56.78)]
        (is (nil? @res))))))

(deftest test-weather-data-ts-update
  (testing "Tests FMI weather data (time series) updating"
    (with-redefs [j/read-value (fn [_ _] [])]
      (is (nil? (-update-fmi-weather-data-ts 87874))))
    (with-redefs [j/read-value (fn [_ _]
                                 [{:cloudiness 8.0
                                   :tz "Europe/Helsinki"
                                   :time "20241110T102000"
                                   :winddirection 254.0
                                   :windspeed 1.1
                                   :temperature 1.4
                                   :humidity 90}])]
      (let [wd (-update-fmi-weather-data-ts 87874)]
        (is (= {:time #inst "2024-11-10T08:20:00.000000000-00:00",
                :temperature 1.4
                :cloudiness 8
                :wind-speed 1.1
                :wind-direction {:short "W"
                                 :long "west"}
                :humidity 90}
               (get wd (first (keys wd)))))))))

(deftest test-weather-data-json-update
  (testing "Tests FMI weather data (JSON) updating"
    (with-redefs [j/read-value (fn [_ _]
                                 {:observations []})]
      (is (nil? (-update-fmi-weather-data-json 87874))))
    (with-redefs [j/read-value (fn [_ _]
                                 {:observations nil})]
      (is (nil? (-update-fmi-weather-data-json 87874))))
    (with-redefs [j/read-value (fn [_ _]
                                 {:observations [{:localtime "20241110T102000"
                                                  :t2m 1.4
                                                  :TotalCloudCover 8
                                                  :WindSpeedMS 1.1
                                                  :WindDirection 254
                                                  :Humidity 90}]})]
      (let [wd (-update-fmi-weather-data-json 87874)]
        (is (= {:time #inst "2024-11-10T08:20:00.000000000-00:00",
                :temperature 1.4
                :cloudiness 8
                :wind-speed 1.1
                :wind-direction {:short "W"
                                 :long "west"}
                :humidity 90}
               (get wd (first (keys wd)))))))))

(deftest fmi-weather-data-fetch
  (testing "Tests FMI weather data fetch"
    ;; Dummy test case, should be improved in the future
    (is (nil? (get-fmi-weather-data)))))

(deftest test-store-weather-data
  (testing "Tests if FMI weather data needs to be stored"
    (is (false? (store-weather-data? {} nil)))
    (with-redefs [jdbc/execute-one!
                  (fn [_ _ _]
                    {:time (t/sql-timestamp
                            (t/local-date-time 2022 10 1 11 20 0))})]
      (is (false? (store-weather-data?
                   {}
                   (t/sql-timestamp (t/local-date-time 2022 10 1 11 20 0)))))
      (is (false? (store-weather-data?
                   {}
                   (t/sql-timestamp (t/local-date-time 2022 10 1 11 15 0)))))
      (is (true? (store-weather-data?
                  {}
                  (t/sql-timestamp (t/local-date-time 2022 10 1 11 21 0))))))
    (with-redefs [jdbc/execute-one!
                  (fn [_ _ _] {:time nil})]
      (is (true? (store-weather-data?
                  {}
                  (t/sql-timestamp (t/local-date-time 2022 10 1 11 20 0))))))
    (with-redefs [jdbc/execute-one!
                  (fn [_ _ _] (throw (PSQLException.
                                      "Test exception"
                                      PSQLState/COMMUNICATION_ERROR)))]
      (is (false? (store-weather-data? {} (t/sql-timestamp)))))))

(deftest test-get-wd-str
  (testing "Test wind direction to string conversion"
    (is (= {:short "invalid"
            :long "invalid"}
           (get-wd-str nil)))
    (is (= {:short "N"
            :long "north"}
           (get-wd-str 0)))
    (is (= {:short "N"
            :long "north"}
           (get-wd-str 21)))
    (is (= {:short "NE"
            :long "north east"}
           (get-wd-str 46)))
    (is (= {:short "E"
            :long "east"}
           (get-wd-str 91)))
    (is (= {:short "SE"
            :long "south east"}
           (get-wd-str 125)))
    (is (= {:short "S"
            :long "south"}
           (get-wd-str 175)))
    (is (= {:short "SW"
            :long "south west"}
           (get-wd-str 225)))
    (is (= {:short "W"
            :long "west"}
           (get-wd-str 275)))
    (is (= {:short "NW"
            :long "north west"}
           (get-wd-str 315)))
    (is (= {:short "N"
            :long "north"}
           (get-wd-str 348)))
    (is (= {:short "N"
            :long "north"}
           (get-wd-str 360)))))

(deftest test-wd-has-empty-values?
  (testing "Test weather data nil value detection"
    (let [observation {:cloudiness 8,
                       :wind-speed 7.6,
                       :temperature -10.2}]
      (is (false? (wd-has-empty-values? observation)))
      (is (true? (wd-has-empty-values? (assoc observation :cloudiness nil))))
      (is (true? (wd-has-empty-values? (assoc observation :temperature nil))))
      (is (true? (wd-has-empty-values? (assoc observation :wind-speed nil)))))))

;; Astronomy data

(def ipgeol-api-resp (j/write-value-as-string
                      {:date "2024-11-04"
                       :sunrise "07:52"
                       :sunset "16:15"}))

(deftest test-fetch-astronomy-data
  (testing "Tests astronomy data fetching"
    (with-fake-routes {#"https://api.ipgeolocation.io/astronomy(.+)"
                       (fn [_] {:status 403})}
      (is (nil? (-fetch-astronomy-data "foo" 456 789))))
    (with-fake-routes {#"https://api.ipgeolocation.io/astronomy(.+)"
                       (fn [_] {:status 200
                                :body ipgeol-api-resp})}
      (is (= {"2024-11-04" {:sunrise "07:52", :sunset "16:15"}}
             (-fetch-astronomy-data "foo" 456 789))))))

;; General

(deftest test-get-weather-data
  (testing "Tests FMI and astronomy data query"
    (let [weather-data (get-weather-data)]
      (is (nil? (:ast weather-data)))
      (is (= {:time #inst "2022-06-07T16:00:00.000000000-00:00"
              :temperature 17.0
              :wind-speed 4.0
              :cloudiness 0
              :wind-direction {:short "SE"
                               :long "south east"}
              :precipitation 1.0
              :humidity 93.0}
             (:forecast (:fmi weather-data))))
      (is (empty? (:not-found weather-data))))))
