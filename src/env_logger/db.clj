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
            [env-logger.config :refer [get-conf-value db-conf]]))

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

;; User data functions
(defn get-user-data
  "Returns the password hash and Yubikey ID of the user with the given username.
  Returns nil if the user is not found."
  [db-con username]
  (let [result (j/query db-con
                        (sql/format (sql/build :select [:pw_hash]
                                               :from :users
                                               :where [:= :username
                                                       username]))
                        {:row-fn #(:pw_hash %)})
        key-ids (j/query db-con
                         (sql/format (sql/build :select [:yubikey_id]
                                                :from [[:users :u]]
                                                :join [:yubikeys
                                                       [:= :u.user_id
                                                        :yubikeys.user_id]]
                                                :where [:= :u.username
                                                        username]))
                         {:row-fn #(:yubikey_id %)})]
    (when (pos? (count result))
      {:pw-hash (first result)
       :yubikey-ids (set key-ids)})))

;; Observation functions
(defn insert-observation
  "Inserts a observation to the database. Optionally corrects the temperature
  with an offset."
  [db-con observation]
  (if (= 5 (count observation))
    (j/with-db-transaction [t-con db-con]
      (try
        (let [offset (if (get-conf-value :correction :k :enabled)
                       (get-conf-value :correction :k :offset) 0)
              obs-id (:id (first (j/insert! t-con
                                            :observations
                                            {:recorded (f/parse
                                                        (:timestamp
                                                         observation))
                                             :temperature (- (:inside_temp
                                                              observation)
                                                             offset)
                                             :brightness (:inside_light
                                                          observation)})))]
          (if (pos? obs-id)
            (if (every? pos?
                        (for [beacon (:beacons observation)]
                          (:id (first (j/insert! t-con
                                                 :beacons
                                                 {:obs_id obs-id
                                                  :mac_address (:mac beacon)
                                                  :rssi (:rssi beacon)})))))
              (let [weather-data (:weather-data observation)]
                (if (zero? (count weather-data))
                  ;; No weather data
                  true
                  (if (pos? (:id (first (j/insert! t-con
                                                   :weather_data
                                                   {:obs_id obs-id
                                                    :time (f/parse
                                                           (:date
                                                            weather-data))
                                                    :temperature (:temperature
                                                                  weather-data)
                                                    :cloudiness
                                                    (:cloudiness
                                                     weather-data)}))))
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
                                                 "Europe/Helsinki"))
                       formatter))

(defn validate-date
  "Checks if the given date is nil or if non-nil, it is in the dd.mm.yyyy
  or d.m.yyyy format."
  [date]
  (if (nil? date)
    true
    (not (nil? (and date
                    (re-find #"\d{1,2}\.\d{1,2}\.\d{4}" date))))))

(defn make-date-dt
  "Formats a date to be SQL datetime compatible. Mode is either start or end."
  [date mode]
  (f/parse (f/formatter "d.M.y H:m:s")
           (format "%s %s" date (if (= mode "start")
                                  "00:00:00"
                                  "23:59:59"))))

(defmacro get-by-interval
  "Fetches observations in an interval using the provided function."
  [fetch-fn db-con start-date end-date dt-column]
  (let [start-dt (gensym 'start)
        end-dt (gensym 'end)]
    `(if (or (not (validate-date ~start-date))
             (not (validate-date ~end-date)))
       ;; Either date is invalid
       ()
       (let [~start-dt (if ~start-date
                         (make-date-dt ~start-date "start")
                         ;; Hack to avoid SQL WHERE hacks
                         (t/date-time 2010 1 1))
             ~end-dt (if ~end-date
                       (make-date-dt ~end-date "end")
                       ;; Hack to avoid SQL WHERE hacks
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
                             [:w.temperature "o_temperature"]
                             :w.cloudiness]
                    :from [[:observations :o]]
                    :left-join [[:weather-data :w]
                                [:= :o.id :w.obs_id]]}
        where-query (if where
                      (sql/build base-query :where where)
                      base-query)
        limit-query (if limit
                      (sql/build where-query :limit limit
                                 :order-by [[:o.id :desc]])
                      (sql/build where-query :order-by [[:o.id :asc]]))]
    (j/query db-con
             (sql/format limit-query)
             {:row-fn #(merge %
                              {:recorded (format-datetime
                                          (:recorded %)
                                          :date-hour-minute-second)})})))

(defn get-obs-days
  "Fetches the observations from the last N days."
  [db-con n]
  (get-observations db-con
                    :where [:>= :recorded
                            (t/minus (t/now)
                                     (t/days n))]))

(defn get-obs-interval
  "Fetches observations in an interval between the provided dates."
  [db-con start-date end-date]
  (get-by-interval get-observations
                   db-con
                   start-date
                   end-date
                   :recorded))

(defn get-weather-observations
  "Fetches weather observations filtered by the provided SQL WHERE clause."
  [db-con & {:keys [where]}]
  (j/query db-con
           (sql/format (sql/build :select [:time
                                           [:temperature "o_temperature"]
                                           :cloudiness]
                                  :from [:weather-data]
                                  :where where
                                  :order-by [[:id :asc]]))
           {:row-fn #(merge %
                            {:time (format-datetime
                                    (:time %)
                                    :date-hour-minute-second)})}))


(defn get-weather-obs-days
  "Fetches the weather observations from the last N days."
  [db-con n]
  (get-weather-observations db-con
                            :where [:>= :time
                                    (t/minus (t/now)
                                             (t/days n))]))

(defn get-weather-obs-interval
  "Fetches weather observations in an interval between the provided dates."
  [db-con start-date end-date]
  (get-by-interval get-weather-observations
                   db-con
                   start-date
                   end-date
                   :time))

(defn get-obs-start-and-end
  "Fetches the start (first) and end (last) dates of all observations."
  [db-con]
  (let [formatter (f/formatter "d.M.y")
        result (j/query db-con
                        (sql/format (sql/build :select [[:%min.recorded "start"]
                                                        [:%max.recorded "end"]]
                                               :from :observations)))]
    (if (= 1 (count result))
      {:start (f/unparse formatter (:start (first result)))
       :end (f/unparse formatter (:end (first result)))}
      {:start "" :end ""})))

(defn insert-yc-image-name
  "Stores the name of the latest Yardcam image. Rows from the table are
  deleted before a new row is inserted."
  [db-con image-name]
  (let [result (j/query db-con
                        (sql/format (sql/build :select [:image_id]
                                               :from :yardcam_images)))]
    (when (pos? (count result))
      (j/execute! db-con "DELETE FROM yardcam_images"))
    (= 1 (count (j/insert! db-con
                           :yardcam_images
                           {:image_name image-name})))))
