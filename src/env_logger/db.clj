(ns env-logger.db
  "Namespace containing the application's database function"
  (:require [clojure.string :as str]
            [config.core :refer [env]]
            [taoensso.timbre :refer [error info]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as js]
            [java-time.api :as jt])
  (:import java.sql.Connection
           (java.text DecimalFormat)
           (java.time DateTimeException
                      LocalDateTime
                      YearMonth
                      ZoneId
                      ZoneOffset)
           (java.util Date
                      TimeZone)
           org.postgresql.util.PSQLException))
(refer-clojure :exclude '[distinct filter for group-by into partition-by set update])
(require '[honey.sql :as sql])

(defn db-conf
  "Returns the value of the requested database configuration key"
  [k]
  (k (:database env)))

(defn get-db-password
  "Returns the database password."
  []
  (let [pwd-file (System/getenv "POSTGRESQL_DB_PASSWORD_FILE")]
    (try
      (if pwd-file
        (str/trim (slurp pwd-file))
        (or (db-conf :password) (error "No database password available")))
      (catch java.io.FileNotFoundException ex
        (error ex "Database password file not found")))))

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
      db-password (get-db-password)]
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
(def df-inst (DecimalFormat. "0.0#"))

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
        image-dt (jt/zoned-date-time (jt/formatter :iso-offset-date-time)
                                     (nth match 1))]
    (try
      (<= (jt/as (jt/interval image-dt ref-dt) :minutes)
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
                                            :where [:is-not :tb_image_name nil]
                                            :order-by [[:id :desc]]
                                            :limit [1]})
                                          rs-opts))]
    (when (and tb-image-name
               (image-age-check tb-image-name
                                (jt/zoned-date-time)
                                (:image-max-time-diff env)))
      tb-image-name)))

(defn get-tz-offset
  "Returns the offset in hours to UTC for the given timezone."
  [tz]
  (/ (/ (/ (TimeZone/.getOffset (TimeZone/getTimeZone tz)
                                (Date/.getTime (Date.))) 1000) 60) 60))

(defn get-midnight-dt
  "Returns a LocalDateTime at midnight with N days subtracted from the current
  date and time."
  [n-days]
  (let [ldt (jt/local-date-time)]
    (jt/minus (jt/minus ldt
                        (jt/days n-days)
                        (jt/hours (LocalDateTime/.getHour ldt))
                        (jt/minutes (LocalDateTime/.getMinute ldt))
                        (jt/seconds (LocalDateTime/.getSecond ldt))
                        (jt/nanos (LocalDateTime/.getNano ldt)))
             ;; Correct generated datetime value when UTC is used as display
             ;; timezone
              (jt/hours (if (= (:display-timezone env) "UTC")
                          (get-tz-offset (:store-timezone env))
                          0)))))

(defn convert->epoch-ms
  "Converts the given datetime value to Unix epoch time in milliseconds."
  [tz-offset dt]
  (let [^LocalDateTime subs-dt (jt/minus (jt/local-date-time dt)
                                         (jt/hours tz-offset))]
    (* (LocalDateTime/.toEpochSecond subs-dt ZoneOffset/UTC)
       1000)))

