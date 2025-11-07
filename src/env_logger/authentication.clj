(ns env-logger.authentication
  "A namespace for authentication related functions"
  (:require [config.core :refer [env]]
            [taoensso.timbre :refer [error]]
            [env-logger.render :refer [serve-text]])
  (:import org.jose4j.jwk.HttpsJwks
           org.jose4j.keys.resolvers.HttpsJwksVerificationKeyResolver
           org.jose4j.jwt.JwtClaims
           (org.jose4j.jwt.consumer InvalidJwtException JwtConsumer JwtConsumerBuilder)))

;; Helpers

(def response-unauthorized {:status 401
                            :headers {"Content-Type" "text/plain"}
                            :body "Unauthorized"})
(def response-server-error {:status 500
                            :headers {"Content-Type" "text/plain"}
                            :body "Internal Server Error"})

;; OpenID Connect

(def id-token-valid (atom false))

(defn- validate-access-token
  "Validate provided access token in JWT format."
  [jwt-string]
  (let [http-jwks (HttpsJwks. (str (:base-url (:oid-auth env))
                                   "/protocol/openid-connect/certs"))
        http-key-resolver (HttpsJwksVerificationKeyResolver. http-jwks)
        jwt-consumer (-> (JwtConsumerBuilder.)
                         JwtConsumerBuilder/.setRequireExpirationTime
                         (JwtConsumerBuilder/.setAllowedClockSkewInSeconds 30)
                         JwtConsumerBuilder/.setRequireSubject
                         JwtConsumerBuilder/.setRequireIssuedAt
                         (JwtConsumerBuilder/.setExpectedAudience
                          (into-array ["account"]))
                         (JwtConsumerBuilder/.setExpectedIssuer
                          (:base-url (:oid-auth env)))
                         (JwtConsumerBuilder/.setVerificationKeyResolver
                          http-key-resolver)
                         .build)]
    (try
      (contains? (set (:authorised-subject-uuids (:oid-auth env)))
                 (JwtClaims/.getSubject (JwtConsumer/.processToClaims jwt-consumer
                                                                      jwt-string)))
      (catch InvalidJwtException _
        (error "Access token JWT validation failed")
        nil))))

(defn- validate-id-token
  "Validate provided ID token."
  [jwt-string]
  (let [http-jwks (HttpsJwks. (str (:base-url (:oid-auth env))
                                   "/protocol/openid-connect/certs"))
        http-key-resolver (HttpsJwksVerificationKeyResolver. http-jwks)
        jwt-consumer (-> (JwtConsumerBuilder.)
                         (JwtConsumerBuilder/.setExpectedAudience
                          (into-array [(:client-id (:oid-auth env))]))
                         (JwtConsumerBuilder/.setExpectedIssuer
                          (:base-url (:oid-auth env)))
                         (JwtConsumerBuilder/.setVerificationKeyResolver
                          http-key-resolver)
                         ;; Allow big skew because ID tokens have the same value
                         ;; in iat and exp claims
                         (JwtConsumerBuilder/.setAllowedClockSkewInSeconds 2592000)
                         JwtConsumerBuilder/.setRequireIssuedAt
                         JwtConsumerBuilder/.setRequireSubject
                         .build)]
    (try
      (contains? (set (:authorised-subject-uuids (:oid-auth env)))
                 (JwtClaims/.getSubject (JwtConsumer/.processToClaims jwt-consumer
                                                                      jwt-string)))
      (catch InvalidJwtException _
        (error "ID token JWT validation failed")
        nil))))

(defn receive-and-check-id-token
  "Receives and validates the ID token and stores the information about this."
  [request]
  (reset! id-token-valid false)
  (if (validate-id-token (get (:query-params request) "id-token"))
    (do
      (reset! id-token-valid true)
      (serve-text "OK"))
    (serve-text "Not valid")))

(defn user-authorized?
  "Checks if the user is authorised."
  [request]
  (when-let [token (:value (get (:cookies request)
                                "X-Authorization-Token"))]
    (validate-access-token token)))

(defn access-ok?
  "Checks the the user is both authenticated and authorised."
  [request]
  (if-let [bearer-token (get (:headers request) "bearer")]
    (validate-access-token bearer-token)
    (and @id-token-valid (user-authorized? request))))
