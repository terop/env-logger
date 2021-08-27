(ns env-logger.owm
  "Namespace for OpenWeatherMap related code."
  (:require [clojure.core.cache.wrapped :as c]
            [clojure.tools.logging :as log]
            [cheshire.core :refer [parse-string]]
            [clj-http.client :as client]
            [env-logger.config :refer [get-conf-value]]
            [java-time :as t]))

(def owm-cache (c/basic-cache-factory {:data nil :recorded nil}))

(defn -cache-set-value
  "Set a value for the given item in a given cache."
  [cache item value]
  (c/evict cache item)
  (c/through-cache cache item (constantly value)))

(defn fetch-owm-data
  "Fetch weather data from OpenWeatherMap, this data contains both current
  weather and forecast data. Returns nil map if query failed."
  [app-id latitude longitude]
  (let [url (format (str "https://api.openweathermap.org/data/2.5/onecall?"
                         "lat=%s&lon=%s&exclude=minutely,daily,alerts&"
                         "units=metric&appid=%s")
                    (str latitude)
                    (str longitude)
                    app-id)
        resp (try
               (client/get url)
               (catch Exception e
                 (log/error (str "OWM data fetch failed, status: " (str e)))
                 nil))]
    (when (= 200 (:status resp))
      (let [all-data (parse-string (:body resp) true)]
        {:current (:current all-data)
         :forecast (nth (:hourly all-data) 1)}))))

(defn- update-cache-data
  "Updates the OpenWeatherMap data in the cache."
  []
  (-cache-set-value owm-cache
                    :data
                    (fetch-owm-data (get-conf-value :owm-app-id)
                                    (get-conf-value :forecast-lat)
                                    (get-conf-value :forecast-lon)))
  (-cache-set-value owm-cache
                    :recorded
                    (str (t/local-date-time))))

(defn get-owm-data
  "Get OpenWeatherMap weather data from cache if it is recent enough.
  Otherwise fetch updated data and store it in the cache. Always return
  the available data."
  []
  (if-not (nil? (c/lookup owm-cache :recorded))
    (when (>= (t/time-between (t/local-date-time
                               (c/lookup owm-cache :recorded))
                              (t/local-date-time)
                              :minutes)
              (get-conf-value :owm-query-threshold))
      (update-cache-data))
    (update-cache-data))
  (c/lookup owm-cache :data))