(defn convert-time->iso8601-str
  "Converts a ZonedDateTime or a java.sql.Timestamp object to a ISO 8601
  formatted datetime string."
  [datetime]
  (str/replace (str (first (str/split (str (jt/instant datetime))
                                      #"\.\d+"))
                    (if (not= java.sql.Timestamp (type datetime))
                      "Z" ""))
               "ZZ" "Z"))

(defn round-number
  "Rounds the given number up to two decimals."
  [number]
  (Float/parseFloat (DecimalFormat/.format df-inst number)))

(defn- transform-row->column
  "Do a row to column transform of the given iterable containing maps
  as its content."
  [rows]
  (let [keys (keys (first rows))]
    (into {} (map (fn [k] [k (mapv k rows)]) keys))))

(defn insert-plain-observation
  "Insert a row into observations table."
  [db-con observation]
  (:id (js/insert! db-con
                   :observations
                   {:recorded (jt/sql-timestamp
                               (jt/minus (jt/zoned-date-time
                                          (:timestamp observation))
                                         (jt/hours (get-tz-offset
                                                    (:store-timezone env)))))
                    :tb_image_name (get-tb-image db-con)
                    :inside_light (:insideLight observation)
                    :inside_temperature (:insideTemperature observation)
                    :outside_temperature (:outsideTemperature observation)
                    :outside_light (:outsideLight observation)
                    :co2 (:co2 observation)
                    :voc_index (:vocIndex observation)
                    :nox_index (:noxIndex observation)}
                   rs-opts)))

(defn insert-beacon
  "Insert a beacon into the beacons table."
  [db-con obs-id observation]
  (let [beacon (:beacon observation)]
    (if (and (seq (:mac beacon))
             (> (count (:mac beacon)) 16)
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

(defn insert-ruuvi-device-observations
  "Insert one or more Ruuvi device observations into the database."
  [db-con timestamp observations]
  (try
    (let [insert-fn
          (fn [observation]
            (let [type (:type observation)
                  values (if (= type "tag")
                           {:name (:name observation)
                            :temperature (:temperature observation)
                            :pressure (:pressure observation)
                            :humidity (:humidity observation)
                            :battery_voltage (:battery_voltage observation)
                            :rssi (:rssi observation)}
                           {:name (:name observation)
                            :co2 (:co2 observation)
                            :nox (:nox observation)
                            :voc (:voc observation)
                            :pm_2_5 (:pm_2_5 observation)
                            :iaqs (:iaqs observation)})
                  table-name (if (= type "tag")
                               :ruuvitag_observations
                               :ruuvi_air_observations)]
              (:id (js/insert! db-con
                               table-name
                               (if timestamp
                                 (assoc values
                                        :recorded
                                        (jt/sql-timestamp
                                         (jt/minus (jt/zoned-date-time
                                                    timestamp)
                                                   (jt/hours (get-tz-offset
                                                              (:store-timezone
                                                               env))))))
                                 values)
                               rs-opts))))]
      (every? pos? (map insert-fn observations)))
    (catch PSQLException pe
      (error pe "Ruuvi device observation insert failed")
      false)))

(defn insert-observation
  "Inserts a observation to the database."
  [db-con observation]
  (if (>= (count observation) 9)
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
                      (Connection/.rollback tx)
                      false))))
              (do
                (info (str "Database insert: rolling back "
                           "transaction after beacon scan insert"))
                (Connection/.rollback tx)
                false))))
        (catch PSQLException pe
          (error pe "Database insert failed")
          (Connection/.rollback tx)
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
  (jt/minus (jt/local-date-time (format "%sT%s"
                                        date
                                        (if (= mode "start")
                                          "00:00:00"
                                          "23:59:59")))
           ;; Correct generated datetime value when UTC is used as display
           ;; timezone
            (jt/hours (if (= (:display-timezone env) "UTC")
                        (get-tz-offset (:store-timezone env))
                        0))))

(defn add-tz-offset-to-dt
  "Add the TZ offset of the 'storing timezone' to the provided datetime if the
 system has different timezone than the 'storing timezone'."
  [dt]
  (if-not (= (ZoneId/systemDefault)
             (jt/zone-id (:store-timezone env)))
    (jt/plus dt
             (jt/hours (get-tz-offset (:store-timezone env))))
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
                         (jt/local-date-time 2010 1 1))
             ~end-dt (if (:end ~dates)
                       (make-local-dt (:end ~dates) "end")
                       (jt/local-date-time))]
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
                             :o.inside_light
                             :o.inside_temperature
                             :o.co2
                             :o.outside_temperature
                             :b.mac_address
                             [:b.rssi "beacon_rssi"]
                             [:b.battery_level "beacon_battery"]
                             :o.tb_image_name]
                    :from [[:observations :o]]
                    :left-join [[:beacons :b]
                                [:= :o.id :b.obs_id]]}
        where-query (if where
                      (assoc base-query :where where)
                      base-query)
        limit-query (if limit
                      (merge where-query {:limit limit
                                          :order-by [[:o.id :desc]]})
                      (assoc where-query :order-by [[:o.id :asc]]))
        beacon-name (:beacon-name env)
        tz-offset (get-tz-offset (:display-timezone env))
        results (transform-row->column (jdbc/execute! db-con
                                                      (sql/format limit-query)
                                                      rs-opts))
        updated-recorded (map #(convert->epoch-ms tz-offset %) (:recorded results))
        updated-beacon (map #(get beacon-name % %) (:mac-address results))]
    (as-> results res
      (assoc res :recorded updated-recorded)
      (assoc res :beacon-name updated-beacon)
      (assoc res :co2 (:co-2 res))
      (dissoc res :co-2 :mac-address))))

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
        {:start (jt/format :iso-local-date (jt/local-date-time (:start result)))
         :end (jt/format :iso-local-date (jt/local-date-time (:end result)))}
        result))
    (catch PSQLException pe
      (error pe "Observation date interval fetch failed")
      {:error :db-error})))

