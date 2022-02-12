(ns env-logger.user-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as js]
            [env-logger
             [user :refer [get-pw-hash get-user-id]]
             [db-test :refer [test-ds]]])
  (:import (org.postgresql.util PSQLException
                                PSQLState)))

(defn clean-test-database
  "Cleans the test database before and after running tests."
  [test-fn]
  (js/insert! test-ds
              :users
              {:username "test-user"
               :pw_hash "myhash"})
  (test-fn)
  (jdbc/execute! test-ds ["DELETE FROM users"]))

;; Fixture run at the start and end of tests
(use-fixtures :once clean-test-database)

(deftest user-data-query
  (testing "Querying for a user's password hash"
    (is (nil? (get-pw-hash test-ds "notfound")))
    (is (= "myhash" (get-pw-hash test-ds "test-user")))
    (with-redefs [jdbc/execute-one!
                  (fn [_ _ _]
                    (throw (PSQLException.
                            "Test exception"
                            (PSQLState/COMMUNICATION_ERROR))))]
      (is (= {:error :db-error}
             (get-pw-hash test-ds "test-user"))))))

(deftest user-id-query
  (testing "Querying of user ID"
    (is (nil? (get-user-id test-ds "notfound")))
    (is (pos? (get-user-id test-ds "test-user")))))
