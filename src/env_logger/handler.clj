(ns env-logger.handler
  "The main namespace of the application"
  (:gen-class)
  (:require [buddy
             [auth :refer [authenticated?]]
             [hashers :as h]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [cheshire.core :refer [generate-string parse-string]]
            [clj-time
             [core :as t]
             [format :as f]]
            [clojure set
             [string :as s]]
            [clojure.tools.logging :as log]
            [compojure
             [core :refer [defroutes DELETE GET POST]]
             [route :as route]]
            [env-logger
             [config :refer [get-conf-value]]
             [db :as db]
             [grabber :refer [calculate-start-time
                              get-latest-fmi-weather-data
                              weather-query-ok?]]
             [user :as u]]
            [immutant.web :as web]
            [ring.middleware.defaults :refer :all]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.util.response :as resp]
            [selmer.parser :refer [render-file]])
  (:import com.yubico.client.v2.YubicoClient))

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
                   (contains? (u/get-yubikey-id db/postgres username)
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

(def auth-backend (session-backend
                   {:unauthorized-handler unauthorized-handler}))

(defn data-custom-dates
  "Gets data for a custom date range."
  [logged-in? start-date end-date]
  (generate-string
   (if logged-in?
     (db/combine-db-and-ruuvitag-obs (db/get-obs-interval db/postgres
                                                          {:start start-date
                                                           :end end-date})
                                     (db/get-ruuvitag-obs
                                      db/postgres
                                      (db/make-local-dt start-date "start")
                                      (db/make-local-dt end-date "end")
                                      (get-conf-value :ruuvitag-locations)))
     (db/get-weather-obs-interval db/postgres
                                  {:start start-date
                                   :end end-date}))))

(defn data-default-dates
  "Gets data for the default date range."
  [logged-in? initial-days]
  (generate-string
   (if logged-in?
     (db/combine-db-and-ruuvitag-obs (db/get-obs-days db/postgres
                                                      initial-days)
                                     (db/get-ruuvitag-obs
                                      db/postgres
                                      (t/minus (t/now)
                                               (t/days initial-days))
                                      (t/now)
                                      (get-conf-value :ruuvitag-locations)))
     (db/get-weather-obs-days db/postgres
                              initial-days))))

(defn yc-image-validity-check
  "Checks whether the yardcam image has the right format and is not too old.
  Returns true when the image name is valid and false otherwise."
  [image-name]
  (let [formatter (f/formatter "Y-M-d H:mZ")
        pattern #"^yc-(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}\+[\d:]+).+$"]
    (boolean (and image-name
                  (re-find pattern image-name)
                  (<= (t/in-minutes (t/interval
                                     (f/parse formatter
                                              (s/replace
                                               (nth
                                                (re-matches pattern
                                                            image-name)
                                                1)
                                               "T" " "))
                                     (t/now)))
                      (get-conf-value :yc-max-time-diff))))))

(defn get-plot-page-data
  "Returns data needed for rendering the plot page."
  [request]
  (let [start-date (when (seq (:startDate (:params request)))
                     (:startDate (:params request)))
        end-date (when (seq (:endDate (:params request)))
                   (:endDate (:params request)))
        obs-dates (merge (db/get-obs-start-date db/postgres)
                         (db/get-obs-end-date db/postgres))
        formatter (f/formatter "y-MM-dd")
        logged-in? (authenticated? request)
        initial-days (get-conf-value :initial-show-days)
        common-values {:obs-dates obs-dates
                       :logged-in? logged-in?
                       :yc-image-basepath (get-conf-value
                                           :yc-image-basepath)
                       :tb-image-basepath (get-conf-value
                                           :tb-image-basepath)}]
    (merge common-values
           (if (or start-date end-date)
             {:data (data-custom-dates logged-in?
                                       start-date
                                       end-date)
              :start-date start-date
              :end-date end-date}
             {:data (data-default-dates logged-in?
                                        initial-days)
              :start-date
              (f/unparse formatter
                         (t/minus (f/parse formatter
                                           (:end obs-dates))
                                  (t/days initial-days)))
              :end-date (:end obs-dates)}))))

(defn handle-observation-insert
  "Handles the insertion of an observation to the database."
  [obs-string]
  (let [start-time (calculate-start-time)
        start-time-int (t/interval (t/plus start-time
                                           (t/minutes 4))
                                   (t/plus start-time
                                           (t/minutes 7)))
        weather-data (when (and (t/within? start-time-int (t/now))
                                (weather-query-ok? db/postgres 3))
                       (get-latest-fmi-weather-data
                        (get-conf-value :station-id)))]
    (db/insert-observation db/postgres
                           (assoc (parse-string obs-string
                                                true)
                                  :weather-data weather-data))))

(defroutes routes
  ;; Index and login
  (GET "/" request
       (if-not (db/test-db-connection db/postgres)
         (render-file "templates/error.html"
                      {})
         (render-file "templates/plots.html"
                      (get-plot-page-data request))))
  (GET "/login" [] (render-file "templates/login.html" {}))
  (POST "/login" [] login-authenticate)
  (GET "/logout" request
       (assoc (resp/redirect (str (get-conf-value :url-path) "/"))
              :session {}))
  ;; Observation storing
  (POST "/observations" request
        (if-not (check-auth-code (:code (:params request)))
          response-unauthorized
          (if-not (db/test-db-connection db/postgres)
            response-server-error
            (if (handle-observation-insert (:obs-string (:params request)))
              "OK" response-server-error))))
  ;; RuuviTag observation storage
  (POST "/rt-observations" request
        (if-not (check-auth-code (:code (:params request)))
          response-unauthorized
          (if-not (db/test-db-connection db/postgres)
            response-server-error
            (if (pos? (db/insert-ruuvitag-observation
                       db/postgres
                       (parse-string (:observation (:params request)) true)))
              "OK" response-server-error))))
  ;; Testbed image name storage
  (POST "/tb-image" request
        (if-not (check-auth-code (:code (:params request)))
          response-unauthorized
          (if-not (db/test-db-connection db/postgres)
            response-server-error
            (if (re-find #"testbed-\d{4}-\d{2}-\d{2}T\d{2}:\d{2}\+\d{4}\.png"
                         (:name (:params request)))
              (if (db/insert-tb-image-name db/postgres
                                           (db/get-last-obs-id
                                            db/postgres)
                                           (:name (:params
                                                   request)))
                "OK" response-server-error)
              response-invalid-request))))
  ;; Latest yardcam image name storage
  (POST "/yc-image" request
        (if-not (check-auth-code (:code (:params request)))
          response-unauthorized
          (if-not (db/test-db-connection db/postgres)
            response-server-error
            (let [image-name (:image-name (:params request))]
              (if (yc-image-validity-check image-name)
                (if (db/insert-yc-image-name db/postgres
                                             image-name)
                  "OK" response-server-error)
                response-invalid-request)))))
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
               (wrap-reload handler))
      opts)))
