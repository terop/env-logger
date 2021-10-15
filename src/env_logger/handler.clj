(ns env-logger.handler
  "The main namespace of the application"
  (:gen-class)
  (:require [clojure set
             [string :as s]]
            [clojure.tools.logging :as log]
            [buddy
             [auth :refer [authenticated?]]
             [hashers :as h]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.backends.token :refer [jwe-backend]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [buddy.core.nonce :as nonce]
            [buddy.sign.jwt :as jwt]
            [cheshire.core :refer [generate-string parse-string]]
            [java-time :as t]
            [compojure
             [core :refer [defroutes DELETE GET POST]]
             [route :as route]]
            [next.jdbc :as jdbc]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults :refer :all]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.json :refer [wrap-json-params wrap-json-response]]
            [ring.util.response :as resp]
            [selmer.parser :refer [render-file]]
            [env-logger
             [config :refer [get-conf-value]]
             [db :as db]
             [grabber :refer [calculate-start-time
                              get-fmi-weather-data
                              weather-query-ok?]]
             [owm :refer [get-owm-data]]
             [user :as u]])
  (:import java.time.Instant
           com.yubico.client.v2.YubicoClient))

(defn otp-value-valid?
  "Checks whether the provided Yubico OTP value is valid. Returns true
  on success and false otherwise."
  [otp-value]
  (let [client (YubicoClient/getClient
                (Integer/parseInt (get-conf-value :yubico-client-id))
                (get-conf-value :yubico-secret-key))]
    (if-not (YubicoClient/isValidOTPFormat otp-value)
      false
      (.isOk (.verify client otp-value)))))

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
        otp (get-in request [:form-params "otp"])
        session (:session request)
        use-ldap? (get-conf-value :use-ldap)
        user-data (if use-ldap?
                    (when-let [password (u/get-password-from-ldap username)]
                      {:pw-hash password})
                    (u/get-user-data db/postgres username))]
    (if (:error user-data)
      (render-file "templates/error.html"
                   {})
      (if (or (and user-data (h/check password (:pw-hash user-data)))
              (and (seq otp)
                   (otp-value-valid? otp)
                   (contains? (u/get-yubikey-id db/postgres-ds username)
                              (YubicoClient/getPublicId otp))))
        (let [next-url (get-in request [:query-params :next]
                               (str (get-conf-value :url-path) "/"))
              updated-session (assoc session :identity (keyword username))]
          (assoc (resp/redirect next-url) :session updated-session))
        (render-file "templates/login.html"
                     {:error "Error: an invalid credential was provided"})))))

(defn unauthorized-handler
  "Handles unauthorized requests."
  [request metadata]
  (if (authenticated? request)
    ;; If request is authenticated, raise 403 instead of 401 as the user
    ;; is authenticated but permission denied is raised.
    (assoc (resp/response "403 Forbidden") :status 403)
    ;; In other cases, redirect it user to login
    (resp/redirect (format (str (get-conf-value :url-path) "/login?next=%s")
                           (:uri request)))))

(def response-unauthorized {:status 401
                            :headers {"Content-Type" "text/plain"}
                            :body "Unauthorized"})
(def response-invalid-request {:status 400
                               :headers {"Content-Type" "text/plain"}
                               :body "Invalid request"})
(def response-server-error {:status 500
                            :headers {"Content-Type" "text/plain"}
                            :body "Internal Server Error"})

(def jwe-secret (nonce/random-bytes 32))

(def jwe-auth-backend (jwe-backend {:secret jwe-secret
                                    :options {:alg :a256kw :enc :a128gcm}}))

(def auth-backend (session-backend
                   {:unauthorized-handler unauthorized-handler}))

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

(defn convert-epoch-ms-to-string
  "Converts an Unix epoch timestamp to a 'human readable' value."
  [epoch-ts]
  (t/format "d.L.Y HH:mm:ss"
            (t/plus (t/zoned-date-time
                     (str (Instant/ofEpochMilli epoch-ts)))
                    (t/hours (db/get-tz-offset
                              (get-conf-value :store-timezone))))))