(defn get-weather-observations
  "Fetches FMI weather observations optionally filtered by a provided SQL WHERE clause.
  Limiting rows is possible by providing row count with the :limit argument."
  [db-con & {:keys [where limit]
             :or {where nil
                  limit nil}}]
  (let [base-query {:select [:time
                             :temperature
                             :cloudiness
                             :wind_speed]
                    :from [:weather_data]}
        where-query (if where
                      (assoc base-query :where where)
                      base-query)
        limit-query (if limit
                      (merge where-query {:limit limit
                                          :order-by [[:time :desc]]})
                      (assoc where-query :order-by [[:time :asc]]))
        tz-offset (get-tz-offset (:display-timezone env))
        results (transform-row->column (jdbc/execute! db-con
                                                      (sql/format limit-query)
                                                      rs-opts))
        updated-time (map #(convert->epoch-ms tz-offset %) (:time results))]
    (assoc results :time updated-time)))

(defn get-weather-days
  "Fetches the weather observations from the last N days."
  [db-con n]
  (get-weather-observations db-con
                            :where [:>= :time
                                    (get-midnight-dt n)]))

(defn get-weather-interval
  "Fetches weather observations in an interval between the provided dates."
  [db-con dates]
  (get-by-interval get-weather-observations
                   db-con
                   dates
                   :time))

(defn get-ruuvitag-obs
  "Returns RuuviTag observations being between the provided timestamps
  and having the given name(s)."
  [db-con start end names]
  (try
    (let [query (sql/format {:select [:recorded
                                      :name
                                      :temperature
                                      :humidity]
                             :from :ruuvitag_observations
                             :where [:and
                                     [:in :name names]
                                     [:>= :recorded start]
                                     [:<= :recorded end]]
                             :order-by [[:id :asc]]})
          tz-offset (get-tz-offset (:display-timezone env))
          results (transform-row->column (jdbc/execute! db-con query rs-opts))
          updated-recorded (map #(convert->epoch-ms tz-offset %) (:recorded results))]
      (assoc results :recorded updated-recorded))
    (catch PSQLException pe
      (error pe "RuuviTag observation fetch failed")
      {})))

(defn get-ruuvi-air-obs
  "Returns Ruuvi Air observations being between the provided timestamps."
  [db-con start end]
  (try
    (let [query (sql/format {:select [[:co2 "ruuvi_co2"]
                                      [:pm_2_5 "pm_25"]
                                      :iaqs]
                             :from :ruuvi_air_observations
                             :where [:and
                                     [:>= :recorded start]
                                     [:<= :recorded end]]
                             :order-by [[:id :asc]]})
          results (transform-row->column (jdbc/execute! db-con query rs-opts))]
      (as-> results res
        (assoc res :ruuvi-co2 (:ruuvi-co-2 res))
        (dissoc res :ruuvi-co-2)))
    (catch PSQLException pe
      (error pe "Ruuvi Air observation fetch failed")
      {})))

(defn get-elec-fees
  "Returns electricity fees (contract, transfer and tax) in cent per kWh."
  []
  (* (+ (:elec-contract-margin env)
        (:elec-tax env)
        (:elec-transfer-fee env)) 100))

(defn get-elec-data-day
  "Returns the average electricity price and consumption values per day inside
  the given time interval. If the end parameter is nil all the values after
  start will be returned."
  [db-con start end add-fees]
  (try
    (let [end-val (or end
                      (when-let [dt (:date
                                     (jdbc/execute-one!
                                      db-con
                                      (sql/format {:select [[[:max :start_time]
                                                             :date]]
                                                   :from [:electricity_price]})
                                      rs-opts))]
                        (add-tz-offset-to-dt (jt/local-date-time dt))))]
      (if-not end-val
        [nil]
        (for [date (take (inc (jt/time-between (jt/local-date start)
                                               (jt/local-date end-val)
                                               :days))
                         (jt/iterate jt/plus (jt/local-date start)
                                     (jt/days 1)))]
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
                     {:date (jt/format :iso-local-date date)
                      :price (when (:price result)
                               (round-number (if add-fees
                                               (+ (:price result) (get-elec-fees))
                                               (:price result))))
                      :consumption (when (:consumption result)
                                     (round-number (:consumption result)))}))))))
    (catch PSQLException pe
      (error pe "Daily electricity data fetch failed")
      [nil])))

(defn get-elec-data-hour
  "Returns the electricity price and consumption values per hour inside the given
  time interval. If the end parameter is nil all the values after start will
  be returned."
  [db-con start end add-fees]
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
        (let [fees (get-elec-fees)]
          (for [row rows]
            (merge row
                   {:price (round-number (if add-fees (+ (:price row) fees)
                                             (:price row)))
                    :start-time (convert-time->iso8601-str (:start-time row))})))))
    (catch PSQLException pe
      (error pe "Hourly electricity data fetch failed")
      nil)))

