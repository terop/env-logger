(ns env-logger.authentication
  "A namespace for authentication related functions"
  (:require [cljwebauthn.core :as webauthn]
            [cljwebauthn.b64 :as b64]
            [cheshire.core :refer [generate-string]]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as js]
            [ring.util.response :as resp]
            [taoensso.timbre :refer [error]]
            [env-logger
             [config :refer [get-conf-value]]
             [db :as db]
             [user :refer [get-user-id]]])
  (:import com.webauthn4j.authenticator.AuthenticatorImpl
           com.webauthn4j.converter.AttestedCredentialDataConverter
           com.webauthn4j.converter.util.ObjectConverter
           org.postgresql.util.PSQLException
           webauthn4j.AttestationStatementEnvelope))
(refer-clojure :exclude '[filter for group-by into partition-by set update])
(require '[honey.sql :as sql])

;; WebAuthn

(let [production? (get-conf-value :in-production)]
  (def site-properties
    {:site-id (get-conf-value :hostname)
     :site-name "Environment logger app"
     :protocol (if production?
                 "https" "http")
     :port (if production?
             443 80)
     :host (get-conf-value :hostname)}))

(def authenticator-name (atom ""))
(def current-authn-number (atom 0))

;; Helper functions
(defn save-authenticator
  "Serialises and saves the given authenticator to the DB."
  [db-con username authenticator]
  (try
    (let [user-id (get-user-id db-con username)
          object-converter (new ObjectConverter)
          credential-converter (new AttestedCredentialDataConverter
                                    object-converter)
          cred-base64 (b64/encode-binary (.convert credential-converter
                                                   (.getAttestedCredentialData
                                                    ^AuthenticatorImpl authenticator)))
          envelope (new AttestationStatementEnvelope (.getAttestationStatement
                                                      ^AuthenticatorImpl authenticator))
          row (js/insert! db-con
                          :webauthn_authenticators
                          {:user_id user-id
                           :name (when (seq @authenticator-name)
                                   @authenticator-name)
                           :counter (.getCounter ^AuthenticatorImpl
                                     authenticator)
                           :attested_credential cred-base64
                           :attestation_statement (b64/encode-binary
                                                   (.writeValueAsBytes
                                                    (.getCborConverter
                                                     object-converter)
                                                    envelope))
                           :login_count 0}
                          db/rs-opts)]
      (pos? (:authn-id row)))
    (catch PSQLException pge
      (error pge "Failed to insert authenticator")
      false)
    (finally
      (reset! authenticator-name nil))))

(defn get-authenticators
  "Returns the user's saved authenticators."
  [db-con username]
  (let [user-id (get-user-id db-con username)
        object-converter (new ObjectConverter)
        credential-converter (new AttestedCredentialDataConverter
                                  object-converter)
        cbor-converter (.getCborConverter object-converter)]
    (for [row (jdbc/execute! db-con
                             (sql/format {:select [:authn_id
                                                   :counter
                                                   :attested_credential
                                                   :attestation_statement]
                                          :from [:webauthn_authenticators]
                                          :where [:= :user_id user-id]
                                          :order-by [[:login_count :desc]]})
                             db/rs-opts)]
      {:authn (new AuthenticatorImpl
                   (.convert credential-converter
                             (bytes (b64/decode-binary
                                     (:attested-credential row))))
                   (.getAttestationStatement
                    ^AttestationStatementEnvelope
                    (.readValue cbor-converter
                                (bytes (b64/decode-binary
                                        (:attestation-statement row)))
                                AttestationStatementEnvelope))
                   (:counter row))
       :id (:authn-id row)})))

(defn get-authenticator-count
  "Returns the number of the user's registered authenticators."
  [db-con username]
  (let [user-id (get-user-id db-con username)]
    (:count (jdbc/execute-one! db-con
                               (sql/format {:select [:%count.counter]
                                            :from :webauthn_authenticators
                                            :where [:= :user_id user-id]})
                               db/rs-opts))))

(defn inc-login-count
  "Increments login count for the given authenticator."
  [db-con authenticator-id]
  (jdbc/execute-one! db-con
                     (sql/format {:update :webauthn_authenticators
                                  :set {:login_count [:+ :login_count 1]}
                                  :where [:= :authn_id authenticator-id]})))

(defn register-user!
  "Callback function for user registration."
  [username authenticator]
  (save-authenticator db/postgres-ds
                      username
                      authenticator))

;; Handlers
(defn wa-prepare-register
  "Function for getting user register preparation data."
  [request]
  (reset! authenticator-name (get-in request [:params :name]))
  (-> (get-in request [:params :username])
      (webauthn/prepare-registration site-properties)
      generate-string
      resp/response))

(defn wa-register
  "User registration function."
  [request]
  (if-let [user (webauthn/register-user (:params request)
                                        site-properties
                                        register-user!)]
    (resp/created "/login" (generate-string user))
    (resp/status 500)))

(defn do-prepare-login
  "Function doing the login preparation."
  [request db-con]
  (let [username (get-in request [:params :username])
        authenticators (get-authenticators db-con username)]
    (when (> @current-authn-number (dec (count authenticators)))
      (reset! current-authn-number 0))
    (if-let [resp (webauthn/prepare-login username
                                          #(:authn
                                            (nth authenticators
                                                 @current-authn-number) %))]
      (resp/response (generate-string resp))
      (resp/status 500))))

(defn wa-prepare-login
  "Function for getting user login preparation data."
  [request]
  (do-prepare-login request db/postgres-ds))

(defn wa-login
  "User login function."
  [{session :session :as request}]
  (let [payload (:params request)]
    (if (empty? payload)
      (do
        (swap! current-authn-number inc)
        (resp/status (resp/response
                      (generate-string {:error "invalid-authenticator"})) 403))
      (let [username (b64/decode (:user-handle payload))
            authenticators (get-authenticators db/postgres-ds
                                               username)
            auth (nth authenticators @current-authn-number)]
        (reset! current-authn-number 0)
        (if (webauthn/login-user payload site-properties #(:authn auth %))
          (do
            (inc-login-count db/postgres-ds (:id auth))
            (assoc (resp/response (str "/" (get-conf-value :url-path) "/"))
                   :session (assoc session :identity (keyword username))))
          (resp/status 500))))))
