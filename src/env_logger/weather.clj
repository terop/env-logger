(ns env-logger.weather
  "Namespace for weather fetching code"
  (:require [clojure.xml :refer [parse]]
            [clojure.zip :refer [xml-zip]]
            [clojure.data.zip.xml :as zx]
            [clojure.math :refer [pow round]]
            [clojure.string :as str]
            [config.core :refer [env]]
            [taoensso.timbre :refer [error info warn]]
            [jsonista.core :as j]
            [clj-http.client :as client]
            [next.jdbc :as jdbc]
            [java-time.api :as jt]
            [env-logger [db :refer [rs-opts -convert-time->iso8601-str]]])
  (:import (java.time LocalDateTime ZonedDateTime)
           java.time.temporal.ChronoUnit
           org.postgresql.util.PSQLException))
(refer-clojure :exclude '[filter for group-by into partition-by set update])
(require '[honey.sql :as sql])

(def fmi-current (atom {}))
(def fmi-forecast (atom nil))
(def ast-data (atom {}))

(def retry-count 3)

;; Utilities

(defn calculate-start-time
  "Calculates the start time for the data request and returns it as a
  DateTime object. The time is the closest even ten minutes in the past,
  example: for 08:27 it would be 08:20."
  []
  (let [curr-minute (ZonedDateTime/.getMinute (jt/zoned-date-time))
        start-time (jt/minus (jt/zoned-date-time)
                             (jt/minutes (- curr-minute
                                            (- curr-minute
                                               (mod curr-minute 10))))
                             (jt/seconds (ZonedDateTime/.getSecond
                                          (jt/zoned-date-time))))]
    start-time))

(defn -convert-dt->tz-iso8601-str
  "Formats and returns a datetime as an ISO 8601 formatted start time string
  having the :weather-zone value timezone before ISO 8601 conversion."
  [datetime]
  (-convert-time->iso8601-str (jt/with-zone
                                datetime
                                (:weather-timezone env))))

(defn store-weather-data?
  "Tells whether to store FMI weather data.
  The criteria for storing is that the candidate datetime to be stored comes
  after the latest stored weather observation. Storing is also allowed if there
  is no latest stored observation."
  [db-con cand-dt]
  (try
    (if-not cand-dt
      false
      (if-let [latest-stored (:time
                              (jdbc/execute-one! db-con
                                                 (sql/format
                                                  {:select :time
                                                   :from :weather_data
                                                   :order-by [[:id :desc]]
                                                   :limit 1})
                                                 rs-opts))]
        (jt/after?
         (jt/local-date-time cand-dt)
         (jt/local-date-time latest-stored))
        true))
    (catch PSQLException pe
      (error pe "Weather data storage check failed")
      false)))

(defn -calculate-summer-simmer
  "Calculate the summer simmer value."
  [temperature humidity-percent]
  (if (<= temperature 14.5)
    temperature
    (let [humidity-ref 0.5
          humidity (/ humidity-percent 100.0)]
      (/ (- (* 1.8 temperature) (* 0.55 (- 1 humidity)
                                   (- (* 1.8 temperature) 26))
            (* 0.55 (- 1 humidity-ref) 26))
         (* 1.8 (- 1 (* 0.55 (- 1 humidity-ref))))))))

(defn -calculate-feels-like-temp
  "Calculate the 'feels like' temperature. The formula is based on FMI
  'feels like' temperature calculation."
  [temperature wind-speed humidity radiation]
  ;; Formula taken from https://github.com/saaste/fmi-weather-client/blob/
  ;; master/fmi_weather_client/parsers/forecast.py#L150
  (if (< wind-speed 0.0)
    temperature
    (let [chill (+ 15 (* (- 1 (/ 15 37)) temperature)
                   (* (/ 15 37) (pow (inc wind-speed) 0.16) (- temperature 37)))
          heat (-calculate-summer-simmer temperature humidity)
          radiation-correction (if radiation (/ (* 0.7 0.07 radiation)
                                                (- (+ wind-speed 10) 0.25))
                                   0)]
      (Float/parseFloat (format "%.1f"
                                (+ temperature
                                   (- chill temperature)
                                   (- heat temperature)
                                   radiation-correction))))))

;; FMI

(defn wd-has-empty-values?
  "Returns true if the provided weather data observation has a nil value
  in its cloudiness, temperature, or wind-speed values."
  [observation]
  (or (nil? (:cloudiness observation))
      (nil? (:temperature observation))
      (nil? (:wind-speed observation))))

(defn get-wd-str
  "Returns a dict with the long and short form of the given wind direction
  in degrees."
  [wind-direction]
  (if-not wind-direction
    {:short "invalid"
     :long "invalid"}
    (cond
      (and (>= wind-direction 0)
           (< wind-direction 25.0)) {:short "N"
                                     :long "north"}
      (and (>= wind-direction 25.0)
           (< wind-direction 65.0)) {:short "NE"
                                     :long "north east"}
      (and (>= wind-direction 65.0)
           (< wind-direction 115.0)) {:short "E"
                                      :long "east"}
      (and (>= wind-direction 115.0)
           (< wind-direction 155.0)) {:short "SE"
                                      :long "south east"}
      (and (>= wind-direction 155.0)
           (< wind-direction 205.0)) {:short "S"
                                      :long "south"}
      (and (>= wind-direction 205.0)
           (< wind-direction 245.0)) {:short "SW"
                                      :long "south west"}
      (and (>= wind-direction 245.0)
           (< wind-direction 295.0)) {:short "W"
                                      :long "west"}
      (and (>= wind-direction 295.0)
           (< wind-direction 335.0)) {:short "NW"
                                      :long "north west"}
      (and (>= wind-direction 335.0)
           (<= wind-direction 360.0)) {:short "N"
                                       :long "north"})))

(defn extract-forecast-data
  "Parses and returns forecast data values from the given XML data."
  [parsed-xml]
  (when (>= (count (:content parsed-xml)) 5)
    (let [root (xml-zip parsed-xml)
          values (map
                  zx/text
                  (zx/xml-> root :wfs:member
                            :BsWfs:BsWfsElement
                            :BsWfs:ParameterValue))
          date-text (zx/text (zx/xml1-> root
                                        :wfs:member
                                        :BsWfs:BsWfsElement
                                        :BsWfs:Time))]
      {:time (jt/sql-timestamp
              (jt/local-date-time (jt/instant date-text)
                                  (:weather-timezone env)))
       :temperature (Float/parseFloat (format "%.1f" (Float/parseFloat
                                                      (nth values 0))))
       :wind-speed (Float/parseFloat (format "%.1f" (Float/parseFloat
                                                     (nth values 1))))
       :cloudiness (round (Float/parseFloat (nth values 2)))
       :wind-direction (get-wd-str (Float/parseFloat
                                    (nth values 3)))
       :precipitation (Float/parseFloat (format "%.1f" (Float/parseFloat
                                                        (nth values 4))))
       :humidity (Float/parseFloat (format "%.1f" (Float/parseFloat
                                                   (nth values 5))))
       :radiation (Float/parseFloat (format "%.1f" (Float/parseFloat
                                                    (nth values 6))))})))

(defn -update-fmi-weather-data-json
  "Updates the latest FMI weather data from the FMI JSON for the given weather
  observation station."
  [station-id]
  (try
    ;; The first check is to prevent pointless fetch attempts when data
    ;; is not yet available
    (when (and (>= (rem (jt/as (jt/local-date-time) :minute-of-hour) 10) 3)
               (nil? (get @fmi-current (-convert-dt->tz-iso8601-str
                                        (calculate-start-time)))))
      (let [url (format (str "https://www.ilmatieteenlaitos.fi/api/weather/"
                             "observations?fmisid=%s&observations=true")
                        station-id)
            parsed-json (j/read-value (:body (client/get url))
                                      (j/object-mapper
                                       {:decode-key-fn true}))]
        (when (and (:observations parsed-json)
                   (seq (:observations parsed-json)))
          (let [obs (last (:observations parsed-json))
                time-str (subs (str/replace (:localtime obs) "T" "") 0 12)
                local-dt (jt/local-date-time "yyyyMMddHHmm" time-str)
                ;; Assume that the timestamp is always in local (i.e.
                ;; Europe/Helsinki) timezone
                wd {:time (jt/sql-timestamp
                           (jt/zoned-date-time local-dt
                                               "Europe/Helsinki"))
                    :temperature (:t2m obs)
                    :cloudiness (if-not (nil? (:TotalCloudCover obs))
                                  (:TotalCloudCover obs) 9)
                    :wind-speed (:WindSpeedMS obs)
                    :wind-direction (get-wd-str (:WindDirection obs))
                    :humidity (:Humidity obs)
                    :feels-like (-calculate-feels-like-temp
                                 (:t2m obs)
                                 (:WindSpeedMS obs)
                                 (:Humidity obs)
                                 nil)}]
            (when-not (nil? (:temperature wd))
              (swap! fmi-current conj
                     {(-convert-time->iso8601-str (:time wd)) wd}))))))
    (catch Exception ex
      (error ex "FMI weather data (JSON) fetch failed")
      nil)))

(defn -update-fmi-weather-data-ts
  "Updates the latest FMI weather data from the FMI time series data for the
  given weather observation station."
  [station-id]
  (try
    (when (nil? (get @fmi-current (-convert-dt->tz-iso8601-str
                                   (calculate-start-time))))
      (let [url (format (str "https://opendata.fmi.fi/timeseries?producer="
                             "opendata&fmisid=%s&param=time,tz,temperature,"
                             "cloudiness,windspeed,winddirection,humidity"
                             "&format=json&precision=double&starttime=%s")
                        station-id
                        (-convert-dt->tz-iso8601-str (calculate-start-time)))
            parsed-json (j/read-value (:body (client/get url))
                                      (j/object-mapper
                                       {:decode-key-fn true}))]
        (when (seq parsed-json)
          (let [obs (last parsed-json)
                time-str (subs (str/replace (:time obs) "T" "") 0 12)
                local-dt (jt/local-date-time "yyyyMMddHHmm" time-str)
                offset (ChronoUnit/.between
                        ChronoUnit/HOURS
                        (LocalDateTime/.atZone local-dt (jt/zone-id (:tz obs)))
                        (LocalDateTime/.atZone local-dt
                                               (jt/zone-id (:weather-timezone
                                                            env))))
                wd {:time
                    (jt/sql-timestamp
                     (jt/minus
                      (jt/zoned-date-time local-dt (:tz obs))
                      (jt/hours offset))),
                    :temperature (:temperature obs)
                    :cloudiness (if-not (nil? (:cloudiness obs))
                                  (int (:cloudiness obs)) 9)
                    :wind-speed (:windspeed obs)
                    :wind-direction (get-wd-str (:winddirection obs))
                    :humidity (:humidity obs)
                    :feels-like (-calculate-feels-like-temp
                                 (:temperature obs)
                                 (:windspeed obs)
                                 (:humidity obs)
                                 nil)}]
            (when wd
              (swap! fmi-current
                     conj
                     {(-convert-time->iso8601-str (:time wd)) wd}))))))
    (catch Exception ex
      (error ex "FMI weather data (time series) fetch failed")
      nil)))

(defn get-fmi-weather-data
  "Fetches the latest FMI weather data from the cache.
  If there is no data for the current or the previous check times nil
  is returned."
  []
  (or (get @fmi-current
           (-convert-dt->tz-iso8601-str (calculate-start-time)))
      (get @fmi-current (-convert-dt->tz-iso8601-str
                         (jt/minus (calculate-start-time) (jt/minutes 10))))))

(defn -update-fmi-weather-forecast
  "Updates the latest FMI forecaster edited weather forecast from the FMI WFS
  for the given weather observation station."
  [latitude longitude]
  (future
    (let [start-time (jt/plus (jt/zoned-date-time) (jt/minutes 10))
          end-time (jt/plus start-time (jt/hours 1))
          url (format (str "https://opendata.fmi.fi/wfs?service=WFS&version="
                           "2.0.0&request=getFeature&storedquery_id=fmi::"
                           "forecast::edited::weather::scandinavia::point::"
                           "simple&latlon="
                           "%s&parameters=Temperature,WindSpeedMS,"
                           "TotalCloudCover,WindDirection,Precipitation1h,"
                           "Humidity,RadiationGlobal&starttime=%s&endtime=%s")
                      (str latitude "," longitude)
                      ;; Start time must always be ahead of the current time so
                      ;; that forecast for the next hour is fetched
                      (-convert-dt->tz-iso8601-str start-time)
                      (-convert-dt->tz-iso8601-str end-time))
          index (atom retry-count)]
      (try
        (while (and (pos? @index)
                    (or (nil? @fmi-forecast)
                        (< (abs (jt/time-between (jt/local-date-time
                                                  (:time @fmi-forecast))
                                                 (jt/local-date-time)
                                                 :minutes)) 15)
                        (> (abs (jt/time-between (jt/local-date-time)
                                                 (:fetched @fmi-forecast)
                                                 :minutes)) 30)))
          (if-let [forecast (extract-forecast-data (parse url))]
            (reset! fmi-forecast
                    (dissoc (assoc forecast
                                   :fetched (jt/local-date-time)
                                   :feels-like (-calculate-feels-like-temp
                                                (:temperature forecast)
                                                (:wind-speed forecast)
                                                (:humidity forecast)
                                                (:radiation forecast)))
                            :radiation))
            (do
              (info (str "Retrying forecast fetch, attempt "
                         (- retry-count (dec @index))
                         " of " retry-count))
              (Thread/sleep 5000)))
          (swap! index dec))
        (catch org.xml.sax.SAXParseException spe
          (error spe "FMI forecast parsing failed")
          nil)
        (catch Exception ex
          (error ex "FMI forecast fetch failed")
          nil)))))

;; Astronomy data

(defn -fetch-astronomy-data
  "Fetch astronomy data, currently sunrise and sunset times. Data is fetched
  from the ipgeolocation API."
  [api-key latitude longitude]
  (let [url (format (str "https://api.ipgeolocation.io/astronomy?"
                         "apiKey=%s&lat=%s&long=%s")
                    api-key
                    (str latitude)
                    (str longitude))
        date-str (str (jt/local-date))]
    (when (or (empty? @ast-data)
              (nil? (get @ast-data date-str)))
      (let [resp (try
                   (client/get url)
                   (catch Exception ex
                     (error ex "Astronomy data fetch failed")))]
        (when (= 200 (:status resp))
          (let [all-data (j/read-value (:body resp)
                                       (j/object-mapper
                                        {:decode-key-fn true}))]
            (reset! ast-data (assoc @ast-data (:date all-data)
                                    {:sunrise (:sunrise all-data)
                                     :sunset (:sunset all-data)}))))))))

;; General

(defn fetch-all-weather-data
  "Fetches all FMI weather as well as astronomy data."
  []
  (when (or (nil? (-update-fmi-weather-data-ts (:fmi-station-id env)))
            (and (seq @fmi-current)
                 (wd-has-empty-values? (last (last @fmi-current)))))
    (when (wd-has-empty-values? (last (last @fmi-current)))
      (warn "Got nil values in FMI weather data observation (from TS):"
            (last (last @fmi-current))))
    (-update-fmi-weather-data-json (:fmi-station-id env))
    (when (wd-has-empty-values? (last (last @fmi-current)))
      (warn "Got nil values in FMI weather data observation (from JSON):"
            (last (last @fmi-current)))))
  (-update-fmi-weather-forecast (:weather-lat env)
                                (:weather-lon env))
  (-fetch-astronomy-data (:ipgeol-api-key env)
                         (:weather-lat env)
                         (:weather-lon env)))

(defn get-weather-data
  "Get weather (FMI) and astronomy data from cache if it is recent enough.
  Otherwise fetch updated data and store it in the cache. Always return
  the available data."
  []
  {:fmi {:current (get-fmi-weather-data)
         :forecast (dissoc @fmi-forecast :fetched)}
   :ast (get @ast-data (str (jt/local-date)))})
