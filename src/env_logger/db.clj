(ns env-logger.db
  (:require [clojure.edn :as edn]
            [korma.db :refer [postgres defdb]]
            [korma.core :as kc]
            [clj-time [core :as tco] [coerce :as tc] [format :as tf] [local :as tl]]))
            
(defn load-config
  "Given a filename, load and return a config file"
  [filename]
  (edn/read-string (slurp filename)))

(defn get-conf-value
  "Return a key value from the configuration"
  [property config-key]
  (config-key (property (load-config
    (clojure.java.io/resource "config.edn")))))

(def db (postgres {
  :host (get (System/getenv) "OPENSHIFT_POSTGRESQL_DB_HOST" "localhost")
  :port (get (System/getenv) "OPENSHIFT_POSTGRESQL_DB_PORT" "5432")
  :db (get-conf-value :database :name)
  :user (get (System/getenv) "OPENSHIFT_POSTGRESQL_DB_USERNAME"
          (get-conf-value :database :username))
  :password (get (System/getenv) "OPENSHIFT_POSTGRESQL_DB_PASSWORD"
              (get-conf-value :database :password))}))
(defdb postgres-db db)
(kc/defentity observations)

(defn insert-observation
  "Inserts a observation to the database. Optionally corrects the temperature
  with an offset."
  [observation]
  (let [offset (if (get-conf-value :correction :enabled)
                        (get-conf-value :correction :offset) 0)]
    (kc/insert observations
      (kc/values {
                :recorded (->> (:timestamp observation)
                               (tf/parse) tc/to-timestamp)
                :temperature (- (:inside_temp observation) offset)
                :brightness (:inside_light observation)}))))

(defn format-date
  "Changes the timezone and formats the date with a given formatter"
  [date formatter]
  (tl/format-local-time (tco/to-time-zone (tc/from-sql-date date)
      (tco/time-zone-for-id "Europe/Helsinki")) formatter))

(defn get-all-obs
  "Fetches all observations from the database"
  [ & time-format]
  (let [formatter (if time-format (nth time-format 0) :mysql)]
    (for [row (kc/select observations
            (kc/fields :brightness :temperature :recorded))]
      (merge row
        ; Reformat date
        {:recorded (format-date (:recorded row) formatter)}))))

(defn get-last-n-days-obs
  "Fetches the observations from the last n days"
  [n & time-format]
  (let [formatter (if time-format (nth time-format 0) :mysql)]
    (for [row (kc/select observations
            (kc/fields :brightness :temperature :recorded)
            (kc/where {:recorded [>= (tc/to-sql-date (tco/minus (tco/now)
                          (tco/days n)))]}))]
      (merge row
        ; Reformat date
        {:recorded (format-date (:recorded row) formatter)}))))
