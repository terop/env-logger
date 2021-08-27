(ns env-logger.owm-test
  (:require [clojure.core.cache.wrapped :as c]
            [clojure.test :refer :all]
            [cheshire.core :refer [generate-string]]
            [clj-http.fake :refer [with-fake-routes]]
            [env-logger.owm :refer :all]))

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

(deftest test-cache-set-value
  (testing "Tests cache value storing"
    (let [test-cache (c/basic-cache-factory {})]
      (is (false? (c/has? test-cache :test-value)))
      (-cache-set-value test-cache :test-value 42)
      (is (= 42 (c/lookup test-cache :test-value))))))

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

(deftest test-get-owm-data
  (testing "Tests OpenWeatherMap data query"
    (with-fake-routes {#"https://api.openweathermap.org/data(.+)"
                       (fn [_] {:status 200
                                :body owm-json-resp})}
      (let [owm-data (get-owm-data)]
        (is (seq (:current owm-data)))
        (is (seq (:forecast owm-data)))
        (is (empty? (:not-found owm-data)))))))
