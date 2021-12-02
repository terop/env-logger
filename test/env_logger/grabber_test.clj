(ns env-logger.grabber-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [clojure.xml :refer [parse]]
            [clj-http.fake :refer [with-fake-routes]]
            [cheshire.core :refer [generate-string]]
            [java-time :as t]
            [next.jdbc :as jdbc]
            [env-logger.grabber :refer :all])
  (:import java.time.ZonedDateTime
           java.util.Date))

(deftest test-data-extraction
  (testing "Data extraction function tests"
    (is (= {:wind-speed 5.0,
            :cloudiness 3,
            :temperature -9.0,
            :date #inst "2021-12-02T18:10:00.000000000-00:00"}
           (extract-data
            (load-file "test/env_logger/wfs_extraction_data.txt"))))
    (is (nil? (extract-data
               (load-file
                "test/env_logger/wfs_extraction_data_invalid.txt"))))))

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

(deftest test-weather-data-extraction-json
  (testing "Tests FMI weather data (JSON) extraction"
    (with-fake-routes {
                       #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 403})}
      (is (nil? (-get-fmi-weather-data-json 87874))))
    (with-fake-routes {
                       #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 200
                                :body "Invalid JSON"})}
      (is (nil? (-get-fmi-weather-data-json 87874))))
    (with-fake-routes {
                       #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 200
                                :body (generate-string
                                       {"generated" 1539719550869,
                                        "latestObservationTime" 1539719400000,
                                        "timeZoneId" "Europe/Helsinki",
                                        "TotalCloudCover" []})})}
      (is (nil? (-get-fmi-weather-data-json 87874))))
    (with-fake-routes {
                       #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
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
    (with-fake-routes {
                       #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
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
        (with-fake-routes {
                           #"https:\/\/ilmatieteenlaitos.fi\/observation-data"
                           (fn [_] {:status 403})}
          (is (= {:wind-speed 5.0,
                  :cloudiness 3,
                  :temperature -9.0,
                  :date #inst "2021-12-02T18:10:00.000000000-00:00"}
                 (get-fmi-weather-data 87874)))))
      (with-fake-routes {
                         #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
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