(defn get-last-obs-data
  "Get data for observation with a non-null FMI temperature value."
  [request]
  (if-not (authenticated? request)
    response-unauthorized
    (with-open [con (jdbc/get-connection db/postgres-ds)]
      (let [data (first (filter #(not (nil? (:fmi_temperature %)))
                                (reverse (db/get-obs-days con 1))))
            rt-data (sort-by :location
                             (take (count (get-conf-value :ruuvitag-locations))
                                   (reverse (db/get-ruuvitag-obs
                                             con
                                             (t/minus (t/local-date-time)
                                                      (t/minutes 45))
                                             (t/local-date-time)
                                             (get-conf-value
                                              :ruuvitag-locations)))))]
        {:status 200
         :body {:data (assoc data :recorded
                             (convert-epoch-ms-to-string (:recorded data)))
                :rt-data (for [item rt-data]
                           (assoc item :recorded
                                  (convert-epoch-ms-to-string
                                   (:recorded item))))}}))))

(defn yc-image-validity-check
  "Checks whether the yardcam image has the right format and is not too old.
  Returns true when the image name is valid and false otherwise."
  [image-name]
  (boolean (and image-name
                (re-find db/yc-image-pattern image-name)
                (<= (t/as (t/interval (t/zoned-date-time
                                       (t/formatter :iso-offset-date-time)
                                       (nth (re-find db/yc-image-pattern
                                                     image-name)
                                            1))
                                      (t/zoned-date-time))
                          :minutes)
                    (get-conf-value :image-max-time-diff)))))

(defn get-plot-page-data
  "Returns data needed for rendering the plot page."
  [request]
  (with-open [con (jdbc/get-connection db/postgres-ds)]
    (let [start-date (when (seq (:startDate (:params request)))
                       (:startDate (:params request)))
          end-date (when (seq (:endDate (:params request)))
                     (:endDate (:params request)))
          obs-dates (db/get-obs-date-interval con)
          logged-in? (authenticated? request)
          initial-days (get-conf-value :initial-show-days)
          common-values {:obs-dates obs-dates
                         :logged-in? logged-in?
                         :yc-image-basepath (get-conf-value
                                             :yc-image-basepath)
                         :tb-image-basepath (get-conf-value
                                             :tb-image-basepath)
                         :rt-names (generate-string
                                    (get-conf-value :ruuvitag-locations))
                         :hide-rt (generate-string (get-conf-value
                                                    :hide-ruuvitag-data))}]
      (merge common-values
             (if (or start-date end-date)
               {:data (generate-string
                       (if logged-in?
                         (db/get-obs-interval con
                                              {:start start-date
                                               :end end-date})
                         (db/get-weather-obs-interval con
                                                      {:start start-date
                                                       :end end-date})))
                :rt-data (generate-string
                          (when logged-in?
                            (db/get-ruuvitag-obs
                             con
                             (db/make-local-dt start-date "start")
                             (db/make-local-dt end-date "end")
                             (get-conf-value :ruuvitag-locations))))
                :start-date start-date
                :end-date end-date}
               {:data (generate-string
                       (if logged-in?
                         (db/get-obs-days con
                                          initial-days)
                         (db/get-weather-obs-days con
                                                  initial-days)))
                :rt-data (generate-string
                          (when logged-in?
                            (db/get-ruuvitag-obs
                             con
                             (t/minus (t/local-date-time)
                                      (t/days initial-days))
                             (t/local-date-time)
                             (get-conf-value :ruuvitag-locations))))
                :start-date (t/format (t/formatter :iso-local-date)
                                      (t/minus (t/local-date (t/formatter
                                                              :iso-local-date)
                                                             (:end obs-dates))
                                               (t/days initial-days)))
                :end-date (:end obs-dates)})
             (when logged-in?
               {:owm-data (generate-string (get-owm-data))})))))