(defn get-elec-price-minute
  "Returns the electricity price values with 15 minute resolution for the given
  time interval."
  [db-con start end add-fees]
  (try
    (let [query (sql/format {:select [:p.start_time
                                      :p.price]
                             :from [[:electricity_price_minute :p]]
                             :where [:and
                                     [:>= :p.start_time start]
                                     [:<= :p.start_time end]]
                             :order-by [[:p.start_time :asc]]})
          rows (jdbc/execute! db-con query rs-opts)]
      (when (pos? (count rows))
        (let [fees (get-elec-fees)]
          (for [row rows]
            (merge row
                   {:price (round-number (if add-fees (+ (:price row) fees)
                                             (:price row)))
                    :start-time (convert-time->iso8601-str (:start-time row))})))))
    (catch PSQLException pe
      (error pe "Minute resolution electricity price fetch failed")
      nil)))

(defn get-month-avg-elec-price
  "Returns the average electricity price for the current month."
  [db-con add-fees]
  (try
    (let [today (jt/local-date)
          month-start (jt/minus today (jt/days (dec (jt/as today :day-of-month))))
          query (sql/format {:select [:%avg.price]
                             :from :electricity_price
                             :where [[:>= :start_time
                                      (make-local-dt (str month-start) "start")]]})
          result (:avg (jdbc/execute-one! db-con query rs-opts))]
      (when result
        (round-number (if add-fees (+ result (get-elec-fees)) result))))
    (catch PSQLException pe
      (error pe "Monthly average electricity price fetch failed")
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
            (Connection/.rollback tx)
            false)))
      (catch PSQLException pe
        (error pe "Electricity consumption data insert failed")
        (Connection/.rollback tx)
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
      (jt/format
       "d.L.Y HH:mm"
       (add-tz-offset-to-dt (jt/local-date-time time))))
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
        (jt/format :iso-local-date (jt/local-date-time (:start result)))))
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
        (let [end-dt (add-tz-offset-to-dt (jt/local-date-time (:end result)))]
          ;; Remove one hour to get rid of the last value (midnight) which ends
          ;; up on the following day
          (jt/format :iso-local-date (jt/minus end-dt (jt/hours 1))))))
    (catch PSQLException pe
      (error pe "Electricity price date interval end fetch failed")
      nil)))

(defn get-elec-price-minute-interval-start
  "Fetches the date interval start of 15 minute electricity price data."
  [db-con]
  (try
    (let [result (jdbc/execute-one! db-con
                                    (sql/format
                                     {:select [[:%min.start_time "start"]]
                                      :from :electricity_price_minute}))]
      (when (:start result)
        (jt/format :iso-local-date (jt/local-date-time (:start result)))))
    (catch PSQLException pe
      (error pe "15 minue electricity price date interval start fetch failed")
      nil)))

