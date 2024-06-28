(ns env-logger.db
  "Namespace containing the application's database function"
  (:require [clojure.string :as str]
            [config.core :refer [env]]
            [taoensso.timbre :refer [error info]]
            [next.jdbc :as jdbc]
            [next.jdbc [result-set :as rs] [sql :as js]]
            [java-time.api :as t])
  (:import (java.text NumberFormat
                      DecimalFormat)
           (java.time DateTimeException
                      LocalDateTime
                      ZoneId
                      ZoneOffset)
           (java.util Date
                      TimeZone)
           org.postgresql.util.PSQLException))
(refer-clojure :exclude '[filter for group-by into partition-by set update])
(require '[honey.sql :as sql])

(defn db-conf
  "Returns the value of the requested database configuration key"
  [k]
  (k (:database env)))

(let [db-host (get (System/getenv)
                   "POSTGRESQL_DB_HOST"
                   (db-conf :host))
      db-port (get (System/getenv)
                   "POSTGRESQL_DB_PORT")
      db-name (get (System/getenv)
                   "POSTGRESQL_DB_NAME"
                   (db-conf :name))
      db-user (get (System/getenv)
                   "POSTGRESQL_DB_USERNAME"
                   (db-conf :username))
      db-password (get (System/getenv)
                       "POSTGRESQL_DB_PASSWORD"
                       (db-conf :password))]
  (def postgres (merge {:dbtype "postgresql"
                        :dbname db-name
                        :host db-host
                        :user db-user
                        :password db-password}
                       (when db-port
                         {:db-port db-port}))))
(def postgres-ds (jdbc/get-datasource postgres))
(def rs-opts {:builder-fn rs/as-unqualified-kebab-maps})

(def tb-image-pattern
  #"testbed-(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:?\+\d{2}(:?:\d{2})?|Z)).+")

(defn test-db-connection
  "Tests the connection to the DB."
  [db-con]
  (try
    (= 1 (:?column? (jdbc/execute-one! db-con ["SELECT 1"])))
    (catch PSQLException pe
      (error pe "DB connection establishment failed")
      false)))

(defn image-age-check
  "Returns true when the following condition is true:
  (image datetime - reference datetime) <= diff-minutes
  and false otherwise. Also return true if
  (image datetime - reference datetime) < 0."
  [image-name ref-dt diff-minutes]
  (let [match (re-find tb-image-pattern
                       image-name)
        image-dt (t/zoned-date-time (t/formatter :iso-offset-date-time)
                                    (nth match 1))]
    (try
      (<= (t/as (t/interval image-dt ref-dt) :minutes)
          diff-minutes)
      (catch DateTimeException _
        ;; ref-dt < image-dt results in a DateTimeException
        true))))

(defn get-tb-image
  "Returns the name of the latest FMI Testbed image."
  [db-con]
  (let [tb-image-name (:tb-image-name
                       (jdbc/execute-one! db-con
                                          (sql/format
                                           {:select [:tb_image_name]
                                            :from [:observations]
                                            :where [[:raw
                                                     (str "tb_image_name IS "
                                                          "NOT NULL")]]
                                            :order-by [[:id :desc]]
                                            :limit [1]})
                                          rs-opts))]
    (when (and tb-image-name
               (image-age-check tb-image-name
                                (t/zoned-date-time)
                                (:image-max-time-diff env)))
      tb-image-name)))

(defn get-tz-offset
  "Returns the offset in hours to UTC for the given timezone."
  [tz]
  (/ (/ (/ (.getOffset (TimeZone/getTimeZone ^String tz)
                       (.getTime (Date.))) 1000) 60) 60))

(defn get-midnight-dt
  "Returns a LocalDateTime at midnight with N days subtracted from the current
  date and time."
  [n-days]
  (let [ldt (t/local-date-time)]
    (t/minus (t/minus ldt
                      (t/days n-days)
                      (t/hours (.getHour ldt))
                      (t/minutes (.getMinute ldt))
                      (t/seconds (.getSecond ldt))
                      (t/nanos (.getNano ldt)))
             ;; Correct generated datetime value when UTC is used as display
             ;; timezone
             (t/hours (if (= (:display-timezone env) "UTC")
                        (get-tz-offset (:store-timezone env))
                        0)))))

(defn convert->epoch-ms
  "Converts the given datetime value to Unix epoch time in milliseconds."
  [tz-offset dt]
  (let [^LocalDateTime subs-dt (t/minus (t/local-date-time dt)
                                        (t/hours tz-offset))]
    (* (.toEpochSecond subs-dt
                       ZoneOffset/UTC)
       1000)))

(defn -convert-to-iso8601-str
  "Converts a ZonedDateTime or a java.sql.Timestamp object to a ISO 8601
  formatted datetime string."
  [datetime]
  (str/replace (str (first (str/split (str (t/instant datetime))
                                      #"\.\d+"))
                    (if (not= java.sql.Timestamp (type datetime))
                      "Z" ""))
               "ZZ" "Z"))

(defn insert-plain-observation
  "Insert a row into observations table."
  [db-con observation]
  (:id (js/insert! db-con
                   :observations
                   {:recorded (t/sql-timestamp
                               (t/minus (t/zoned-date-time
                                         (:timestamp observation))
                                        (t/hours (get-tz-offset
                                                  (:store-timezone env)))))
                    :brightness (:insideLight observation)
                    :outside_temperature (:outsideTemperature observation)
                    :tb_image_name (get-tb-image db-con)}
                   rs-opts)))

(defn insert-beacon
  "Insert a beacon into the beacons table."
  [db-con obs-id observation]
  (let [beacon (:beacon observation)]
    (if (and (and (seq (:mac beacon))
                  (> (count (:mac beacon)) 16))
             (integer? (:rssi beacon))
             (or (when (nil? (:battery beacon)) true)
                 (integer? (:battery beacon))))
      (:id (js/insert! db-con
                       :beacons
                       {:obs_id obs-id
                        :mac_address (:mac beacon)
                        :rssi (:rssi beacon)
                        :battery_level (:battery beacon)}
                       rs-opts))
      (do
        (error "Invalid data for beacon insert: MAC" (:mac beacon) "RSSI"
               (:rssi beacon))
        (when (:battery beacon)
          (error "battery level" (:battery beacon)))
        1))))

(defn insert-wd
  "Insert a FMI weather observation into the database."
  [db-con obs-id weather-data]
  (:id (js/insert! db-con
                   :weather_data
                   {:obs_id obs-id
                    :time (:time weather-data)
                    :temperature (:temperature weather-data)
                    :cloudiness (:cloudiness weather-data)
                    :wind_speed (:wind-speed weather-data)}
                   rs-opts)))

(defn insert-ruuvitag-observations
  "Insert one or more RuuviTag observations into the database."
  [db-con timestamp observations]
  (try
    (let [insert-fn
          (fn [observation]
            (let [values {:name (:name observation)
                          :temperature (:temperature observation)
                          :pressure (:pressure observation)
                          :humidity (:humidity observation)
                          :battery_voltage (:battery_voltage observation)
                          :rssi (:rssi observation)}]
              (:id (js/insert! db-con
                               :ruuvitag_observations
                               (if timestamp
                                 (assoc values
                                        :recorded
                                        (t/sql-timestamp
                                         (t/minus (t/zoned-date-time
                                                   timestamp)
                                                  (t/hours (get-tz-offset
                                                            (:store-timezone
                                                             env))))))
                                 values)
                               rs-opts))))]
      (every? pos? (map insert-fn observations)))
    (catch PSQLException pe
      (error pe "RuuviTag observation insert failed")
      false)))

(defn insert-observation
  "Inserts a observation to the database."
  [db-con observation]
  (if (= 5 (count observation))
    (jdbc/with-transaction [tx db-con]
      (try
        (let [obs-id (insert-plain-observation tx
                                               observation)]
          (when (pos? obs-id)
            (if (or (when-not (seq (:beacon observation)) true)
                    (pos? (insert-beacon tx obs-id observation)))
              (let [weather-data (:weather-data observation)]
                (if (nil? weather-data)
                  true
                  (if (pos? (insert-wd tx obs-id weather-data))
                    true
                    (do
                      (info (str "Database insert: rolling back "
                                 "transaction after weather data insert"))
                      (.rollback tx)
                      false))))
              (do
                (info (str "Database insert: rolling back "
                           "transaction after beacon scan insert"))
                (.rollback tx)
                false))))
        (catch PSQLException pe
          (error pe "Database insert failed")
          (.rollback tx)
          false)))
    (do
      (error "Wrong number of observation parameters provided")
      false)))

(defn validate-date
  "Checks if the given date is nil or if non-nil, it is in the yyyy-mm-dd
  or yyyy-m-d format."
  [date]
  (if (nil? date)
    true
    (some? (re-find #"\d{4}-\d{1,2}-\d{1,2}" date))))

(defn make-local-dt
  "Creates SQL datetime in local time from the provided date string.
  Allowed values for the 'mode' parameter: start,end."
  [date mode]
  (t/minus (t/local-date-time (format "%sT%s"
                                      date
                                      (if (= mode "start")
                                        "00:00:00"
                                        "23:59:59")))
           ;; Correct generated datetime value when UTC is used as display
           ;; timezone
           (t/hours (if (= (:display-timezone env) "UTC")
                      (get-tz-offset (:store-timezone env))
                      0))))

(defn add-tz-offset-to-dt
  "Add the TZ offset of the 'storing timezone' to the provided datetime if the
 system has different timezone than the 'storing timezone'."
  [dt]
  (if-not (= (ZoneId/systemDefault)
             (t/zone-id (:store-timezone env)))
    (t/plus dt
            (t/hours (get-tz-offset (:store-timezone env))))
    dt))

(defmacro get-by-interval
  "Fetches observations in an interval using the provided function."
  [fetch-fn db-con dates dt-column]
  (let [start-dt (gensym 'start)
        end-dt (gensym 'end)]
    `(if (or (not (validate-date (:start ~dates)))
             (not (validate-date (:end ~dates))))
       ()
       (let [~start-dt (if (:start ~dates)
                         (make-local-dt (:start ~dates) "start")
                         ;; Hack to avoid SQL WHERE hacks
                         (t/local-date-time 2010 1 1))
             ~end-dt (if (:end ~dates)
                       (make-local-dt (:end ~dates) "end")
                       (t/local-date-time))]
         (~fetch-fn ~db-con :where [:and
                                    [:>= ~dt-column ~start-dt]
                                    [:<= ~dt-column ~end-dt]])))))

(defn get-observations
  "Fetches observations optionally filtered by a provided SQL WHERE clause.
  Limiting rows is possible by providing row count with the :limit argument."
  [db-con & {:keys [where limit]
             :or {where nil
                  limit nil}}]
  (let [base-query {:select [:o.recorded
                             :o.brightness
                             [:w.time "weather_recorded"]
                             [:w.temperature "fmi_temperature"]
                             :w.cloudiness
                             :w.wind_speed
                             [:o.outside_temperature "o_temperature"]
                             :b.mac_address
                             [:b.rssi "beacon_rssi"]
                             [:b.battery_level "beacon_battery"]
                             :o.tb_image_name]
                    :from [[:observations :o]]
                    :left-join [[:weather-data :w]
                                [:= :o.id :w.obs_id]
                                [:beacons :b]
                                [:= :o.id :b.obs_id]]}
        where-query (if where
                      (assoc base-query :where where)
                      base-query)
        limit-query (if limit
                      (merge where-query {:limit limit
                                          :order-by [[:o.id :desc]]})
                      (assoc where-query :order-by [[:o.id :asc]]))
        beacon-name (:beacon-name env)
        tz-offset (get-tz-offset (:display-timezone env))]
    (for [row (jdbc/execute! db-con
                             (sql/format limit-query)
                             rs-opts)]
      (dissoc (merge row
                     {:recorded (convert->epoch-ms tz-offset
                                                   (:recorded row))
                      :weather-recorded (when (:weather-recorded row)
                                          (convert->epoch-ms
                                           tz-offset
                                           (:weather-recorded row)))
                      :beacon-name (get beacon-name
                                        (:mac-address row)
                                        (:mac-address row))})
              :mac-address))))

(defn get-obs-days
  "Fetches the observations from the last N days."
  [db-con n]
  (get-observations db-con
                    :where [:>= :recorded
                            (get-midnight-dt n)]))

(defn get-obs-interval
  "Fetches observations in an interval between the provided dates."
  [db-con dates]
  (get-by-interval get-observations
                   db-con
                   dates
                   :recorded))

(defn get-weather-observations
  "Fetches weather observations filtered by the provided SQL WHERE clause."
  [db-con & {:keys [where]}]
  (let [tz-offset (get-tz-offset (:display-timezone env))]
    (for [row (jdbc/execute! db-con
                             (sql/format {:select [:w.time
                                                   [:w.temperature
                                                    "fmi_temperature"]
                                                   :w.cloudiness
                                                   :w.wind_speed
                                                   :o.tb_image_name]
                                          :from [[:weather-data :w]]
                                          :join [[:observations :o]
                                                 [:= :w.obs_id :o.id]]
                                          :where where
                                          :order-by [[:w.id :asc]]})
                             rs-opts)]
      (merge row
             {:time (convert->epoch-ms tz-offset
                                       (:time row))}))))

(defn get-weather-obs-days
  "Fetches the weather observations from the last N days."
  [db-con n]
  (get-weather-observations db-con
                            :where [:>= :time
                                    (get-midnight-dt n)]))

(defn get-weather-obs-interval
  "Fetches weather observations in an interval between the provided dates."
  [db-con dates]
  (get-by-interval get-weather-observations
                   db-con
                   dates
                   :time))

(defn get-obs-date-interval
  "Fetches the date interval (start and end) of all observations."
  [db-con]
  (try
    (let [result (jdbc/execute-one! db-con
                                    (sql/format
                                     {:select [[:%min.recorded "start"]
                                               [:%max.recorded "end"]]
                                      :from :observations}))]
      (if (and (:start result)
               (:end result))
        {:start (t/format :iso-local-date (t/local-date-time (:start result)))
         :end (t/format :iso-local-date (t/local-date-time (:end result)))}
        result))
    (catch PSQLException pe
      (error pe "Observation date interval fetch failed")
      {:error :db-error})))

(defn get-ruuvitag-obs
  "Returns RuuviTag observations being between the provided timestamps
  and having the given name(s)."
  [db-con start end names]
  (try
    (let [nf (NumberFormat/getInstance)
          query (sql/format {:select [:recorded
                                      :name
                                      :temperature
                                      :humidity]
                             :from :ruuvitag_observations
                             :where [:and
                                     [:in :name names]
                                     [:>= :recorded start]
                                     [:<= :recorded end]]
                             :order-by [[:id :asc]]})
          tz-offset (get-tz-offset (:display-timezone env))]
      (.applyPattern ^DecimalFormat nf "0.0#")
      (for [row (jdbc/execute! db-con query rs-opts)]
        (merge row
               {:recorded (convert->epoch-ms tz-offset
                                             (:recorded row))
                :temperature (Float/parseFloat
                              (.format nf (:temperature row)))
                :humidity (Float/parseFloat
                           (.format nf (:humidity row)))})))
    (catch PSQLException pe
      (error pe "RuuviTag observation fetching failed")
      {})))

(defn get-elec-data-day
  "Returns the electricity price and consumption values per day inside the given
  time interval. If the end parameter is nil all the values after start will
  be returned."
  [db-con start end]
  (try
    (let [nf (NumberFormat/getInstance)
          end-val (or end
                      (when-let [dt (:date
                                     (jdbc/execute-one!
                                      db-con
                                      (sql/format {:select [[[:max :start_time]
                                                             :date]]
                                                   :from [:electricity_price]})
                                      rs-opts))]
                        (add-tz-offset-to-dt (t/local-date-time dt))))]
      (.applyPattern ^DecimalFormat nf "0.0#")
      (if-not end-val
        [nil]
        (for [date (take (inc (t/time-between (t/local-date start)
                                              (t/local-date end-val)
                                              :days))
                         (t/iterate t/plus (t/local-date start) (t/days 1)))]
          (let [query (sql/format {:select [[:%sum.consumption :consumption]
                                            [:%avg.price :price]]
                                   :from [[:electricity_price :p]]
                                   :left-join [[:electricity_consumption :u]
                                               [:= :p.start_time :u.time]]
                                   :where [:and
                                           [:>= :p.start_time
                                            (make-local-dt date "start")]
                                           [:<= :p.start_time
                                            (make-local-dt date "end")]]})
                result (jdbc/execute-one! db-con query rs-opts)]
            (when (:price result)
              (merge result
                     {:date (t/format :iso-local-date date)
                      :price (when (:price result)
                               (Float/parseFloat
                                (.format nf (:price result))))
                      :consumption (when (:consumption result)
                                     (Float/parseFloat
                                      (.format nf (:consumption result))))}))))))
    (catch PSQLException pe
      (error pe "Daily electricity data fetching failed")
      [nil])))

(defn get-elec-data-hour
  "Returns the electricity price and consumption values per hour inside the given
  time interval. If the end parameter is nil all the values after start will
  be returned."
  [db-con start end]
  (try
    (let [query (sql/format {:select [:p.start_time
                                      :p.price
                                      :u.consumption]
                             :from [[:electricity_price :p]]
                             :left-join [[:electricity_consumption :u]
                                         [:= :p.start_time :u.time]]
                             :where (if end
                                      [:and
                                       [:>= :p.start_time start]
                                       [:<= :p.start_time end]]
                                      [:>= :p.start_time start])
                             :order-by [[:p.start_time :asc]]})
          rows (jdbc/execute! db-con query rs-opts)]
      (when (pos? (count rows))
        (for [row rows]
          (merge row
                 {:start-time (-convert-to-iso8601-str (:start-time row))}))))
    (catch PSQLException pe
      (error pe "Hourly electricity data fetching failed")
      nil)))

(defn get-month-avg-elec-price
  "Returns the average electricity price for the current month."
  [db-con]
  (try
    (let [today (t/local-date)
          month-start (t/minus today (t/days (dec (t/as today :day-of-month))))
          query (sql/format {:select [:%avg.price]
                             :from :electricity_price
                             :where [[:>= :start_time
                                      (make-local-dt (str month-start) "start")]]})
          result (:avg (jdbc/execute-one! db-con query rs-opts))]
      (when result
        (let [nf (NumberFormat/getInstance)]
          (.applyPattern ^DecimalFormat nf "0.0#")
          (.format nf result))))
    (catch PSQLException pe
      (error pe "Monthly average electricity price fetching failed")
      nil)))

(defn get-last-obs-id
  "Returns the ID of the last observation."
  [db-con]
  (:id (jdbc/execute-one! db-con
                          (sql/format {:select [[:%max.id "id"]]
                                       :from :observations})
                          rs-opts)))

(defn insert-tb-image-name
  "Saves a Testbed image name and associates it with given observation ID.
  Returns true on success and false otherwise."
  [db-con obs-id image-name]
  (= 1 (:next.jdbc/update-count (js/update! db-con
                                            :observations
                                            {:tb_image_name image-name}
                                            ["id = ?" obs-id]
                                            rs-opts))))

(defn insert-elec-consumption-data
  "Inserts given electricity consumption data."
  [db-con consumption-data]
  (jdbc/with-transaction [tx db-con]
    (try
      (let [res (js/insert-multi! tx
                                  :electricity_consumption
                                  [:time :consumption]
                                  consumption-data
                                  rs-opts)]
        (if (= (count consumption-data) (count res))
          true
          (do
            (.rollback tx)
            false)))
      (catch PSQLException pe
        (error pe "Electricity consumption data insert failed")
        (.rollback tx)
        false))))

(defn get-latest-elec-consumption-record-time
  "Returns the time of the latest electricity consumption record."
  [db-con]
  (try
    (when-let [time (:time
                     (jdbc/execute-one!
                      db-con
                      (sql/format {:select [[[:max :time] :time]]
                                   :from [:electricity_consumption]})
                      rs-opts))]
      (t/format
       "d.L.Y HH:mm"
       (add-tz-offset-to-dt (t/local-date-time time))))
    (catch PSQLException pe
      (error pe "Electricity consumption latest consumption date fetch failed")
      nil)))

(defn get-elec-consumption-interval-start
  "Fetches the date interval start of electricity consumption data."
  [db-con]
  (try
    (let [result (jdbc/execute-one! db-con
                                    (sql/format
                                     {:select [[:%min.time "start"]]
                                      :from :electricity_consumption}))]
      (when (:start result)
        (t/format :iso-local-date (t/local-date-time (:start result)))))
    (catch PSQLException pe
      (error pe "Electricity consumption date interval start fetch failed")
      nil)))

(defn get-elec-price-interval-end
  "Fetches the date interval end of electricity price data."
  [db-con]
  (try
    (let [result (jdbc/execute-one! db-con
                                    (sql/format
                                     {:select [[:%max.start_time "end"]]
                                      :from :electricity_price}))]
      (when (:end result)
        (let [end-dt (add-tz-offset-to-dt (t/local-date-time (:end result)))]
          ;; Remove one hour to get rid of the last value (midnight) which ends up
          ;; on the following day
          (t/format :iso-local-date (t/minus end-dt (t/hours 1))))))
    (catch PSQLException pe
      (error pe "Electricity price date interval end fetch failed")
      nil)))
