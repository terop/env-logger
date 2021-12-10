(ns env-logger.weather-test
  (:require [clojure.core.cache.wrapped :as c]
            [clojure.test :refer :all]
            [clojure.string :as s]
            [clojure.xml :refer [parse]]
            [clj-http.fake :refer [with-fake-routes]]
            [cheshire.core :refer [generate-string]]
            [java-time :as t]
            [next.jdbc :as jdbc]
            [env-logger.weather :refer :all])
  (:import java.time.ZonedDateTime
           java.util.Date))

;; FMI

(deftest test-data-extraction
  (testing "Data extraction function tests"
    (is (= {:wind-speed 5.0,
            :cloudiness 3,
            :temperature -9.0,
            :date #inst "2021-12-02T18:10:00.000000000-00:00"}
           (extract-weather-data
            (load-file "test/env_logger/wfs_extraction_data.txt"))))
    (is (nil? (extract-weather-data
               (load-file
                "test/env_logger/wfs_extraction_data_invalid.txt"))))))

(deftest test-forecast-data-extraction
  (testing "Forecast data extraction function tests"
    (is (= {:temperature -5.0
            :wind-speed 7.0
            :cloudiness 50}
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
              :date #inst "2021-12-02T18:10:00.000000000-00:00"}
             (-get-fmi-weather-data-wfs 87874))))
    (with-redefs [parse (fn [_]
                          {:content "garbage"})]
      (is (nil? (-get-fmi-weather-data-wfs 87874))))))

(deftest test-forecast-data-fetch
  (testing "Tests FMI forecast data fetching"
    (with-redefs [parse (fn [_]
                          (load-file "test/env_logger/wfs_forecast_data.txt"))]
      (is (= {:temperature -5.0
              :wind-speed 7.0
              :cloudiness 50}
             (-get-fmi-weather-forecast 87874))))
    (with-redefs [parse (fn [_]
                          {:content "garbage"})]
      (is (nil? (-get-fmi-weather-forecast 87874))))))

(deftest test-weather-data-extraction-json
  (testing "Tests FMI weather data (JSON) extraction"
    (with-fake-routes {#"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 403})}
      (is (nil? (-get-fmi-weather-data-json 87874))))
    (with-fake-routes {#"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 200
                                :body "Invalid JSON"})}
      (is (nil? (-get-fmi-weather-data-json 87874))))
    (with-fake-routes {#"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 200
                                :body (generate-string
                                       {"generated" 1539719550869,
                                        "latestObservationTime" 1539719400000,
                                        "timeZoneId" "Europe/Helsinki",
                                        "TotalCloudCover" []})})}
      (is (nil? (-get-fmi-weather-data-json 87874))))
    (with-fake-routes {#"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 200
                                :body
                                (generate-string
                                 {"generated" 1539719550869
                                  "latestObservationTime" 1539719400000
                                  "timeZoneId" "Europe/Helsinki"
                                  "t2m" [[1539208800000 9.0]
                                         [1539212400000 11.0]]
                                  "TotalCloudCover" [[1539208800000 0]
                                                     [1539212400000 2]]
                                  "WindSpeedMS" [[1539208800000 5]
                                                 [1539212400000 6]]})})}
      (is (= {:date (t/sql-timestamp (t/zoned-date-time
                                      (str (.toInstant (new Date
                                                            1539719400000)))))
              :temperature 11.0
              :cloudiness 2
              :wind-speed 6}
             (-get-fmi-weather-data-json 87874))))))

(deftest test-weather-data-extraction
  (testing "Tests FMI weather data (JSON and WFS) extraction"
    (with-fake-routes {#"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 200
                                :body
                                (generate-string
                                 {"generated" 1539719550869
                                  "latestObservationTime" 1539719400000
                                  "timeZoneId" "Europe/Helsinki"
                                  "t2m" [[1539208800000 9.0]
                                         [1539212400000 11.0]]
                                  "TotalCloudCover" [[1539208800000 0]
                                                     [1539212400000 2]]
                                  "WindSpeedMS" [[1539208800000 5]
                                                 [1539212400000 6]]})})}
      (is (= {:date (t/sql-timestamp (t/zoned-date-time
                                      (str (.toInstant (new Date
                                                            1539719400000)))))
              :temperature 11.0
              :cloudiness 2
              :wind-speed 6}
             (get-fmi-weather-data 87874)))
      (with-redefs [parse (fn [_]
                            (load-file "test/env_logger/wfs_data.txt"))]
        (with-fake-routes {#"https:\/\/ilmatieteenlaitos.fi\/observation-data"
                           (fn [_] {:status 403})}
          (is (= {:wind-speed 5.0,
                  :cloudiness 3,
                  :temperature -9.0,
                  :date #inst "2021-12-02T18:10:00.000000000-00:00"}
                 (get-fmi-weather-data 87874)))))
      (with-fake-routes {#"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                         (fn [_] {:status 403})
                         #"https:\/\/opendata\.fmi\.fi\/wfs\?(.+)"
                         (fn [_] {:status 404})}
        (is (nil? (get-fmi-weather-data 87874)))))))

(deftest weather-query-ok
  (testing "Test when it is OK to query for FMI weather data"
    (with-redefs [jdbc/execute-one! (fn [con query opts] '())]
      (is (true? (weather-query-ok? {} 5))))
    (with-redefs [jdbc/execute-one! (fn [con query opts]
                                      {:recorded (t/minus (t/offset-date-time)
                                                          (t/minutes 3))})]
      (is (false? (weather-query-ok? {} 5))))
    (with-redefs [jdbc/execute-one! (fn [con query opts]
                                      {:recorded (t/minus (t/offset-date-time)
                                                          (t/minutes 3))})]
      (is (true? (weather-query-ok? {} 3))))
    (with-redefs [jdbc/execute-one! (fn [con query opts]
                                      {:recorded (t/minus (t/offset-date-time)
                                                          (t/minutes 6))})]
      (is (true? (weather-query-ok? {} 5))))))

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
