(ns env-logger.weather-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.cache.wrapped :as c]
            [clojure.string :as s]
            [clojure.xml :refer [parse]]
            [clj-http.fake :refer [with-fake-routes]]
            [cheshire.core :refer [generate-string]]
            [java-time :as t]
            [next.jdbc :as jdbc]
            [env-logger.weather :refer [-cache-set-value
                                        -get-fmi-weather-data-wd
                                        -get-fmi-weather-data-wfs
                                        -get-fmi-weather-forecast
                                        calculate-start-time
                                        extract-forecast-data
                                        extract-weather-data
                                        extract-weather-data-wd
                                        fetch-owm-data
                                        get-fmi-weather-data
                                        get-wd-str
                                        get-weather-data
                                        weather-query-ok?]])
  (:import java.time.ZonedDateTime))

;; FMI

(deftest test-data-extraction
  (testing "Data extraction function tests"
    (is (= {:wind-speed 5.0,
            :cloudiness 3,
            :temperature -9.0,
            :time #inst "2021-12-02T18:10:00.000000000-00:00"}
           (extract-weather-data
            (load-file "test/env_logger/wfs_extraction_data.txt"))))
    (is (nil? (extract-weather-data
               (load-file
                "test/env_logger/wfs_extraction_data_invalid.txt"))))))

(deftest test-wd-data-extraction
  (testing "Wind direction data extraction function tests"
    (is (= 300.0
           (extract-weather-data-wd
            (load-file "test/env_logger/wfs_data_wd.txt"))))))

(deftest test-forecast-data-extraction
  (testing "Forecast data extraction function tests"
    (is (= {:temperature -8.0
            :wind-speed 5.0
            :cloudiness 0
            :wind-direction {:long "north west", :short "NW"}}
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

(deftest test-weather-data-extraction-wfs
  (testing "Tests FMI weather data (WFS) extraction"
    (with-redefs [parse (fn [_]
                          (load-file "test/env_logger/wfs_data.txt"))]
      (is (= {:wind-speed 5.0,
              :cloudiness 3,
              :temperature -9.0,
              :time #inst "2021-12-02T18:10:00.000000000-00:00"}
             (-get-fmi-weather-data-wfs 87874))))
    (with-redefs [parse (fn [_]
                          {:content "garbage"})]
      (is (nil? (-get-fmi-weather-data-wfs 87874))))))

(deftest test-weather-data-extraction-wd
  (testing "Tests FMI weather wind speed data extraction"
    (with-redefs [parse (fn [_]
                          (load-file "test/env_logger/wfs_data_wd.txt"))]
      (is (= 300.0
             (-get-fmi-weather-data-wd 87874))))))

(deftest test-forecast-data-fetch
  (testing "Tests FMI forecast data fetching"
    (with-redefs [parse (fn [_]
                          (load-file "test/env_logger/wfs_forecast_data.txt"))]
      (is (= {:temperature -8.0
              :wind-speed 5.0
              :cloudiness 0
              :wind-direction {:long "north west", :short "NW"}}
             (-get-fmi-weather-forecast 87874))))
    (with-redefs [parse (fn [_]
                          {:content "garbage"})]
      (is (nil? (-get-fmi-weather-forecast 87874))))))

(deftest test-weather-data-extraction
  (testing "Tests FMI weather data (WFS) extraction"
    (with-redefs [parse (fn [_]
                          (load-file "test/env_logger/wfs_data.txt"))]
      (is (= {:wind-speed 5.0,
              :cloudiness 3,
              :temperature -9.0,
              :time #inst "2021-12-02T18:10:00.000000000-00:00"}
             (get-fmi-weather-data 87874))))
    (with-fake-routes {#"https:\/\/opendata\.fmi\.fi\/wfs\?(.+)"
                       (fn [_] {:status 404})}
      (is (nil? (get-fmi-weather-data 87874))))))

(deftest test-weather-query-ok
  (testing "Test when it is OK to query for FMI weather data"
    (with-redefs [jdbc/execute-one! (fn [_ _ _] '())]
      (is (true? (weather-query-ok? {} 5))))
    (with-redefs [jdbc/execute-one! (fn [_ _ _]
                                      {:recorded (t/minus (t/offset-date-time)
                                                          (t/minutes 3))})]
      (is (false? (weather-query-ok? {} 5))))
    (with-redefs [jdbc/execute-one! (fn [_ _ _]
                                      {:recorded (t/minus (t/offset-date-time)
                                                          (t/minutes 3))})]
      (is (true? (weather-query-ok? {} 3))))
    (with-redefs [jdbc/execute-one! (fn [_ _ _]
                                      {:recorded (t/minus (t/offset-date-time)
                                                          (t/minutes 6))})]
      (is (true? (weather-query-ok? {} 5))))))

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

(def owm-json-resp (generate-string
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
      (is (nil? (fetch-owm-data 123 456 789))))
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
             (fetch-owm-data 123 456 789))))))

(deftest test-get-weather-data
  (testing "Tests FMI and OpenWeatherMap data query"
    (with-fake-routes {#"https://api.openweathermap.org/data(.+)"
                       (fn [_] {:status 200
                                :body owm-json-resp})}
      (let [weather-data (get-weather-data)]
        (is (seq (:current (:owm weather-data))))
        (is (seq (:forecast (:owm weather-data))))
        (is (empty? (:not-found weather-data)))))))

;; Cache

(deftest test-cache-set-value
  (testing "Tests cache value storing"
    (let [test-cache (c/basic-cache-factory {})]
      (is (false? (c/has? test-cache :test-value)))
      (-cache-set-value test-cache :test-value 42)
      (is (= 42 (c/lookup test-cache :test-value))))))
