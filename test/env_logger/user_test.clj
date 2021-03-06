(ns env-logger.user-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as j]
            [clj-ldap.client :as ldap]
            [env-logger.user :refer :all]
            [env-logger.db-test :refer [test-postgres]])
  (:import (org.postgresql.util PSQLException
                                PSQLState)))

(defn clean-test-database
  "Cleans the test database before and after running tests."
  [test-fn]
  (let [user-id (:user_id (first (j/insert! test-postgres
                                            :users
                                            {:username "test-user"
                                             :pw_hash "myhash"})))]
    (j/insert! test-postgres
               :yubikeys
               {:user_id user-id
                :yubikey_id "mykeyid"}))
  (test-fn)
  (j/execute! test-postgres "DELETE FROM users"))

;; Fixture run at the start and end of tests
(use-fixtures :once clean-test-database)

(deftest yubikey-id-query
  (testing "Querying for a user's Yubikey ID"
    (is (nil? (seq (get-yubikey-id test-postgres "notfound"))))
    (is (= (set ["mykeyid"]) (get-yubikey-id test-postgres "test-user")))))

(deftest user-data-query
  (testing "Querying for a user's password hash and Yubikey ID"
    (is (nil? (get-user-data test-postgres "notfound")))
    (is (= {:pw-hash "myhash"
            :yubikey-ids (set ["mykeyid"])}
           (get-user-data test-postgres "test-user")))
    (with-redefs [j/query (fn [db query opts]
                            (throw (PSQLException.
                                    "Test exception"
                                    (PSQLState/COMMUNICATION_ERROR))))]
      (is (= {:error :db-error}
             (get-user-data test-postgres "test-user"))))))

(deftest password-from-ldap-query
  (testing "Searching for a user's password from LDAP"
    (with-redefs [ldap/connect (fn [options] nil)
                  ldap/get (fn [con dn fields] nil)]
      (is (nil? (get-password-from-ldap "notfound"))))
    (with-redefs [ldap/connect (fn [options] nil)
                  ldap/get (fn [con dn fields] {:userPassword "foobar"})]
      (is (= "foobar" (get-password-from-ldap "test-user"))))))

(deftest user-id-query
  (testing "Querying of user ID"
    (is (nil? (get-user-id test-postgres "notfound")))
    (is (pos? (get-user-id test-postgres "test-user")))))