(defn get-month-elec-consumption
  "Returns the electricity consumption for the current month."
  [db-con]
  (try
    (let [today (jt/local-date)
          month-start (jt/minus today (jt/days (dec (jt/as today :day-of-month))))
          query (sql/format {:select [:%sum.consumption]
                             :from :electricity_consumption
                             :where [[:>= :time
                                      (make-local-dt (str month-start) "start")]]})
          result (:sum (jdbc/execute-one! db-con query rs-opts))]
      (when result
        (round-number result)))
    (catch PSQLException pe
      (error pe "Monthly electricity consumption fetch failed")
      nil)))

(defn get-interval-elec-cost
  "Returns electricity cost (electricity price and transfer) for a given
  interval. Note! Only works correctly across an two months or less interval."
  [db-con interval-start interval-end]
  (try
    (let [spot-cost
          (:price (jdbc/execute-one! db-con
                                     (sql/format {:select [[[:raw "COALESCE(SUM(p.price "
                                                             "* c.consumption), 0)"]
                                                            :price]]
                                                  :from [[:electricity_price :p]]
                                                  :join [[:electricity_consumption :c]
                                                         [:= :c.time :p.start_time]]
                                                  :where [:and
                                                          [:>= :c.time interval-start]
                                                          [:<= :c.time interval-end]]})
                                     rs-opts))
          total-cons (:cons (jdbc/execute-one! db-con
                                               (sql/format {:select [[[:raw "COALESCE("
                                                                       "SUM(consumption), 0)"]
                                                                      :cons]]
                                                            :from [:electricity_consumption]
                                                            :where [:and
                                                                    [:>= :time
                                                                     interval-start]
                                                                    [:<= :time
                                                                     interval-end]]})
                                               rs-opts))
          transfer-base-fee (:elec-transfer-base-fee env)
          contract-base-fee (:elec-contract-base-fee env)
          inter-month-interval? (not= (jt/as interval-start :month-of-year)
                                      (jt/as interval-end :month-of-year))
          month-one-day-fraction (when inter-month-interval?
                                   (let [days (YearMonth/.lengthOfMonth
                                               (YearMonth/of
                                                (jt/as interval-start
                                                       :year)
                                                (jt/as interval-start
                                                       :month-of-year)))]
                                     (/ (inc (- days (jt/as interval-start
                                                            :day-of-month)))
                                        days)))
          days-in-month (YearMonth/.lengthOfMonth (YearMonth/of
                                                   (jt/as interval-start :year)
                                                   (jt/as interval-start
                                                          :month-of-year)))
          interval-days (inc (jt/as (jt/duration interval-start
                                                 interval-end)
                                    :days))
          month-two-day-fraction (if inter-month-interval?
                                   (/ (jt/as interval-end :day-of-month)
                                      (YearMonth/.lengthOfMonth
                                       (YearMonth/of
                                        (jt/as interval-end
                                               :year)
                                        (jt/as interval-end
                                               :month-of-year))))
                                   (/ interval-days days-in-month))
          transfer-base-fee-part (if inter-month-interval?
                                   (+ (* month-one-day-fraction transfer-base-fee)
                                      (* month-two-day-fraction transfer-base-fee))
                                   (* month-two-day-fraction transfer-base-fee))
          contract-base-fee-part (if inter-month-interval?
                                   (+ (* month-one-day-fraction contract-base-fee)
                                      (* month-two-day-fraction contract-base-fee))
                                   (* month-two-day-fraction contract-base-fee))
          transfer-price (+ transfer-base-fee-part
                            (* (:elec-tax env) total-cons)
                            (* (:elec-transfer-fee env) total-cons))
          elec-price (+ contract-base-fee-part
                        (* (:elec-contract-margin env) total-cons)
                        (/ spot-cost 100))
          total-price (+ elec-price transfer-price)]
      (round-number total-price))
    (catch PSQLException pe
      (error pe "Electricity cost calculation failed")
      nil)))
