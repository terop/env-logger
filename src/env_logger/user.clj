(ns env-logger.user
  "Namespace for user functionality"
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [env-logger.db :refer [rs-opts]])
  (:import org.postgresql.util.PSQLException))
(refer-clojure :exclude '[filter for group-by into partition-by set update])
(require '[honey.sql :as sql])

(defn get-yubikey-id
  "Returns the Yubikey ID(s) of a user in a set. Returns an empty set
  if the ID is not found."
  [db-con username]
  (set (for [row (jdbc/execute! db-con
                                (sql/format {:select [:yubikey_id]
                                             :from [[:users :u]]
                                             :join [:yubikeys
                                                    [:= :u.user_id
                                                     :yubikeys.user_id]]
                                             :where [:= :u.username
                                                     username]})
                                rs-opts)]
         (:yubikey-id row))))

(defn get-user-data
  "Returns the password hash and Yubikey ID(s) of the user with the given
  username. Returns nil if the user is not found."
  [db-con username]
  (try
    (let [result (jdbc/execute-one! db-con
                                    (sql/format {:select [:pw_hash]
                                                 :from [:users]
                                                 :where [:= :username
                                                         username]})
                                    rs-opts)
          key-ids (get-yubikey-id db-con username)]
      (when (pos? (count result))
        {:pw-hash (:pw-hash result)
         :yubikey-ids key-ids}))
    (catch PSQLException pge
      (log/error "Failed to get user data from DB, message:"
                 (.getMessage pge))
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
