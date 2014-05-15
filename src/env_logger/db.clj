(ns env-logger.db
  (:require [korma.db :refer [postgres defdb]]
            [korma.core :refer [defentity insert values]] 
            [clj-time [format :as tf] [coerce :as tc]]
            [env-logger.utils :as utils]))
            
(defn get-conf-value
  "Return a property from the database configuration"
  [property]
  (property (:database (utils/load-config 
    (clojure.java.io/resource "config.edn")))))

(def db (postgres {
  :host (get (System/getenv) "OPENSHIFT_POSTGRESQL_DB_HOST" "localhost")
  :port (get (System/getenv) "OPENSHIFT_POSTGRESQL_DB_PORT" "5432")
  :db (get-conf-value :name)
  :user (get-conf-value :username) 
  :password (get-conf-value :password)}))
(defdb postgres-db db)
(defentity observations)

(defn insert-observation
  "Inserts a observation to the database"
  [observation]
  ; (str (->> (:timestamp observation) (tf/parse) tc/to-timestamp) " "
  ;       (:temperature observation) " "
  ;       (:brightness observation)))
  (insert observations
    (values {
              :recorded (->> (:timestamp observation) 
                             (tf/parse) tc/to-timestamp)
              :temperature (:temperature observation)
              :brightness (:brightness observation)})))
