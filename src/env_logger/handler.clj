(ns env-logger.handler
  "The main namespace of the application"
  (:require [clojure set]
            [config.core :refer [env]]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [java-time :as t]
            [jsonista.core :as j]
            [next.jdbc :as jdbc]
            [reitit.ring :as ring]
            [reitit.ring.middleware
             [parameters :as parameters]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware
             [defaults :refer [wrap-defaults
                               site-defaults
                               secure-site-defaults]]
             [json :refer [wrap-json-params]]
             [reload :refer [wrap-reload]]]
            [ring.util.response :as resp]
            [env-logger
             [authentication :as auth]
             [db :as db]
             [render :refer [serve-text serve-json serve-template]]
             [weather :refer [calculate-start-time
                              get-fmi-weather-data
                              store-weather-data?
                              get-weather-data
                              fetch-all-weather-data]]])
  (:import java.time.Instant)
  (:gen-class))

(def json-decode-opts
  "Options for read-value"
  (j/object-mapper {:decode-key-fn true}))

(defn convert-epoch-ms-to-string
  "Converts an Unix epoch timestamp to a 'human readable' value."
  [epoch-ts]
  (t/format "d.L.Y HH:mm:ss"
            (t/plus (t/zoned-date-time
                     (str (Instant/ofEpochMilli epoch-ts)))
                    (t/hours (db/get-tz-offset
                              (:store-timezone env))))))

(defn get-latest-obs-data
  "Get data for the latest observation."
  [request]
  (if-not (authenticated? (if (:identity request)
                            request (:session request)))
    auth/response-unauthorized
    (with-open [con (jdbc/get-connection db/postgres-ds)]
      (let [data (first (reverse (db/get-obs-days con 1)))
            rt-data (sort-by :location
                             (take (count (:ruuvitag-locations env))
                                   (reverse (db/get-ruuvitag-obs
                                             con
                                             (t/minus (t/local-date-time)
                                                      (t/minutes 45))
                                             (t/local-date-time)
                                             (:ruuvitag-locations env)))))]
        (if-not data
          (serve-json {:data []
                       :rt-data []
                       :weather-data []})
          (serve-json {:data (assoc data :recorded
                                    (convert-epoch-ms-to-string
                                     (:recorded data)))
                       :rt-data (for [item rt-data]
                                  (assoc item :recorded
                                         (convert-epoch-ms-to-string
                                          (:recorded item))))
                       :weather-data (get-fmi-weather-data)}))))))

(defn get-plot-page-data
  "Returns data needed for rendering the plot page."
  [request]
  (with-open [con (jdbc/get-connection db/postgres-ds)]
    (let [start-date-val (get (:params request) "startDate")
          start-date (when (seq start-date-val) start-date-val)
          end-date-val (get (:params request) "endDate")
          end-date (when (seq end-date-val) end-date-val)
          obs-dates (db/get-obs-date-interval con)
          logged-in? (authenticated? (:session request))
          initial-days (:initial-show-days env)
          ruuvitag-locations (:ruuvitag-locations env)]
      (if (or start-date end-date)
        {:obs-data (if logged-in?
                     (db/get-obs-interval con
                                          {:start start-date
                                           :end end-date})
                     (db/get-weather-obs-interval con
                                                  {:start start-date
                                                   :end end-date}))
         :rt-data (when logged-in?
                    (db/get-ruuvitag-obs
                     con
                     (db/make-local-dt start-date "start")
                     (db/make-local-dt end-date "end")
                     ruuvitag-locations))
         :start-date start-date
         :end-date end-date}
        {:obs-data (if logged-in?
                     (db/get-obs-days con
                                      initial-days)
                     (db/get-weather-obs-days con
                                              initial-days))
         :rt-data (when logged-in?
                    (db/get-ruuvitag-obs
                     con
                     (t/minus (t/local-date-time)
                              (t/days initial-days))
                     (t/local-date-time)
                     ruuvitag-locations))
         :start-date (when (:end obs-dates)
                       (t/format (t/formatter :iso-local-date)
                                 (t/minus (t/local-date (t/formatter
                                                         :iso-local-date)
                                                        (:end obs-dates))
                                          (t/days initial-days))))
         :end-date (:end obs-dates)}))))

(defn get-display-data
  "Returns the display data."
  [request]
  (serve-json
   (merge {:weather-data
           (if-not (authenticated? (:session request))
             (:current (:fmi (get-weather-data)))
             (get-weather-data))}
          (merge {:mode (if (authenticated? (:session request)) "all" "weather")
                  :tb-image-basepath (:tb-image-basepath env)}
                 (when (authenticated? (:session request))
                   {:rt-names (:ruuvitag-locations env)
                    :rt-default-show (:ruuvitag-default-show env)
                    :rt-default-values (:ruuvitag-default-values env)}))
          (get-plot-page-data request))))

(defn handle-observation-insert
  "Handles the insertion of an observation to the database."
  [observation]
  (with-open [con (jdbc/get-connection db/postgres-ds)]
    (let [start-time (calculate-start-time)
          start-time-int (t/interval (t/plus start-time (t/minutes 4))
                                     (t/plus start-time (t/minutes 7)))
          weather-data (when (and (t/contains? start-time-int
                                               (t/zoned-date-time))
                                  (store-weather-data? con))
                         (get-fmi-weather-data))]
      (db/insert-observation con
                             (assoc observation
                                    :weather-data weather-data)))))