(defn handle-observation-insert
  "Handles the insertion of an observation to the database."
  [obs-string]
  (with-open [con (jdbc/get-connection db/postgres-ds)]
    (let [start-time (calculate-start-time)
          start-time-int (t/interval (t/plus start-time (t/minutes 4))
                                     (t/plus start-time (t/minutes 7)))
          weather-data (when (and (t/contains? start-time-int
                                               (t/zoned-date-time))
                                  (weather-query-ok? con 3))
                         (get-fmi-weather-data
                          (get-conf-value :station-id)))]
      (db/insert-observation con
                             (assoc (parse-string obs-string
                                                  true)
                                    :weather-data weather-data)))))

(defroutes routes
  ;; Index and login
  (GET "/" request
       (if-not (db/test-db-connection db/postgres-ds)
         (render-file "templates/error.html"
                      {})
         (render-file "templates/chart.html"
                      (get-plot-page-data request))))
  (GET "/login" [] (render-file "templates/login.html" {}))
  (POST "/login" [] login-authenticate)
  (GET "/logout" request
       (assoc (resp/redirect (str (get-conf-value :url-path) "/"))
              :session {}))
  (POST "/token-login" [] token-login)
  (GET "/get-last-obs" [] get-last-obs-data)
  ;; Observation storing
  (POST "/observations" request
        (if-not (check-auth-code (:code (:params request)))
          response-unauthorized
          (if-not (db/test-db-connection db/postgres-ds)
            response-server-error
            (if (handle-observation-insert (:obs-string (:params request)))
              "OK" response-server-error))))
  ;; RuuviTag observation storage
  (POST "/rt-observations" request
        (if-not (check-auth-code (:code (:params request)))
          response-unauthorized
          (if-not (db/test-db-connection db/postgres-ds)
            response-server-error
            (if (pos? (db/insert-ruuvitag-observation
                       db/postgres-ds
                       (parse-string (:observation (:params request)) true)))
              "OK" response-server-error))))
  ;; Testbed image name storage
  (POST "/tb-image" request
        (if-not (check-auth-code (:code (:params request)))
          response-unauthorized
          (if-not (db/test-db-connection db/postgres-ds)
            response-server-error
            (if (re-find #"testbed-\d{4}-\d{2}-\d{2}T\d{2}:\d{2}\+\d{4}\.png"
                         (:name (:params request)))
              (if (db/insert-tb-image-name db/postgres-ds
                                           (db/get-last-obs-id
                                            db/postgres-ds)
                                           (:name (:params
                                                   request)))
                "OK" response-server-error)
              response-invalid-request))))
  ;; Latest yardcam image name storage
  (POST "/yc-image" request
        (if-not (check-auth-code (:code (:params request)))
          response-unauthorized
          (if-not (db/test-db-connection db/postgres-ds)
            response-server-error
            (let [image-name (:image-name (:params request))]
              (if (yc-image-validity-check image-name)
                (if (db/insert-yc-image-name db/postgres-ds
                                             image-name)
                  "OK" response-server-error)
                response-invalid-request)))))
  ;; Serve static files
  (route/files "/")
  (route/not-found "404 Not Found"))

(defn -main
  "Starts the web server."
  []
  (let [port (Integer/parseInt (get (System/getenv)
                                    "APP_PORT" "8080"))
        production? (get-conf-value :in-production)
        defaults-config (if production?
                          ;; TODO fix CSRF tokens
                          (assoc (assoc-in (assoc-in secure-site-defaults
                                                     [:security :anti-forgery]
                                                     false)
                                           [:security :hsts]
                                           (get-conf-value :use-hsts))
                                 :proxy (get-conf-value :use-proxy))
                          (assoc-in site-defaults
                                    [:security :anti-forgery] false))
        handler (as-> routes $
                  (wrap-authorization $ auth-backend)
                  (wrap-authentication $ jwe-auth-backend auth-backend)
                  (wrap-defaults $ defaults-config)
                  (wrap-json-response $ {:pretty false})
                  (wrap-json-params $ {:keywords? true}))
        opts {:port port}]
    (run-jetty (if production?
                 handler
                 (wrap-reload handler))
               opts)))
