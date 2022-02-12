(ns env-logger.authentication-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cheshire.core :refer [parse-string]]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as js]
            [cljwebauthn.b64 :as b64]
            [env-logger
             [authentication :refer [save-authenticator
                                     get-authenticators
                                     get-authenticator-count
                                     inc-login-count
                                     wa-prepare-register
                                     do-prepare-login]]
             [db :refer [rs-opts]]
             [db-test :refer [test-ds]]])
  (:import com.webauthn4j.authenticator.AuthenticatorImpl
           com.webauthn4j.converter.AttestedCredentialDataConverter
           com.webauthn4j.converter.util.ObjectConverter
           webauthn4j.AttestationStatementEnvelope
           (org.postgresql.util PSQLException
                                PSQLState)))
(refer-clojure :exclude '[filter for group-by into partition-by set update])
(require '[honey.sql :as sql])

(def authenticator-data {:attested-credential "AAAAAAAAAAAAAAAAAAAAAAAg09w4snBXtbIKzw/O7krAjYTzkIWeOVDkYGvlT/v90UelAQIDJiABIVggdBiX1FPFpHQM/NQxJ2eT5jr+eSkBvd4LOQUE0FKyJqciWCBSERtAsX3p5DfyS06FygtTlRj2HiAWNUyrUvGnWCZ/gg=="
                         :attestation-statement "v2dhdHRTdG10omNzaWdYRzBFAiB0LliflcT5Po+aAvh4DcwArDLNgYYWL+tDDDPbwP0fNQIhAIKFwoYN+JWm+Lla8rr6ya7vtepHWZikR9yYzhnFxHAMY3g1Y4FZAd0wggHZMIIBfaADAgECAgEBMA0GCSqGSIb3DQEBCwUAMGAxCzAJBgNVBAYTAlVTMREwDwYDVQQKDAhDaHJvbWl1bTEiMCAGA1UECwwZQXV0aGVudGljYXRvciBBdHRlc3RhdGlvbjEaMBgGA1UEAwwRQmF0Y2ggQ2VydGlmaWNhdGUwHhcNMTcwNzE0MDI0MDAwWhcNNDIwMjA3MTAxOTI1WjBgMQswCQYDVQQGEwJVUzERMA8GA1UECgwIQ2hyb21pdW0xIjAgBgNVBAsMGUF1dGhlbnRpY2F0b3IgQXR0ZXN0YXRpb24xGjAYBgNVBAMMEUJhdGNoIENlcnRpZmljYXRlMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEjWF+ZclQjmS8xWc6yCpnmdo8FEZoLCWMRj//31jf0vo+bDeLU9eVxKTf+0GZ7deGLyOrrwIDtLiRG6BWmZThAaMlMCMwEwYLKwYBBAGC5RwCAQEEBAMCBSAwDAYDVR0TAQH/BAIwADANBgkqhkiG9w0BAQsFAANHADBEAiAcozP66GUhr4J1nEAM+03WpaqrWOtjGtmSr/cS4IWd3wIgUTxReYyaiGrq0RG52f/LGB112ki9h76ZaH0CaPI31sZjZm10aGZpZG8tdTJm/w=="
                         :counter 0})

(let [object-converter (new ObjectConverter)
      credential-converter (new AttestedCredentialDataConverter
                                object-converter)
      cbor-converter (.getCborConverter object-converter)]
  (def authenticator (new AuthenticatorImpl
                          (.convert credential-converter
                                    (b64/decode-binary (:attested-credential
                                                        authenticator-data)))
                          (.getAttestationStatement
                           (.readValue cbor-converter
                                       (b64/decode-binary
                                        (:attestation-statement
                                         authenticator-data))
                                       AttestationStatementEnvelope))
                          (:counter authenticator-data))))

;; Helpers
(defn clean-test-database
  "Cleans the test database before and after running tests."
  [test-fn]
  (js/insert! test-ds
              :users
              {:username "test-user"
               :pw_hash "myhash"})
  (test-fn)
  (jdbc/execute! test-ds ["DELETE FROM users"]))

(defn insert-authenticator
  "Inserts an authenticator to the DB for test purposes."
  []
  (save-authenticator test-ds "test-user" authenticator))

(defn delete-authenticators
  "Deletes all authenticators from the DB."
  []
  (jdbc/execute! test-ds ["DELETE FROM webauthn_authenticators"]))

(defn get-login-count
  "Returns the login count for an authenticator."
  [authenticator-id]
  (:login-count (jdbc/execute-one! test-ds
                                   (sql/format {:select [:login_count]
                                                :from [:webauthn_authenticators]
                                                :where [:= :authn_id
                                                        authenticator-id]})
                                   rs-opts)))

;; Fixture run at the start and end of tests
(use-fixtures :once clean-test-database)

(deftest authenticator-saving
  (testing "Saving of an authenticator to the DB"
    (is (true? (save-authenticator test-ds "test-user" authenticator)))
    (is (= 1 (:count (jdbc/execute-one! test-ds
                                        (sql/format
                                         {:select [:%count.counter]
                                          :from :webauthn_authenticators})))))
    (with-redefs [js/insert! (fn [_ _ _ _]
                               (throw (PSQLException.
                                       "Test exception"
                                       (PSQLState/COMMUNICATION_ERROR))))]
      (is (false? (save-authenticator test-ds "test-user" authenticator))))))

(deftest authenticator-query
  (testing "Fetching of saved authenticators from the DB"
    (insert-authenticator)
    (let [authenticator (get-authenticators test-ds "test-user")]
      (is (= 1 (count authenticator)))
      (is (not (nil? (first authenticator))))
      (is (instance? AuthenticatorImpl (:authn (first authenticator))))
      (is (pos? (:id (first authenticator)))))
    (delete-authenticators)))

(deftest authenticator-count-query
  (testing "Querying the saved authenticator count from the DB"
    (is (zero? (get-authenticator-count test-ds "test-user")))
    (insert-authenticator)
    (is (= 1 (get-authenticator-count test-ds "test-user")))
    (delete-authenticators)))

(deftest login-count-increment
  (testing "Incrementing the login count of an authenticator"
    (insert-authenticator)
    (let [authn-id (:authn-id (jdbc/execute-one!
                               test-ds
                               (sql/format {:select [:authn_id]
                                            :from [:webauthn_authenticators]})
                               rs-opts))]
      (is (zero? (get-login-count authn-id)))
      (inc-login-count test-ds authn-id)
      (is (= 1 (get-login-count authn-id)))
      (inc-login-count test-ds authn-id)
      (inc-login-count test-ds authn-id)
      (is (= 3 (get-login-count authn-id))))
    (delete-authenticators)))

(deftest register-preparation
  (testing "User register preparation data generation"
    (let [resp (wa-prepare-register {:params {:username "test-user"}})
          body (parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (= "localhost" (get-in body [:rp :id])))
      (is (= "dGVzdC11c2Vy" (get-in body [:user :id]))))))

(deftest login-preparation
  (testing "User login preparation data generation"
    (insert-authenticator)
    (let [resp (do-prepare-login {:params {:username "test-user"}} test-ds)
          body (parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (= "09w4snBXtbIKzw/O7krAjYTzkIWeOVDkYGvlT/v90Uc="
             (:id (first (:credentials body))))))
    (delete-authenticators)))