(defn observation-insert
  "Function called when an observation is posted."
  [request]
  (fetch-all-weather-data)
  (if-not (auth/check-auth-code (get (:params request) "code"))
    auth/response-unauthorized
    (if-not (db/test-db-connection db/postgres-ds)
      auth/response-server-error
      (let [observation (j/read-value (get (:params request)
                                           "obs-string")
                                      json-decode-opts)]
        (if-not (= (count observation) 4)
          (resp/bad-request "Bad request")
          (if (handle-observation-insert observation)
            (serve-text "OK") auth/response-server-error))))))

(defn rt-observation-insert
  "Function called when an RuuviTag observation is posted."
  [request]
  (if-not (auth/check-auth-code (get (:params request) "code"))
    auth/response-unauthorized
    (with-open [con (jdbc/get-connection db/postgres-ds)]
      (if-not (db/test-db-connection con)
        auth/response-server-error
        (if (pos? (db/insert-ruuvitag-observation
                   con
                   (j/read-value (get (:params request) "observation")
                                 json-decode-opts)))
          (serve-text "OK") auth/response-server-error)))))

(defn tb-image-insert
  "Function called when an RuuviTag observation is posted."
  [request]
  (if-not (auth/check-auth-code (get (:params request) "code"))
    auth/response-unauthorized
    (with-open [con (jdbc/get-connection db/postgres-ds)]
      (if-not (db/test-db-connection con)
        auth/response-server-error
        (let [image-name (get (:params request) "name")]
          (if (re-find #"testbed-\d{4}-\d{2}-\d{2}T\d{2}:\d{2}\+\d{4}\.png"
                       image-name)
            (if (db/insert-tb-image-name con
                                         (db/get-last-obs-id con)
                                         image-name)
              (serve-text "OK") auth/response-server-error)
            (resp/bad-request "Bad request")))))))

(defn get-middleware
  "Returns the middlewares to be applied."
  []
  (let [dev-mode (:development-mode env)
        defaults (if dev-mode
                   site-defaults
                   secure-site-defaults)
        ;; CSRF protection is knowingly not implemented
        ;; :params and :static options are disabled as Reitit handles them
        defaults-config (-> defaults
                            (assoc-in [:security :anti-forgery]
                                      false)
                            (assoc :params false)
                            (assoc :static false))]
    [[wrap-authorization auth/auth-backend]
     [wrap-authentication auth/jwe-auth-backend auth/auth-backend]
     parameters/parameters-middleware
     ;; TODO replace with muuntaja
     [wrap-json-params {:keywords? true}]
     [wrap-defaults (if dev-mode
                      defaults-config
                      (if (:force-hsts env)
                        (assoc defaults-config :proxy true)
                        (-> defaults-config
                            (assoc-in [:security :ssl-redirect]
                                      false)
                            (assoc-in [:security :hsts]
                                      false))))]]))

(def app
  (ring/ring-handler
   (ring/router
    ;; Index and login
    [["/" {:get #(if-not (db/test-db-connection db/postgres-ds)
                   (serve-template "templates/error.html"
                                   {})
                   (serve-template "templates/chart.html"
                                   {:logged-in? (authenticated? (:session
                                                                 %))
                                    :obs-dates (db/get-obs-date-interval
                                                db/postgres-ds)}))}]
     ["/login" {:get #(if-not (authenticated? (:session %))
                        (serve-template "templates/login.html" {})
                        (resp/redirect (:application-url env)))
                :post auth/login-authenticate}]
     ["/logout" {:get (fn [_]
                        (assoc (resp/redirect (:application-url env))
                               :session {}))}]
     ["/token-login" {:post auth/token-login}]
     ;; WebAuthn routes
     ["/register" {:get #(if-not (authenticated? (:session %))
                           auth/response-unauthorized
                           (serve-template "templates/register.html"
                                           {:username
                                            (name (get-in %
                                                          [:session
                                                           :identity]))}))}]
     ["/webauthn"
      ["/register" {:get auth/wa-prepare-register
                    :post auth/wa-register}]
      ["/login" {:get auth/wa-prepare-login
                 :post auth/wa-login}]]
     ;; Data query
     ["/display-data" {:get get-display-data}]
     ["/get-latest-obs" {:get get-latest-obs-data}]
     ["/get-weather-data" {:get #(if-not (authenticated? (if (:identity %)
                                                           % (:session %)))
                                   auth/response-unauthorized
                                   (serve-json (get-weather-data)))}]
     ;; Observation storing
     ["/observations" {:post observation-insert}]
     ;; RuuviTag observation storage
     ["/rt-observations" {:post rt-observation-insert}]
     ;; Testbed image name storage
     ["/tb-image" {:post tb-image-insert}]])
   (ring/routes
    (ring/create-resource-handler {:path "/"})
    (ring/create-default-handler))
   {:middleware (get-middleware)}))

(defn -main
  "Starts the web server."
  []
  (let [port (Integer/parseInt (get (System/getenv)
                                    "APP_PORT" "8080"))]
    (run-jetty (if (:development-mode env)
                 (wrap-reload #'app) #'app)
               {:port port})))
