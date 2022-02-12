(ns env-logger.user
  "Namespace for user functionality"
  (:require [taoensso.timbre :refer [error]]
            [next.jdbc :as jdbc]
            [env-logger.db :refer [rs-opts]])
  (:import org.postgresql.util.PSQLException))
(refer-clojure :exclude '[filter for group-by into partition-by set update])
(require '[honey.sql :as sql])

(defn get-pw-hash
  "Returns the password hash of the user with the given
  username. Returns nil if the user is not found."
  [db-con username]
  (try
    (:pw-hash (jdbc/execute-one! db-con
                                 (sql/format {:select [:pw_hash]
                                              :from [:users]
                                              :where [:= :username
                                                      username]})
                                 rs-opts))
    (catch PSQLException pge
      (error pge "Failed to get user data from DB")
      {:error :db-error})))

(defn get-user-id
  "Returns the user ID of the user with the given username. If the user is not
  found, nil is returned."
  [db-con username]
  (:user-id (jdbc/execute-one! db-con
                               (sql/format {:select [:user_id]
                                            :from [:users]
                                            :where [:= :username
                                                    username]})
                               rs-opts)))
