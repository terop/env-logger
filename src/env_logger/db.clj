(ns env-logger.db
  (:require [korma.db :refer [postgres defdb]]
            [korma.core :refer :all]
            [clj-time [core :as tco] [coerce :as tc] [format :as tf]
             [local :as tl]]
            [clj-time.jdbc]
            [env-logger.config :refer [get-conf-value]]))

(defdb db (postgres {:host (get (System/getenv)
                                "OPENSHIFT_POSTGRESQL_DB_HOST" "localhost")
                     :port (get (System/getenv)
                                "OPENSHIFT_POSTGRESQL_DB_PORT" "5432")
                     :db (get-conf-value :database :name)
                     :user (get (System/getenv)
                                "OPENSHIFT_POSTGRESQL_DB_USERNAME"
                                (get-conf-value :database :username))
                     :password (get (System/getenv)
                                    "OPENSHIFT_POSTGRESQL_DB_PASSWORD"
                                    (get-conf-value :database :password))}))
(defentity observations)

(defn insert-observation
  "Inserts a observation to the database. Optionally corrects the temperature
  with an offset."
  [observation]
  (if (= 3 (count observation))
    (try
      (let [offset (if (get-conf-value :correction :enabled)
                     (get-conf-value :correction :offset) 0)]
        (if (pos? (:id (insert observations
                               (values {:recorded (tf/parse (:timestamp
                                                             observation))
                                        :temperature (- (:inside_temp
                                                         observation) offset)
                                        :brightness (:inside_light
                                                     observation)}))))
          true false))
      (catch org.postgresql.util.PSQLException pge
        (.printStackTrace pge)
        false))
    false))

(defn format-datetime
  "Changes the timezone and formats the datetime with a given formatter"
  [datetime formatter]
  (tl/format-local-time (tco/to-time-zone datetime (tco/time-zone-for-id
                                                    "Europe/Helsinki"))
                        formatter))

(defn get-all-obs
  "Fetches all observations from the database"
  []
  (for [row (select observations
                    (fields :brightness :temperature :recorded)
                    (order :id :ASC))]
    ;; Reformat date
    (merge row
           {:recorded (format-datetime (:recorded row)
                                       :date-hour-minute-second)})))

(defn get-last-n-days-obs
  "Fetches the observations from the last N days"
  [n]
  (for [row (select observations
                    (fields :brightness :temperature :recorded)
                    (where {:recorded [>= (tc/to-sql-date
                                           (tco/minus (tco/now)
                                                      (tco/days n)))]})
                    (order :id :ASC))]
    ;; Reformat date
    (merge row
           {:recorded (format-datetime (:recorded row)
                                       :date-hour-minute-second)})))
