(ns env-logger.user-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as js]
            [env-logger
             [user :refer [get-user-data get-user-id get-yubikey-id]]
             [db-test :refer [test-ds]]])
  (:import (org.postgresql.util PSQLException
                                PSQLState)))

(defn clean-test-database
  "Cleans the test database before and after running tests."
  [test-fn]
  (let [user-id (:user_id (js/insert! test-ds
                                      :users
                                      {:username "test-user"
                                       :pw_hash "myhash"}))]
    (js/insert! test-ds
                :yubikeys
                {:user_id user-id
                 :yubikey_id "mykeyid"}))
  (test-fn)
  (jdbc/execute! test-ds ["DELETE FROM users"]))

;; Fixture run at the start and end of tests
(use-fixtures :once clean-test-database)

(deftest yubikey-id-query
  (testing "Querying for a user's Yubikey ID"
    (is (nil? (seq (get-yubikey-id test-ds "notfound"))))
    (is (= (set ["mykeyid"]) (get-yubikey-id test-ds "test-user")))))

(deftest user-data-query
  (testing "Querying for a user's password hash and Yubikey ID"
    (is (nil? (get-user-data test-ds "notfound")))
    (is (= {:pw-hash "myhash"
            :yubikey-ids (set ["mykeyid"])}
           (get-user-data test-ds "test-user")))
    (with-redefs [jdbc/execute-one!
                  (fn [_ _ _]
                    (throw (PSQLException.
                            "Test exception"
                            (PSQLState/COMMUNICATION_ERROR))))]
      (is (= {:error :db-error}
             (get-user-data test-ds "test-user"))))))

(deftest user-id-query
  (testing "Querying of user ID"
    (is (nil? (get-user-id test-ds "notfound")))
    (is (pos? (get-user-id test-ds "test-user")))))
