(ns env-logger.user
  "Namespace for user and profile functionality"
  (:require [clj-ldap.client :as ldap]
            [clojure.java.jdbc :as j]
            [clojure.tools.logging :as log]
            [env-logger.config :refer [ldap-conf]]
            [honeysql.core :as sql])
  (:import java.sql.BatchUpdateException
           org.postgresql.util.PSQLException))

(defn get-yubikey-id
  "Returns the Yubikey ID(s) of a user in a set. Returns an empty set
  if the ID is not found."
  [db-con username]
  (set (j/query db-con
                (sql/format (sql/build :select [:yubikey_id]
                                       :from [[:users :u]]
                                       :join [:yubikeys
                                              [:= :u.user_id
                                               :yubikeys.user_id]]
                                       :where [:= :u.username
                                               username]))
                {:row-fn #(:yubikey_id %)})))

(defn get-user-data
  "Returns the password hash and Yubikey ID(s) of the user with the given
  username. Returns nil if the user is not found."
  [db-con username]
  (try
    (let [result (j/query db-con
                          (sql/format (sql/build :select [:pw_hash]
                                                 :from :users
                                                 :where [:= :username
                                                         username]))
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
                  (sql/format (sql/build :select [:user_id]
                                         :from :users
                                         :where [:= :username
                                                 username]))
                  {:row-fn #(:user_id %)})))

;; Profile handling
(defn insert-profile
  "Inserts a user profile into the database. Returns true on success and false
  otherwise."
  [db-con username profile-name profile]
  (let [user-id (get-user-id db-con username)]
    (if user-id
      (try
        (if (= 1 (first (j/execute! db-con
                                    [(str "INSERT INTO profiles (user_id, "
                                          "name, profile) VALUES (?, ?, "
                                          "to_json(?::json))")
                                     user-id profile-name profile])))
          true false)
        (catch java.sql.BatchUpdateException bue
          (log/error "Profile insert failed, message: "
                     (.getMessage bue))
          false))
      false)))

(defn get-profiles
  "Returns the profile(s) for the given user. Returns an empty list if the user
  has no profiles or doesn't exist."
  [db-con username]
  (j/query db-con
           (sql/format (sql/build :select [:name
                                           :profile]
                                  :from [[:users :u]]
                                  :join [[:profiles :p]
                                         [:= :u.user_id
                                          :p.user_id]]
                                  :where [:= :u.username
                                          username]))
           {:row-fn #(merge %
                            {:profile (.getValue (:profile %))})}))

(defn delete-profile
  "Deletes the given profile of the given user. Returns true on success
  and false otherwise."
  [db-con username profile-name]
  (let [profile-id (first (j/query db-con
                                   (sql/format
                                    (sql/build :select [:profile_id]
                                               :from [:profiles]
                                               :where [:and
                                                       [:= :user_id
                                                        (get-user-id db-con
                                                                     username)]
                                                       [:= :name
                                                        profile-name]]))
                                   {:row-fn #(:profile_id %)}))]
    (if (pos? (first (j/delete! db-con
                                :profiles
                                ["profile_id = ?" profile-id])))
      true false)))
