(ns env-logger.db
  "Namespace containing the application's database function"
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.local :as l]
            [clj-time.jdbc]
            [clojure.java.jdbc :as j]
            [honeysql.core :as sql]
            [honeysql.helpers :refer :all]
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

(defn insert-observation
  "Inserts a observation to the database. Optionally corrects the temperature
  with an offset."
  [db-con observation]
  (if (= 4 (count observation))
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
              true
              (do
                (j/db-set-rollback-only! t-con)
                false))
            false))
        (catch org.postgresql.util.PSQLException pge
          (.printStackTrace pge)
          (j/db-set-rollback-only! t-con)
          false)))
    false))

(defn format-datetime
  "Changes the timezone and formats the datetime with the given formatter"
  [datetime formatter]
  (l/format-local-time (t/to-time-zone datetime (t/time-zone-for-id
                                                 "Europe/Helsinki"))
                       formatter))

(defn get-all-obs
  "Fetches all observations from the database"
  [db-con]
  (for [row (j/query db-con
                     (sql/format (sql/build :select [:brightness
                                                     :temperature
                                                     :recorded]
                                            :from :observations
                                            :order-by [[:id :asc]])))]
    ;; Reformat date
    (merge row
           {:recorded (format-datetime (:recorded row)
                                       :date-hour-minute-second)})))

(defn get-last-n-days-obs
  "Fetches the observations from the last N days"
  [db-con n]
  (for [row (j/query db-con
                     (sql/format (sql/build :select [:brightness
                                                     :temperature
                                                     :recorded]
                                            :from :observations
                                            :where [:>= :recorded
                                                    (t/minus (t/now)
                                                             (t/days n))]
                                            :order-by [[:id :asc]])))]
    ;; Reformat date
    (merge row
           {:recorded (format-datetime (:recorded row)
                                       :date-hour-minute-second)})))

(defn get-obs-within-interval
  "Fetches observations in an interval between either one or two dates"
  [db-con start-date end-date]
  (if (or (and start-date
               (not (re-find #"\d{1,2}\.\d{1,2}\.\d{4}" start-date)))
          (and end-date
               (not (re-find #"\d{1,2}\.\d{1,2}\.\d{4}" end-date))))
    ;; Either date is invalid
    ()
    (let [formatter (f/formatter "d.M.y H:m:s")
          start-dt (if start-date
                     (f/parse formatter (format "%s 00:00:00" start-date))
                     ;; Hack to avoid SQL WHERE hacks
                     (t/date-time 2010 1 1))
          end-dt (if end-date
                   (f/parse formatter (format "%s 23:59:59" end-date))
                   ;; Hack to avoid SQL WHERE hacks
                   (t/now))]
      (for [row (j/query db-con
                         (sql/format (sql/build :select [:brightness
                                                         :temperature
                                                         :recorded]
                                                :from :observations
                                                :where [:and
                                                        [:>= :recorded
                                                         (l/to-local-date-time
                                                          start-dt)]
                                                        [:<= :recorded
                                                         (l/to-local-date-time
                                                          end-dt)]]
                                                :order-by [[:id :asc]])))]
        ;; Reformat date
        (merge row
               {:recorded (format-datetime (:recorded row)
                                           :date-hour-minute-second)})))))
