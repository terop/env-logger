(ns env-logger.db
  (:require [clj-time.coerce :as tc]
            [clj-time.core :as tco]
            [clj-time.format :as tf]
            [clj-time.local :as tl]
            [clj-time.jdbc]
            [korma.core :refer :all]
            [korma.db :refer [defdb postgres]]
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
                    (where {:recorded [>= (tco/minus (tco/now)
                                                     (tco/days n))]})
                    (order :id :ASC))]
    ;; Reformat date
    (merge row
           {:recorded (format-datetime (:recorded row)
                                       :date-hour-minute-second)})))

(defn get-obs-within-interval
  "Fetches observations in an interval between either one or two dates"
  [date-one date-two]
  (let [formatter (tf/formatter "d.M.y H:m:s")
        one-dt (if date-one
                 (tf/parse formatter (format "%s 00:00:00" date-one))
                 ;; Hack to avoid problems with Korma and SQL WHERE
                 (tco/date-time 2010 1 1))
        two-dt (if date-two
                 (tf/parse formatter (format "%s 23:59:59" date-two))
                 ;; Hack to avoid problems with Korma and SQL WHERE
                 (tco/today-at 23 59 59))]
    (for [row (select observations
                      (fields :brightness :temperature :recorded)
                      (where (and {:recorded [>=
                                              (tl/to-local-date-time one-dt)]}
                                  {:recorded [<=
                                              (tl/to-local-date-time two-dt)]}))
                      (order :id :ASC))]
    ;; Reformat date
    (merge row
           {:recorded (format-datetime (:recorded row)
                                       :date-hour-minute-second)}))))
