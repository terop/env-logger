(ns env-logger.db
  "Namespace containing the application's database function"
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.local :as l]
            [clj-time.jdbc]
            [clojure.java.jdbc :as j]
            [honeysql.core :as sql]
            [honeysql.helpers :refer :all]
            [clojure.tools.logging :as log]
            [env-logger.config :refer :all]))

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

(defn get-yc-image
  "Returns the name of the latest yardcam image."
  [db-con]
  (:image_name (first (j/query db-con
                               (sql/format
                                (sql/build
                                 :select [:image_name]
                                 :from [:yardcam_images]))))))

(defn insert-plain-observation
  "Insert a row into observations table."
  [db-con observation]
  (:id (first (j/insert! db-con
                         :observations
                         {:recorded (f/parse
                                     (:timestamp observation))
                          :temperature (- (:inside_temp
                                           observation)
                                          (:offset observation))
                          :brightness (:inside_light observation)
                          :yc_image_name (:image-name observation)
                          :outside_temperature (:outside_temp observation)}))))

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
                          :time (f/parse (:date weather-data))
                          :temperature (:temperature weather-data)
                          :cloudiness (:cloudiness weather-data)
                          :pressure (:pressure weather-data)}))))

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
                (if (zero? (count weather-data))
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
        (catch org.postgresql.util.PSQLException pge
          (log/error (str "Database insert failed, message:"
                          (.getMessage pge)))
          (j/db-set-rollback-only! t-con)
          false)))
    false))

(defn format-datetime
  "Changes the timezone and formats the datetime with the given formatter."
  [datetime formatter]
  (l/format-local-time (t/to-time-zone datetime (t/time-zone-for-id
                                                 (get-conf-value :timezone)))
                       formatter))

(defn validate-date
  "Checks if the given date is nil or if non-nil, it is in the dd.mm.yyyy
  or d.m.yyyy format."
  [date]
  (if (nil? date)
    true
    (not (nil? (re-find #"\d{1,2}\.\d{1,2}\.\d{4}" date)))))

(defn make-local-dt
  "Creates SQL datetime in local time from the provided date string.
  Mode is either start or end."
  [date mode]
  (l/to-local-date-time (f/parse (f/formatter "d.M.y H:m:s")
                                 (format "%s %s" date (if (= mode "start")
                                                        "00:00:00"
                                                        "23:59:59")))))

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
                         (t/date-time 2010 1 1))
             ~end-dt (if (:end ~dates)
                       (make-local-dt (:end ~dates) "end")
                       (t/now))]
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
                             :w.pressure
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
        beacon-names (get-conf-value :beacon-name)]
    (j/query db-con
             (sql/format limit-query)
             {:row-fn #(dissoc
                        (merge %
                               {:recorded (format-datetime
                                           (:recorded %)
                                           :date-hour-minute-second)
                                :name (get beacon-names
                                           (:mac_address %)
                                           (:mac_address %))
                                :temp_delta (when (and (:fmi_temperature %)
                                                       (:o_temperature %))
                                              (Float/parseFloat
                                               (format "%.2f"
                                                       (- (:o_temperature %)
                                                          (:fmi_temperature
                                                           %)))))})
                        :mac_address)})))

(defn get-obs-days
  "Fetches the observations from the last N days."
  [db-con n]
  (get-observations db-con
                    :where [:>= :recorded
                            (t/minus (t/now)
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
  (j/query db-con
           (sql/format (sql/build :select [:w.time
                                           [:w.temperature "fmi_temperature"]
                                           :w.cloudiness
                                           :w.pressure
                                           :o.yc_image_name
                                           [:o.outside_temperature
                                            "o_temperature"]
                                           :o.tb_image_name]
                                  :from [[:weather-data :w]]
                                  :join [[:observations :o]
                                         [:= :w.obs_id :o.id]]
                                  :where where
                                  :order-by [[:w.id :asc]]))
           {:row-fn #(merge %
                            {:time (format-datetime
                                    (:time %)
                                    :date-hour-minute-second)
                             :temp_delta (when (and (:fmi_temperature %)
                                                    (:o_temperature %))
                                           (Float/parseFloat
                                            (format "%.2f"
                                                    (- (:o_temperature %)
                                                       (:fmi_temperature
                                                        %)))))})}))


(defn get-weather-obs-days
  "Fetches the weather observations from the last N days."
  [db-con n]
  (get-weather-observations db-con
                            :where [:>= :time
                                    (t/minus (t/now)
                                             (t/days n))]))

(defn get-weather-obs-interval
  "Fetches weather observations in an interval between the provided dates."
  [db-con dates]
  (get-by-interval get-weather-observations
                   db-con
                   dates
                   :time))

(defn get-obs-start-date
  "Fetches the first date of all observations."
  [db-con]
  (try
    (let [formatter (f/formatter "d.M.y")
          result (j/query db-con
                          (sql/format
                           (sql/build :select [[:%min.recorded "start"]]
                                      :from :observations)))]
      (if (= 1 (count result))
        {:start (f/unparse formatter (:start (first result)))}
        {:start ""}))
    (catch org.postgresql.util.PSQLException pge
      (log/error (str "Observation start date fetching failed, message:"
                      (.getMessage pge)))
      {:error :db-error})))

(defn get-obs-end-date
  "Fetches the last date of all observations."
  [db-con]
  (try
    (let [formatter (f/formatter "d.M.y")
          result (j/query db-con
                          (sql/format
                           (sql/build :select [[:%max.recorded "end"]]
                                      :from :observations)))]
      (if (= 1 (count result))
        {:end (f/unparse formatter (:end (first result)))}
        {:end ""}))
    (catch org.postgresql.util.PSQLException pge
      (log/error (str "Observation end date fetching failed, message:"
                      (.getMessage pge)))
      {:error :db-error})))

(defn insert-yc-image-name
  "Stores the name of the latest Yardcam image. Rows from the table are
  deleted before a new row is inserted. Returns true on success and
  false otherwise."
  [db-con image-name]
  (let [result (j/query db-con
                        (sql/format (sql/build :select [:image_id]
                                               :from :yardcam_images)))]
    (when (pos? (count result))
      (j/execute! db-con "DELETE FROM yardcam_images"))
    (= 1 (count (j/insert! db-con
                           :yardcam_images
                           {:image_name image-name})))))

(defn get-last-obs-id
  "Returns the ID of the last observation."
  [db-con]
  (first (j/query db-con
                  (sql/format (sql/build :select [[:%max.id "id"]]
                                         :from :observations))
                  {:row-fn #(:id %)})))

(defn store-tb-image-name
  "Saves a Testbed image name and associates it with given observation ID.
  Returns true on success and false otherwise."
  [db-con obs-id image-name]
  (= 1 (first (j/update! db-con
                         :observations
                         {:tb_image_name image-name}
                         ["id = ?" obs-id]))))
