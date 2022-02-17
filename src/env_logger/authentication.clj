(ns env-logger.authentication
  "A namespace for authentication related functions"
  (:require [buddy.auth :refer [authenticated?]]
            [buddy.auth.backends
             [session :refer [session-backend]]
             [token :refer [jwe-backend]]]
            [buddy.core.nonce :as nonce]
            [buddy.hashers :as h]
            [buddy.sign.jwt :as jwt]
            [cljwebauthn.core :as webauthn]
            [cljwebauthn.b64 :as b64]
            [cheshire.core :refer [generate-string]]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as js]
            [ring.util.response :as resp]
            [selmer.parser :refer [render-file]]
            [taoensso.timbre :refer [error]]
            [env-logger
             [config :refer [get-conf-value]]
             [db :as db]
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
                                                    ^AuthenticatorImpl
                                                    authenticator)))
          envelope (new AttestationStatementEnvelope (.getAttestationStatement
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
        object-converter (new ObjectConverter)
        credential-converter (new AttestedCredentialDataConverter
                                  object-converter)
        cbor-converter (.getCborConverter object-converter)]
    (for [row (jdbc/execute! db-con
                             (sql/format {:select [:counter
                                                   :attested_credential
                                                   :attestation_statement]
                                          :from [:webauthn_authenticators]
                                          :where [:= :user_id user-id]})
                             db/rs-opts)]
      (new AuthenticatorImpl
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
    (if-let [resp (webauthn/prepare-login username
                                          (fn [_] authenticators))]
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
      (resp/status (resp/response
                    (generate-string {:error "invalid-authenticator"})) 403)
      (let [username (b64/decode (:user-handle payload))
            authenticators (get-authenticators db/postgres-ds
                                               username)]
        (if (webauthn/login-user payload
                                 site-properties
                                 (fn [_] authenticators))
          (assoc (resp/response (str "/" (get-conf-value :url-path) "/"))
                 :session (assoc session :identity (keyword username)))
          (resp/status 500))))))

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
    (resp/status (resp/response "403 Forbidden") 403)
    ;; In other cases, redirect it user to login
    (resp/redirect (format (str (get-conf-value :url-path) "/login?next=%s")
                           (:uri request)))))

(def auth-backend (session-backend
                   {:unauthorized-handler unauthorized-handler}))

(defn check-auth-code
  "Checks whether the authentication code is valid."
  [code-to-check]
  (= (get-conf-value :auth-code) code-to-check))

(defn login-authenticate
  "Check request username and password against user data in the database.
  On successful authentication, set appropriate user into the session and
  redirect to the value of (:query-params (:next request)).
  On failed authentication, renders the login page."
  [request]
  (let [username (get-in request [:form-params "username"])
        password (get-in request [:form-params "password"])
        session (:session request)
        pw-hash (get-pw-hash db/postgres-ds username)]
    (if (:error pw-hash)
      (render-file "templates/error.html"
                   {})
      (if (and pw-hash (h/check password pw-hash))
        (let [next-url (get-in request [:query-params :next]
                               (str (get-conf-value :url-path) "/"))
              updated-session (assoc session :identity (keyword username))]
          (assoc (resp/redirect next-url) :session updated-session))
        (render-file "templates/login.html"
                     {:error
                      "Error: an invalid credential was provided"})))))

(defn token-login
  "Login method for getting an token for data access."
  [request]
  (let [auth-data (get-conf-value :data-user-auth-data)
        username (get-in request [:params :username])
        password (get-in request [:params :password])
        valid? (and (and username
                         (= username (:username auth-data)))
                    (and password
                         (h/check password (:password auth-data))))]
    (if valid?
      (let [claims {:user (keyword username)
                    :exp (. (. (. Instant now) plusSeconds
                               (get-conf-value :jwt-token-timeout))
                            getEpochSecond)}
            token (jwt/encrypt claims jwe-secret {:alg :a256kw :enc :a128gcm})]
        token)
      response-unauthorized)))
