(ns env-logger.db
  "Namespace containing the application's database function"
  (:require [clojure.string :as s]
            [config.core :refer [env]]
            [taoensso.timbre :refer [error info]]
            [next.jdbc :as jdbc]
            [next.jdbc
             [result-set :as rs]
             [sql :as js]]
            [java-time.api :as t])
  (:import (java.text NumberFormat
                      DecimalFormat)
           (java.time DateTimeException
                      LocalDateTime
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
        ;; ref-dt < image-dt results in DateTimeException
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
                       (.getTime (new Date))) 1000) 60) 60))

(defn convert-to-epoch-ms
  "Converts the given datetime value to Unix epoch time in milliseconds."
  [tz-offset dt]
  (let [^LocalDateTime subs-dt (t/minus (t/local-date-time dt)
                                        (t/hours tz-offset))]
    (* (.toEpochSecond subs-dt
                       (ZoneOffset/UTC))
       1000)))

(defn -convert-to-iso8601-str
  "Converts a ZonedDateTime or a java.sql.Timestamp object to a ISO 8601
  formatted datetime string."
  [datetime]
  (s/replace (str (first (s/split (str (t/instant datetime))
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
                    :tb_image_name (get-tb-image db-con)}
                   rs-opts)))

(defn insert-beacons
  "Insert one or more beacons into the beacons table."
  [db-con obs-id observation]
  (for [beacon (:beacons observation)]
    (:id (js/insert! db-con
                     :beacons
                     {:obs_id obs-id
                      :mac_address (:mac beacon)
                      :rssi (:rssi beacon)}
                     rs-opts))))

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

(defn insert-ruuvitag-observation
  "Insert a RuuviTag weather observation into the database."
  [db-con observation]
  (try
    (let [values {:location (:location observation)
                  :temperature (:temperature observation)
                  :pressure (:pressure observation)
                  :humidity (:humidity observation)
                  :battery_voltage (:battery_voltage observation)
                  :rssi (:rssi observation)}]
      (:id (js/insert! db-con
                       :ruuvitag_observations
                       (if (:timestamp observation)
                         (assoc values
                                :recorded
                                (t/sql-timestamp
                                 (t/minus (t/zoned-date-time
                                           (:timestamp
                                            observation))
                                          (t/hours (get-tz-offset
                                                    (:store-timezone env))))))
                         values)
                       rs-opts)))
    (catch PSQLException pe
      (error pe "RuuviTag observation insert failed")
      -1)))

(defn insert-observation
  "Inserts a observation to the database."
  [db-con observation]
  (if (= 4 (count observation))
    (jdbc/with-transaction [tx db-con]
      (try
        (let [obs-id (insert-plain-observation tx
                                               observation)]
          (when (pos? obs-id)
            (if (every? pos?
                        (insert-beacons tx obs-id observation))
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
    (not (nil? (re-find #"\d{4}-\d{1,2}-\d{1,2}" date)))))

(defn make-local-dt
  "Creates SQL datetime in local time from the provided date string.
  Mode is either start or end."
  [date mode]
  (t/local-date-time (format "%sT%s" date (if (= mode "start")
                                            "00:00:00"
                                            "23:59:59"))))

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
                             :b.mac_address
                             :b.rssi
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
        beacon-names (:beacon-name env)
        tz-offset (get-tz-offset (:display-timezone env))]
    (for [row (jdbc/execute! db-con
                             (sql/format limit-query)
                             rs-opts)]
      (dissoc (merge row
                     {:recorded (convert-to-epoch-ms tz-offset
                                                     (:recorded row))
                      :weather-recorded (when (:weather-recorded row)
                                          (convert-to-epoch-ms
                                           tz-offset
                                           (:weather-recorded row)))
                      :name (get beacon-names
                                 (:mac-address row)
                                 (:mac-address row))})
              :mac-address))))

(defn get-obs-days
  "Fetches the observations from the last N days."
  [db-con n]
  (get-observations db-con
                    :where [:>= :recorded
                            (t/minus (t/local-date-time)
                                     (t/days n))]))

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
             {:time (convert-to-epoch-ms tz-offset
                                         (:time row))}))))

(defn get-weather-obs-days
  "Fetches the weather observations from the last N days."
  [db-con n]
  (get-weather-observations db-con
                            :where [:>= :time
                                    (t/minus (t/local-date-time)
                                             (t/days n))]))

(defn get-weather-obs-interval
  "Fetches weather observations in an interval between the provided dates."
  [db-con dates]
  (get-by-interval get-weather-observations
                   db-con
                   dates
                   :time))

(defn get-obs-date-interval
  "Fetches the interval (start and end) of all observations."
  [db-con]
  (try
    (let [result (jdbc/execute-one! db-con
                                    (sql/format
                                     {:select [[:%min.recorded "start"]
                                               [:%max.recorded "end"]]
                                      :from :observations}))]
      (if (and (:start result)
               (:end result))
        {:start (t/format (t/formatter :iso-local-date)
                          (t/local-date-time (:start result)))
         :end (t/format (t/formatter :iso-local-date)
                        (t/local-date-time (:end result)))}
        result))
    (catch PSQLException pe
      (error pe "Observation interval fetch failed")
      {:error :db-error})))

(defn get-ruuvitag-obs
  "Returns RuuviTag observations being between the provided timestamps
  and having the given location(s)."
  [db-con start end locations]
  (try
    (let [nf (NumberFormat/getInstance)
          query (sql/format {:select [:recorded
                                      :location
                                      :temperature
                                      :humidity]
                             :from :ruuvitag_observations
                             :where [:and
                                     [:in :location locations]
                                     [:>= :recorded start]
                                     [:<= :recorded end]]
                             :order-by [[:id :asc]]})
          tz-offset (get-tz-offset (:display-timezone env))]
      (.applyPattern ^DecimalFormat nf "0.0#")
      (for [row (jdbc/execute! db-con query rs-opts)]
        (merge row
               {:recorded (convert-to-epoch-ms tz-offset
                                               (:recorded row))
                :temperature (Float/parseFloat
                              (. nf format (:temperature row)))
                :humidity (Float/parseFloat
                           (. nf format (:humidity row)))})))
    (catch PSQLException pe
      (error pe "RuuviTag observation fetching failed")
      {})))

(defn get-elec-price
  "Returns the electricity price values inside the given time interval.
  If the end parameter is nil all the values after start will be returned."
  [db-con start end]
  (try
    (let [query (sql/format {:select [:start_time
                                      :price]
                             :from :electricity_price
                             :where (if end
                                      [:and
                                       [:>= :start_time start]
                                       [:<= :start_time end]]
                                      [:>= :start_time start])
                             :order-by [[:id :asc]]})
          rows (jdbc/execute! db-con query rs-opts)]
      (when (pos? (count rows))
        (for [row rows]
          (merge row
                 {:start-time (-convert-to-iso8601-str (:start-time row))}))))
    (catch PSQLException pe
      (error pe "Electricity price fetching failed")
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
