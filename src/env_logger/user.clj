(ns env-logger.user
  "Namespace for user functionality"
  (:require [clj-ldap.client :as ldap]
            [clojure.java.jdbc :as j]
            [clojure.tools.logging :as log]
            [env-logger.config :refer [ldap-conf]])
  (:import java.sql.BatchUpdateException
           org.postgresql.util.PSQLException))
(refer-clojure :exclude '[filter for group-by into partition-by set update])
(require '[honey.sql :as sql])

(defn get-yubikey-id
  "Returns the Yubikey ID(s) of a user in a set. Returns an empty set
  if the ID is not found."
  [db-con username]
  (set (j/query db-con
                (sql/format {:select [:yubikey_id]
                             :from [[:users :u]]
                             :join [:yubikeys
                                    [:= :u.user_id
                                     :yubikeys.user_id]]
                             :where [:= :u.username
                                     username]})
                {:row-fn #(:yubikey_id %)})))

(defn get-user-data
  "Returns the password hash and Yubikey ID(s) of the user with the given
  username. Returns nil if the user is not found."
  [db-con username]
  (try
    (let [result (j/query db-con
                          (sql/format {:select [:pw_hash]
                                       :from [:users]
                                       :where [:= :username
                                               username]})
                          {:row-fn #(:pw_hash %)})
          key-ids (get-yubikey-id db-con username)]
      (when (pos? (count result))
        {:pw-hash (first result)
         :yubikey-ids key-ids}))
    (catch PSQLException pge
      (log/error "Failed to get user data from DB, message:"
                 (.getMessage pge))
      {:error :db-error})))

(defn get-password-from-ldap
  "Fetches a user's password hash from LDAP. Returns nil if the user is
  not found."
  [username]
  (:userPassword (ldap/get (ldap/connect {:host (format "%s:%s"
                                                        (ldap-conf :host)
                                                        (ldap-conf :port))
                                          :bind-dn (ldap-conf :bind-dn)
                                          :password (ldap-conf :password)})
                           (format "cn=%s,ou=users,%s"
                                   username
                                   (ldap-conf :base-dn))
                           [:userPassword])))

(defn get-user-id
  "Returns the user ID of the user with the given username. If the user is not
  found, nil is returned."
  [db-con username]
  (first (j/query db-con
                  (sql/format {:select [:user_id]
                               :from [:users]
                               :where [:= :username
                                       username]})
                  {:row-fn #(:user_id %)})))
