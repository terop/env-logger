(ns env-logger.db
  "Namespace containing the application's database function"
  (:require [clojure.java.jdbc :as j]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [env-logger.config :refer :all]
            [honeysql
             [core :as sql]
             [helpers :refer :all]]
            [java-time :as t])
  (:import java.text.NumberFormat
           (java.time DateTimeException
                      ZoneOffset)
           (java.util Date
                      TimeZone)
           org.postgresql.util.PSQLException))

(let [db-host (get (System/getenv)
                   "POSTGRESQL_DB_HOST"
                   (db-conf :host))
      db-port (get (System/getenv)
                   "POSTGRESQL_DB_PORT"
                   (db-conf :port))
      db-name (get (System/getenv)
                   "POSTGRESQL_DB_NAME"
                   (db-conf :name))
      db-user (get (System/getenv)
                   "POSTGRESQL_DB_USERNAME"
                   (db-conf :username))
      db-password (get (System/getenv)
                       "POSTGRESQL_DB_PASSWORD"
                       (db-conf :password))]
  (def postgres {:classname "org.postgresql.Driver"
                 :subprotocol "postgresql"
                 :subname (format "//%s:%s/%s"
                                  db-host db-port db-name)
                 :user db-user
                 :password db-password}))

(def yc-image-pattern
  #"yc-(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:?\+\d{2}(:?:\d{2})?|Z)).+")

(defn test-db-connection
  "Tests the connection to the DB."
  [db-con]
  (try
    (j/query db-con
             "SELECT 1")
    true
    (catch PSQLException pe
      (log/error "DB connection establishment failed:"
                 (.getMessage pe))
      false)))

(defn yc-image-age-check
  "Returns true when the following condition is true:
  (yardcam datetime - reference datetime) <= diff-minutes
  and false otherwise. Also return true if
  (yardcam datetime - reference datetime) < 0."
  [yc-image ref-dt diff-minutes]
  (let [match (re-find yc-image-pattern
                       yc-image)
        yc-dt (t/zoned-date-time (t/formatter :iso-offset-date-time)
                                 (nth match 1))]
    (try
      (<= (t/as (t/interval yc-dt ref-dt) :minutes)
          diff-minutes)
      (catch DateTimeException dte
        ;; ref-dt < yc-dt results in DateTimeException
        true))))

(defn get-yc-image
  "Returns the name of the latest yardcam image."
  [db-con]
  (let [image-name (:image_name (first (j/query db-con
                                                (sql/format
                                                 (sql/build
                                                  :select [:image_name]
                                                  :from [:yardcam_image])))))]
    (when (and image-name
               (re-matches yc-image-pattern image-name)
               (yc-image-age-check image-name
                                   (t/zoned-date-time)
                                   (get-conf-value :yc-max-time-diff)))
      image-name)))

(defn get-tz-offset
  "Returns the offset in hours to UTC for the given timezone."
  [tz]
  (/ (/ (/ (.getOffset (TimeZone/getTimeZone tz)
                       (.getTime (new Date))) 1000) 60) 60))

(defn insert-plain-observation
  "Insert a row into observations table."
  [db-con observation]
  (:id (first (j/insert! db-con
                         :observations
                         {:recorded (t/sql-timestamp
                                     (t/minus (t/zoned-date-time
                                               (:timestamp observation))
                                              (t/hours (get-tz-offset
                                                        (get-conf-value
                                                         :store-timezone)))))
                          :temperature (- (:insideTemperature
                                           observation)
                                          (:offset observation))
                          :brightness (:insideLight observation)
                          :yc_image_name (:image-name observation)
                          :outside_temperature (:outsideTemperature
                                                observation)}))))

(defn insert-beacons
  "Insert one or more beacons into the beacons table."
  [db-con obs-id observation]
  (for [beacon (:beacons observation)]
    (:id (first (j/insert! db-con
                           :beacons
                           {:obs_id obs-id
                            :mac_address (:mac beacon)
                            :rssi (:rssi beacon)})))))

(defn insert-wd
  "Insert a FMI weather observation into the database."
  [db-con obs-id weather-data]
  (:id (first (j/insert! db-con
                         :weather_data
                         {:obs_id obs-id
                          :time (t/sql-timestamp
                                 (t/minus (t/local-date-time
                                           (:date weather-data))
                                          (t/hours (get-tz-offset
                                                    (get-conf-value
                                                     :store-timezone)))))
                          :temperature (:temperature weather-data)
                          :cloudiness (:cloudiness weather-data)}))))

(defn insert-ruuvitag-observation
  "Insert a RuuviTag weather observation into the database."
  [db-con observation]
  (try
    (let [values {:location (:location observation)
                  :temperature (:temperature observation)
                  :pressure (:pressure observation)
                  :humidity (:humidity observation)
                  :battery_voltage (:battery_voltage observation)}]
      (:id (first (j/insert! db-con
                             :ruuvitag_observations
                             (if (:timestamp observation)
                               (assoc values
                                      :recorded
                                      (t/sql-timestamp
                                       (t/minus (t/zoned-date-time
                                                 (:timestamp
                                                  observation))
                                                (t/hours (get-tz-offset
                                                          (get-conf-value
                                                           :store-timezone))))))
                               values)))))
    (catch PSQLException pe
      (log/error "RuuviTag observation insert failed:"
                 (.getMessage pe))
      -1)))

(defn insert-observation
  "Inserts a observation to the database. Optionally corrects the temperature
  with an offset."
  [db-con observation]
  (if (= 6 (count observation))
    (j/with-db-transaction [t-con db-con]
      (try
        (let [offset (if (get-conf-value :correction :k :enabled)
                       (get-conf-value :correction :k :offset) 0)
              obs-id (insert-plain-observation t-con
                                               (merge observation
                                                      {:offset offset
                                                       :image-name
                                                       (get-yc-image t-con)}))]
          (if (pos? obs-id)
            (if (every? pos?
                        (insert-beacons t-con obs-id observation))
              (let [weather-data (:weather-data observation)]
                (if (nil? weather-data)
                  true
                  (if (pos? (insert-wd t-con obs-id weather-data))
                    true
                    (do
                      (log/info (str "Database insert: rolling back "
                                     "transaction after weather data insert"))
                      (j/db-set-rollback-only! t-con)
                      false))))
              (do
                (log/info (str "Database insert: rolling back "
                               "transaction after beacon scan insert"))
                (j/db-set-rollback-only! t-con)
                false))))
        (catch PSQLException pe
          (log/error "Database insert failed:"
                     (.getMessage pe))
          (j/db-set-rollback-only! t-con)
          false)))
    false))

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
  (let [base-query {:select [:o.brightness
                             :o.temperature
                             :o.recorded
                             [:w.temperature "fmi_temperature"]
                             :w.cloudiness
                             :o.yc_image_name
                             [:o.outside_temperature "o_temperature"]
                             :b.mac_address
                             :b.rssi
                             :o.tb_image_name]
                    :from [[:observations :o]]
                    :left-join [[:weather-data :w]
                                [:= :o.id :w.obs_id]
                                [:beacons :b]
                                [:= :o.id :b.obs_id]]}
        where-query (if where
                      (sql/build base-query :where where)
                      base-query)
        limit-query (if limit
                      (sql/build where-query :limit limit
                                 :order-by [[:o.id :desc]])
                      (sql/build where-query :order-by [[:o.id :asc]]))
        beacon-names (get-conf-value :beacon-name)
        tz-offset (get-tz-offset (get-conf-value :display-timezone))]
    (j/query db-con
             (sql/format limit-query)
             {:row-fn #(dissoc
                        (merge %
                               {:recorded (* (.toEpochSecond
                                              (t/minus (t/local-date-time
                                                        (:recorded %))
                                                       (t/hours tz-offset))
                                              (ZoneOffset/UTC))
                                             1000)
                                :name (get beacon-names
                                           (:mac_address %)
                                           (:mac_address %))
                                :temp_delta (when (and (:fmi_temperature %)
                                                       (:o_temperature %))
                                              (Float/parseFloat
                                               (format "%.2f"
                                                       (- (:o_temperature %)
                                                          (:fmi_temperature
                                                           %)))))
                                :yc_image_name (if (:yc_image_name %)
                                                 (if (yc-image-age-check
                                                      (:yc_image_name %)
                                                      (:recorded %)
                                                      (get-conf-value
                                                       :yc-max-time-diff))
                                                   (:yc_image_name %)
                                                   nil)
                                                 nil)})
                        :mac_address)})))

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
  (let [tz-offset (get-tz-offset (get-conf-value :display-timezone))]
    (j/query db-con
             (sql/format (sql/build :select [:w.time
                                             [:w.temperature "fmi_temperature"]
                                             :w.cloudiness
                                             [:o.outside_temperature
                                              "o_temperature"]
                                             :o.tb_image_name]
                                    :from [[:weather-data :w]]
                                    :join [[:observations :o]
                                           [:= :w.obs_id :o.id]]
                                    :where where
                                    :order-by [[:w.id :asc]]))
             {:row-fn #(merge %
                              {:time (* (.toEpochSecond
                                         (t/minus (t/local-date-time
                                                   (:time %))
                                                  (t/hours tz-offset))
                                         (ZoneOffset/UTC))
                                        1000)
                               :temp_delta (when (and (:fmi_temperature %)
                                                      (:o_temperature %))
                                             (Float/parseFloat
                                              (format "%.2f"
                                                      (- (:o_temperature %)
                                                         (:fmi_temperature
                                                          %)))))})})))


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
    (let [result (j/query db-con
                          (sql/format
                           (sql/build :select [[:%min.recorded "start"]
                                               [:%max.recorded "end"]]
                                      :from :observations)))]
      (if (= 1 (count result))
        {:start (t/format (t/formatter :iso-local-date)
                          (t/local-date-time (:start (first result))))
         :end (t/format (t/formatter :iso-local-date)
                        (t/local-date-time (:end (first result))))}
        {:start ""
         :end ""}))
    (catch PSQLException pe
      (log/error "Observation interval fetch failed:"
                 (.getMessage pe))
      {:error :db-error})))

