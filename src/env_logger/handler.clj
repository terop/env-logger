(ns env-logger.handler
  "The main namespace of the application"
  (:require [cheshire.core :refer [generate-string parse-string]]
            [compojure.core :refer [GET POST defroutes]]
            [compojure.route :as route]
            [immutant.web :as web]
            [immutant.web.middleware :refer [wrap-development wrap-websocket]]
            [immutant.web.async :as async]
            [ring.middleware.defaults :refer :all]
            [ring.util.response :as resp]
            [selmer.parser :refer [render-file]]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [buddy.hashers :as h]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [clojure.tools.logging :as log]
            [base64-clj.core :as base64]
            [env-logger.config :refer [get-conf-value]]
            [env-logger.db :as db]
            [env-logger.grabber :refer [calculate-start-time
                                        get-latest-fmi-data]])
  (:import com.yubico.client.v2.YubicoClient
           java.io.ByteArrayInputStream)
  (:gen-class))

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
                    (when-let [password (db/get-password-from-ldap username)]
                      {:pw-hash password})
                    (db/get-user-data db/postgres username))]
    (if (or (and user-data (h/check password (:pw-hash user-data)))
            (and (seq otp)
                 (otp-value-valid? otp)
                 (contains? (db/get-yubikey-id db/postgres username)
                            (YubicoClient/getPublicId otp))))
      (let [next-url (get-in request [:query-params :next]
                             (str (get-conf-value :url-path) "/"))
            updated-session (assoc session :identity (keyword username))]
        (assoc (resp/redirect next-url) :session updated-session))
      (render-file "templates/login.html"
                   {:error "Error: an invalid credential was provided"
                    :username username}))))

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

(def auth-backend (session-backend
                   {:unauthorized-handler unauthorized-handler}))

(defonce channels (atom #{}))

(def websocket-callbacks
  "WebSocket callback functions."
  {:on-open (fn [channel]
              (swap! channels conj channel))
   :on-close (fn [channel {:keys [code reason]}]
               (swap! channels clojure.set/difference #{channel}))
   :on-message (fn [ch m]
                 ;; Do nothing as clients are not supposed to send anything
                 )
   :on-error (fn [channel throwable]
               (log/error "WS exception:" throwable))})

(defroutes routes
  ;; Index and login
  (GET "/" request
       (render-file "templates/plots.html"
                    (let [start-date (when (not= "" (:startDate
                                                     (:params request)))
                                       (:startDate (:params request)))
                          end-date (when (not= "" (:endDate (:params request)))
                                     (:endDate (:params request)))
                          obs-dates (db/get-obs-start-and-end db/postgres)
                          formatter (f/formatter "d.M.y")
                          logged-in? (authenticated? request)
                          common-values {:logged-in? logged-in?
                                         :ws-url (get-conf-value :ws-url)
                                         :image-base (get-conf-value
                                                      :image-basepath)}]
                      (if (or (not (nil? start-date))
                              (not (nil? end-date)))
                        (merge common-values
                               {:data (generate-string
                                       (if logged-in?
                                         (db/get-obs-interval
                                          db/postgres
                                          start-date
                                          end-date)
                                         (db/get-weather-obs-interval
                                          db/postgres
                                          start-date
                                          end-date)))
                                :start-date start-date
                                :end-date end-date})
                        (merge common-values
                               {:data (generate-string
                                       (if logged-in?
                                         (db/get-obs-days db/postgres 3)
                                         (db/get-weather-obs-days db/postgres
                                                                  3)))
                                :start-date (f/unparse formatter
                                                       (t/minus (f/parse
                                                                 formatter
                                                                 (:end
                                                                  obs-dates))
                                                                (t/days 3)))
                                :end-date (:end obs-dates)})))))
  (GET "/login" [] (render-file "templates/login.html" {}))
  (POST "/login" [] login-authenticate)
  (GET "/logout" request
       (assoc (resp/redirect (str (get-conf-value :url-path) "/"))
              :session {}))
  ;; Observation storing
  (POST "/observations" request
        (if-not (check-auth-code (:code (:params request)))
          response-unauthorized
          (let [start-time (calculate-start-time)
                start-time-int (t/interval (t/plus start-time
                                                   (t/minutes 4))
                                           (t/plus start-time
                                                   (t/minutes 7)))
                weather-data (when (and (t/within? start-time-int (t/now))
                                        (db/weather-query-ok? db/postgres 3))
                               (get-latest-fmi-data
                                (get-conf-value :fmi-api-key)
                                (get-conf-value :station-id)))
                insert-status (db/insert-observation
                               db/postgres
                               (assoc (parse-string (:obs-string (:params
                                                                  request))
                                                    true)
                                      :weather-data weather-data))]
            (doseq [channel @channels]
              (async/send! channel
                           (generate-string (db/get-observations db/postgres
                                                                 :limit 1))))
            (generate-string insert-status))))
  ;; Testbed image storage
  (POST "/tb-image" request
        (if-not (check-auth-code (:code (:params request)))
          response-unauthorized
          (if (= (db/store-testbed-image db/postgres
                                         (db/get-last-obs-id db/postgres)
                                         (base64/decode-bytes
                                          (.getBytes (:image (:params
                                                              request))))) 1)
            "true" "false")))
  ;; Latest yardcam image name storage
  (POST "/image" request
        (if-not (check-auth-code (:code (:params request)))
          response-unauthorized
          (if (re-find #"yc-\d{4}-\d{2}-\d{2}T\d{2}:\d{2}\+\d{4}\.jpg"
                       (:image-name (:params request)))
            (generate-string (db/insert-yc-image-name db/postgres
                                                      (:image-name (:params
                                                                    request))))
            (generate-string false))))
  ;; Testbed image fetch
  (GET "/tb-image/:id" [id]
       (if (re-find #"[0-9]+"id)
         (if-let [tb-image (db/testbed-image-fetch db/postgres id)]
           (-> (new ByteArrayInputStream tb-image)
               (resp/response)
               (resp/content-type "image/png"))
           {:status 404})
         {:status 404}))
  ;; Serve static files
  (route/files "/")
  (route/not-found "404 Not Found"))

(defn -main
  "Starts the web server."
  []
  (let [ip (get (System/getenv) "APP_IP" "0.0.0.0")
        port (Integer/parseInt (get (System/getenv)
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
                  (wrap-authentication $ auth-backend)
                  (wrap-websocket $ websocket-callbacks)
                  (wrap-defaults $ defaults-config))
        opts {:host ip :port port}]
    (web/run (if production?
               handler
               (wrap-development handler))
      opts)))
