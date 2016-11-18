(ns env-logger.handler
  "The main namespace of the application"
  (:require [cheshire.core :refer [generate-string parse-string]]
            [compojure.core :refer [GET POST defroutes]]
            [compojure.route :as route]
            [immutant.web :as web]
            [immutant.web.middleware :refer [wrap-development]]
            [ring.middleware.defaults :refer :all]
            [ring.util.response :as resp]
            [selmer.parser :refer [render-file]]
            [clj-time.core :as t]
            [buddy.hashers :as h]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [env-logger.config :refer [get-conf-value]]
            [env-logger.db :as db]
            [env-logger.grabber :refer [calculate-start-time
                                        get-latest-fmi-data]])
  (:import (com.yubico.client.v2 YubicoClient))
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
        user-data (db/get-user-data db/postgres username)]
    (if (or (and user-data (h/check password (:pw-hash user-data)))
            (and (seq otp)
                 (otp-value-valid? otp)
                 (contains? (:yubikey-ids user-data)
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

(def auth-backend (session-backend
                   {:unauthorized-handler unauthorized-handler}))

(defroutes routes
  ;; Index and login
  (GET "/" [ & params]
       (render-file "templates/plots.html"
                    (let [start-date (:startDate params)
                          end-date (:endDate params)]
                      (if (or (not (nil? start-date))
                              (not (nil? end-date)))
                        {:data (generate-string
                                (db/get-obs-within-interval
                                 db/postgres
                                 (if (not= "" start-date)
                                   start-date nil)
                                 (if (not= "" end-date)
                                   end-date nil)))
                         :start-date (if (not= "" start-date)
                                       start-date "")
                         :end-date (if (not= "" end-date)
                                     end-date nil)
                         :obs-dates (db/get-obs-start-and-end db/postgres)}
                        {:data (generate-string
                                (db/get-last-n-days-obs db/postgres 3))
                         :obs-dates (db/get-obs-start-and-end db/postgres)}))))
  (GET "/login" [] (render-file "templates/login.html" {}))
  (POST "/login" [] login-authenticate)
  (GET "/logout" request
       (assoc (resp/redirect (str (get-conf-value :url-path) "/"))
              :session {}))
  ;; Observation adding and getting
  (POST "/observations" [json-string]
        (let [start-time (calculate-start-time)
              start-time-int (t/interval (t/plus start-time
                                                 (t/seconds 45))
                                         (t/plus start-time
                                                 (t/minutes 3)))
              weather-data (if (t/within? start-time-int (t/now))
                             (get-latest-fmi-data (get-conf-value :fmi-api-key)
                                                  (get-conf-value :station-id))
                             {})
              obs-data (parse-string json-string true)]
          (generate-string (db/insert-observation
                            db/postgres
                            (assoc obs-data :weather-data weather-data)))))
  (GET "/observations" [] {:headers {"Content-Type" "application/json"}
                           :body (generate-string (db/get-all-obs
                                                   db/postgres))})
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
                  (wrap-defaults $ defaults-config))
        opts {:host ip :port port}]
    (web/run (if production?
               handler
               (wrap-development handler))
      opts)))