(defn get-ruuvitag-obs
  "Returns RuuviTag observations lying between the provided timestamps
  and having the given location(s)."
  [db-con start end locations]
  (try
    (let [nf (NumberFormat/getInstance)
          query (sql/format (sql/build :select [:recorded
                                                :location
                                                :temperature
                                                :humidity]
                                       :from :ruuvitag_observations
                                       :where [:and
                                               [:in :location locations]
                                               [:>= :recorded start]
                                               [:<= :recorded end]]
                                       :order-by [[:id :asc]]))
          tz-offset (get-tz-offset (get-conf-value :display-timezone))]
      (.applyPattern nf "0.0#")
      (j/query db-con query
               {:row-fn (fn [obs]
                          {:location (:location obs)
                           :recorded (* (.toEpochSecond
                                         (t/minus (t/local-date-time
                                                   (:recorded obs))
                                                  (t/hours tz-offset))
                                         (ZoneOffset/UTC))
                                        1000)
                           :temperature (Float/parseFloat
                                         (. nf format (:temperature obs)))
                           :humidity (Float/parseFloat
                                      (. nf format (:humidity obs)))})}))
    (catch PSQLException pe
      (log/error "RuuviTag observation fetching failed:"
                 (.getMessage pe))
      {})))

(defn insert-yc-image-name
  "Stores the name of the latest yardcam image. Rows from the table are
  deleted before a new row is inserted. Returns true on success and
  false otherwise."
  [db-con image-name]
  (let [result (j/query db-con
                        (sql/format (sql/build :select [:image_id]
                                               :from :yardcam_image)))]
    (try
      (when (pos? (count result))
        (j/execute! db-con "DELETE FROM yardcam_image"))
      (= 1 (count (j/insert! db-con
                             :yardcam_image
                             {:image_name image-name})))
      (catch PSQLException pe
        (log/error "Yardcam image name insert failed:"
                   (.getMessage pe))
        false))))

(defn get-last-obs-id
  "Returns the ID of the last observation."
  [db-con]
  (first (j/query db-con
                  (sql/format (sql/build :select [[:%max.id "id"]]
                                         :from :observations))
                  {:row-fn #(:id %)})))

(defn insert-tb-image-name
  "Saves a Testbed image name and associates it with given observation ID.
  Returns true on success and false otherwise."
  [db-con obs-id image-name]
  (= 1 (first (j/update! db-con
                         :observations
                         {:tb_image_name image-name}
                         ["id = ?" obs-id]))))
