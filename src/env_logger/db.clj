(ns env-logger.db
  (:require [clojure.edn :as edn]
            [korma.db :refer [postgres defdb]]
            [korma.core :refer [defentity insert values]] 
            [clj-time [format :as tf] [coerce :as tc]]))
            
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
  ; (str (->> (:timestamp observation) (tf/parse) tc/to-timestamp) " "
  ;       (:inside_temp observation) " "
  ;       (:inside_light observation)))
  (insert observations
    (values {
              :recorded (->> (:timestamp observation) 
                             (tf/parse) tc/to-timestamp)
              :temperature (:inside_temp observation)
              :brightness (:inside_light observation)})))
