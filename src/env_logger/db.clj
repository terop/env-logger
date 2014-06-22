(ns env-logger.db
  (:require [clojure.edn :as edn]
            [korma.db :refer [postgres defdb]]
            [korma.core :refer [defentity insert values select fields]]
            [clj-time [core :as tco] [coerce :as tc] [format :as tf]]))
            
(defn load-config
  "Given a filename, load and return a config file"
  [filename]
  (edn/read-string (slurp filename)))

(defn get-conf-value
  "Return a property from the database configuration"
  [property]
  (property (:database (load-config
    (clojure.java.io/resource "config.edn")))))

(def db (postgres {
  :host (get (System/getenv) "OPENSHIFT_POSTGRESQL_DB_HOST" "localhost")
  :port (get (System/getenv) "OPENSHIFT_POSTGRESQL_DB_PORT" "5432")
  :db (get-conf-value :name)
  :user (get (System/getenv) "OPENSHIFT_POSTGRESQL_DB_USERNAME"
          (get-conf-value :username))
  :password (get (System/getenv) "OPENSHIFT_POSTGRESQL_DB_PASSWORD"
              (get-conf-value :password))}))
(defdb postgres-db db)
(defentity observations)

(defn insert-observation
  "Inserts a observation to the database"
  [observation]
  (insert observations
    (values {
              :recorded (->> (:timestamp observation) 
                             (tf/parse) tc/to-timestamp)
              :temperature (:inside_temp observation)
              :brightness (:inside_light observation)})))

(defn format-date
  "Changes the timezone and formats the date with some formatter"
  [date formatter]
  (tf/unparse (tf/formatters formatter)
    ; Hack to change timezone from UTC to local time
    (tco/to-time-zone (tc/from-sql-date date)
    (tco/time-zone-for-id "Europe/Helsinki"))))

(defn get-all-observations
  "Fetches all observations from the database"
  [ & time-format]
  (let [formatter (if time-format (nth time-format 0) :mysql)]
    (for [row (select observations
            (fields :brightness :temperature :recorded))]
      (merge row
        ; Reformat date
        {:recorded (format-date (:recorded row) formatter)}))))

(defn get-observations-by-criteria
  "Fetches the observations from the database which fullfil the given criteria"
  [criteria & time-format]
  (let [formatter (if time-format (nth time-format 0) :mysql)]
    (for [row (select observations
            (fields :brightness :temperature :recorded)
            (select criteria))]
      (merge row
        ; Reformat date
        {:recorded (format-date (:recorded row) formatter)}))))
