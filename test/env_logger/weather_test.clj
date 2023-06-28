(ns env-logger.weather-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as s]
            [clojure.xml :refer [parse]]
            [config.core :refer [env]]
            [clj-http.fake :refer [with-fake-routes]]
            [jsonista.core :as j]
            [java-time.api :as t]
            [next.jdbc :as jdbc]
            [env-logger
             [db :refer [get-tz-offset]]
             [weather :refer [-convert-to-tz-iso8601-str
                              calculate-start-time
                              extract-forecast-data
                              -update-fmi-weather-forecast
                              -update-fmi-weather-data-json
                              -update-fmi-weather-data-ts
                              -fetch-owm-data
                              get-fmi-weather-data
                              get-wd-str
                              get-weather-data
                              store-weather-data?]]])
  (:import java.time.ZonedDateTime
           (org.postgresql.util PSQLException
                                PSQLState)))

;; Utilities

(deftest test-iso8601-and-tz-str-formatting
  (testing "Date and time to ISO 8601 with timezone string conversion"
    (let [now (ZonedDateTime/now (t/zone-id "Europe/Helsinki"))]
      (is (= (str (first (s/split
                          (str (.minusHours now
                                            (if (= "UTC" (:weather-timezone
                                                          env))
                                              0
                                              (get-tz-offset
                                               "Europe/Helsinki"))))
                          #"\.")) "Z")
             (-convert-to-tz-iso8601-str now))))))

;; FMI

(deftest test-forecast-data-extraction
  (testing "Forecast data extraction function tests"
    (is (= {:temperature 17.0
            :wind-speed 4.0
            :cloudiness 0
            :wind-direction {:long "south east", :short "SE"}
            :precipitation 1.0
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
             (first (s/split (str (t/local-date-time (calculate-start-time)))
                             #"\.")))))
    (with-redefs [t/zoned-date-time (fn []
                                      (ZonedDateTime/of 2020 12 28
                                                        9 9 50 0
                                                        (t/zone-id
                                                         "Europe/Helsinki")))]
      (is (= "2020-12-28T09:00"
             (first (s/split (str (t/local-date-time (calculate-start-time)))
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
                                 [{:cloudiness 5.0
                                   :tz "Europe/Helsinki"
                                   :time "20221221T215000"
                                   :winddirection 68.0,
                                   :windspeed 2.6
                                   :temperature -7.6}])]
      (is (not (nil? (-update-fmi-weather-data-ts 87874)))))))

(deftest test-weather-data-json-update
  (testing "Tests FMI weather data (JSON) updating"
    (with-redefs [j/read-value (fn [_ _]
                                 {:observations []})]
      (is (nil? (-update-fmi-weather-data-json 87874))))
    (with-redefs [j/read-value (fn [_ _]
                                 {:observations nil})]
      (is (nil? (-update-fmi-weather-data-json 87874))))))

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
                                      (PSQLState/COMMUNICATION_ERROR))))]
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

;; OWM

(def owm-json-resp (j/write-value-as-string
                    {:lat 63.25,
                     :lon 20.74,
                     :timezone "Europe/Helsinki",
                     :timezone_offset 10800,
                     :current {:sunset 1630086101,
                               :pressure 1024,
                               :temp 12.62,
                               :dt 1630047529,
                               :sunrise 1630033434,
                               :wind_speed 2.68,
                               :humidity 81,
                               :feels_like 12.05,
                               :uvi 0.94,
                               :weather [{:id 804,
                                          :main "Clouds",
                                          :description "overcast clouds",
                                          :icon "04d"}],
                               :clouds 100,
                               :wind_deg 58,
                               :visibility 10000,
                               :dew_point 9.45},
                     :hourly [{}
                              {:pressure 1024,
                               :temp 12.62,
                               :pop 0,
                               :dt 1630047600,
                               :wind_speed 7.12,
                               :wind_gust 11.93,
                               :humidity 81,
                               :feels_like 12.05,
                               :uvi 0.94,
                               :weather
                               [{:id 804,
                                 :main "Clouds",
                                 :description "overcast clouds",
                                 :icon "04d"}],
                               :clouds 100,
                               :wind_deg 66,
                               :visibility 10000,
                               :dew_point 9.45}]}))

(deftest test-fetch-owm-data
  (testing "Tests OpenWeatherMap data fetching"
    (with-fake-routes {#"https://api.openweathermap.org/data(.+)"
                       (fn [_] {:status 403})}
      (is (nil? (-fetch-owm-data 123 456 789))))
    (with-fake-routes {#"https://api.openweathermap.org/data(.+)"
                       (fn [_] {:status 200
                                :body owm-json-resp})}
      (is (= {:current
              {:sunset 1630086101,
               :pressure 1024,
               :temp 12.62,
               :dt 1630047529,
               :sunrise 1630033434,
               :wind_speed 2.68,
               :humidity 81,
               :feels_like 12.05,
               :uvi 0.94,
               :weather
               [{:id 804,
                 :main "Clouds",
                 :description "overcast clouds",
                 :icon "04d"}],
               :clouds 100,
               :wind_deg 58,
               :visibility 10000,
               :dew_point 9.45},
              :forecast
              {:pressure 1024,
               :temp 12.62,
               :pop 0,
               :dt 1630047600,
               :wind_speed 7.12,
               :wind_gust 11.93,
               :humidity 81,
               :feels_like 12.05,
               :uvi 0.94,
               :weather
               [{:id 804,
                 :main "Clouds",
                 :description "overcast clouds",
                 :icon "04d"}],
               :clouds 100,
               :wind_deg 66,
               :visibility 10000,
               :dew_point 9.45}}
             (dissoc (-fetch-owm-data 123 456 789) :stored))))))

;; General

(deftest test-get-weather-data
  (testing "Tests FMI and OpenWeatherMap data query"
    (with-fake-routes {#"https://api.openweathermap.org/data(.+)"
                       (fn [_] {:status 200
                                :body owm-json-resp})}
      (let [weather-data (get-weather-data)]
        (is (seq (:current (:owm weather-data))))
        (is (= {:time #inst "2022-06-07T16:00:00.000000000-00:00"
                :temperature 17.0
                :wind-speed 4.0
                :cloudiness 0
                :wind-direction {:short "SE"
                                 :long "south east"}
                :precipitation 1.0}
               (:forecast (:fmi weather-data))))
        (is (empty? (:not-found weather-data)))))))
