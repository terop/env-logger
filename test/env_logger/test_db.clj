(ns env-logger.test-db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as js]
            [honey.sql :as sql]
            [env-logger.db :refer [db-conf get-db-password]]))

(let [db-host (get (System/getenv)
                   "POSTGRESQL_DB_HOST"
                   (db-conf :host))
      db-port (get (System/getenv)
                   "POSTGRESQL_DB_PORT"
                   (db-conf :port))
      db-name "env_logger_test"
      db-user (get (System/getenv)
                   "POSTGRESQL_DB_USERNAME"
                   (db-conf :username))
      db-password (get-db-password)]
  (def test-postgres {:dbtype "postgresql"
                      :dbname db-name
                      :host db-host
                      :port db-port
                      :user db-user
                      :password db-password})
  (def test-ds (jdbc/with-options (jdbc/get-datasource test-postgres)
                 {:builder-fn rs/as-unqualified-lower-maps})))

(defn clear-test-database!
  []
  (jdbc/execute! test-ds (sql/format {:delete-from :beacons}))
  (jdbc/execute! test-ds (sql/format {:delete-from :weather_data}))
  (jdbc/execute! test-ds (sql/format {:delete-from :ruuvitag_observations}))
  (jdbc/execute! test-ds (sql/format {:delete-from :ruuvi_air_observations}))
  (jdbc/execute! test-ds (sql/format {:delete-from :electricity_price_minute}))
  (jdbc/execute! test-ds (sql/format {:delete-from :electricity_price}))
  (jdbc/execute! test-ds (sql/format {:delete-from :electricity_consumption}))
  (jdbc/execute! test-ds (sql/format {:delete-from :observations})))

(defn seed-base-observation!
  [current-dt]
  (js/insert! test-ds
              :observations
              {:recorded current-dt
               :inside_light 5}))

(defn reset-test-database!
  [current-dt]
  (clear-test-database!)
  (seed-base-observation! current-dt))
