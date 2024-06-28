(ns env-logger.authentication
  "A namespace for authentication related functions"
  (:require [config.core :refer [env]]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.backends
             [session :refer [session-backend]]
             [token :refer [jwe-backend]]]
            [buddy.core.nonce :as nonce]
            [buddy.hashers :as h]
            [buddy.sign.jwt :as jwt]
            [cljwebauthn.core :as webauthn]
            [cljwebauthn.b64 :as b64]
            [jsonista.core :as j]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as js]
            [ring.util.http-response :refer [created forbidden found ok status]]
            [taoensso.timbre :refer [error]]
            [env-logger
             [db :as db]
             [render :refer [serve-json serve-template serve-text]]
             [user :refer [get-user-id get-pw-hash]]])
  (:import java.time.Instant
           com.webauthn4j.authenticator.AuthenticatorImpl
           com.webauthn4j.converter.AttestedCredentialDataConverter
           com.webauthn4j.converter.util.ObjectConverter
           org.postgresql.util.PSQLException
           webauthn4j.AttestationStatementEnvelope))
(refer-clojure :exclude '[filter for group-by into partition-by set update])
(require '[honey.sql :as sql])

;; WebAuthn

(let [use-https (:use-https (:webauthn env))
      hostname (:hostname (:webauthn env))]
  (def site-properties
    {:site-id hostname
     :site-name "Environment logger app"
     :protocol (if use-https
                 "https" "http")
     :port (if use-https
             443 80)
     :host hostname}))

(def authenticator-name (atom ""))

;; Helper functions
(defn save-authenticator
  "Serialises and saves the given authenticator to the DB."
  [db-con username authenticator]
  (try
    (let [user-id (get-user-id db-con username)
          object-converter (ObjectConverter.)
          credential-converter (AttestedCredentialDataConverter.
                                object-converter)
          cred-base64 (b64/encode-binary (.convert credential-converter
                                                   (.getAttestedCredentialData
                                                    ^AuthenticatorImpl
                                                    authenticator)))
          envelope (AttestationStatementEnvelope. (.getAttestationStatement
                                                   ^AuthenticatorImpl
                                                   authenticator))
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
                                                    envelope))}
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
        object-converter (ObjectConverter.)
        credential-converter (AttestedCredentialDataConverter.
                              object-converter)
        cbor-converter (.getCborConverter object-converter)]
    (for [row (jdbc/execute! db-con
                             (sql/format {:select [:counter
                                                   :attested_credential
                                                   :attestation_statement]
                                          :from [:webauthn_authenticators]
                                          :where [:= :user_id user-id]})
                             db/rs-opts)]
      (AuthenticatorImpl.
       (.convert credential-converter
                 (bytes (b64/decode-binary
                         (:attested-credential row))))
       (.getAttestationStatement
        ^AttestationStatementEnvelope
        (.readValue cbor-converter
                    (bytes (b64/decode-binary
                            (:attestation-statement row)))
                    AttestationStatementEnvelope))
       (:counter row)))))

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
  (reset! authenticator-name (get-in request [:params "name"]))
  (-> (get-in request [:params "username"])
      (webauthn/prepare-registration site-properties)
      j/write-value-as-string
      ok))

(defn wa-register
  "User registration function."
  [request]
  (if-let [user (webauthn/register-user (:body-params request)
                                        site-properties
                                        register-user!)]
    (created "/login" (j/write-value-as-string user))
    (status 500)))

(defn do-prepare-login
  "Function doing the login preparation."
  [request db-con]
  (let [username (get-in request [:params "username"])
        authenticators (get-authenticators db-con username)]
    (if-let [resp (webauthn/prepare-login username
                                          (fn [_] authenticators))]
      (serve-json resp)
      (status 500))))

(defn wa-prepare-login
  "Function for getting user login preparation data."
  [request]
  (do-prepare-login request db/postgres-ds))

(defn wa-login
  "User login function."
  [{session :session :as request}]
  (let [payload (:body-params request)]
    (if (empty? payload)
      (status (serve-json {:error "invalid-authenticator"})
              403)
      (let [username (b64/decode (:user-handle payload))
            authenticators (get-authenticators db/postgres-ds
                                               username)]
        (if (webauthn/login-user payload
                                 site-properties
                                 (fn [_] authenticators))
          (assoc (ok (:app-url env))
                 :session (assoc session :identity (keyword username)))
          (status 500))))))

;; Other functions

(def response-unauthorized {:status 401
                            :headers {"Content-Type" "text/plain"}
                            :body "Unauthorized"})
(def response-server-error {:status 500
                            :headers {"Content-Type" "text/plain"}
                            :body "Internal Server Error"})

(def jwe-secret (nonce/random-bytes 32))

(def jwe-auth-backend (jwe-backend {:secret jwe-secret
                                    :options {:alg :a256kw :enc :a128gcm}}))

(defn unauthorized-handler
  "Handles unauthorized requests."
  [request _]
  (if (authenticated? request)
    ;; If request is authenticated, raise 403 instead of 401 as the user
    ;; is authenticated but permission denied is raised.
    (forbidden "403 Forbidden")
    ;; In other cases, redirect it user to login
    (found (str (:app-url env) "login"))))

(def auth-backend (session-backend
                   {:unauthorized-handler unauthorized-handler}))

(defn check-auth-code
  "Checks whether the authentication code is valid."
  [code-to-check]
  (= (:auth-code env) code-to-check))

(defn login-authenticate
  "Check request username and password against user data in the database.
  On successful authentication, set appropriate user into the session and
  redirect to the start page.
  On failed authentication, renders the login page."
  [request]
  (let [username (get-in request [:form-params "username"])
        password (get-in request [:form-params "password"])
        session (:session request)
        pw-hash (get-pw-hash db/postgres-ds username)]
    (if (:error pw-hash)
      (serve-template "templates/error.html"
                      {})
      (if (and pw-hash (h/check password pw-hash))
        (let [updated-session (assoc session :identity (keyword username))]
          (assoc (found (:app-url env))
                 :session updated-session))
        (serve-template "templates/login.html"
                        {:error
                         "Error: an invalid credential was provided"})))))

(defn token-login
  "Login method for getting an token for data access."
  [request]
  (let [auth-data (:data-user-auth-data env)
        username (get-in request [:params "username"])
        password (get-in request [:params "password"])
        valid? (and (and username
                         (= username (:username auth-data)))
                    (and password
                         (h/check password (:password auth-data))))]
    (if valid?
      (let [claims {:user (keyword username)
                    :exp (.getEpochSecond
                          (.plusSeconds (Instant/now)
                                        (:jwt-token-timeout env)))}
            token (jwt/encrypt claims jwe-secret {:alg :a256kw :enc :a128gcm})]
        (serve-text token))
      response-unauthorized)))
