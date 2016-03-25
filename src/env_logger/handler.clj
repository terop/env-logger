(ns env-logger.handler
  (:require [cheshire.core :refer [generate-string parse-string]]
            [compojure.core :refer [GET POST defroutes]]
            [compojure.route :as route]
            [env-logger.config :refer [get-conf-value]]
            [env-logger.db :as db]
            [immutant.web :refer [run run-dmc]]
            [ring.middleware.defaults :refer :all]
            [selmer.parser :refer [render-file]]))

(defroutes routes
  (GET "/" [] (render-file "templates/index.html" {}))
  (GET "/add" [json-string] (generate-string (db/insert-observation
                                              (parse-string json-string true))))
  (GET "/fetch" [] {:headers {"Content-Type" "application/json"}
                    :body (generate-string (db/get-all-obs))})
  (GET "/plots" [] (render-file "templates/plots.html"
                                {:data (generate-string
                                        (db/get-last-n-days-obs 3))}))
  (POST "/plots" [ & params]
        (let [date-one (:dateOne params)
              date-two (:dateTwo params)]
          (render-file "templates/plots.html"
                       {:data (generate-string
                               (db/get-obs-within-interval
                                (if (not= "" date-one)
                                  date-one nil)
                                (if (not= "" date-two)
                                  date-two nil)))
                        :date-one (if (not= "" date-one)
                                    date-one "")
                        :date-two (if (not= "" date-two)
                                    date-two nil)})))
  ;; Serve static files
  (route/files "/" {:root "resources"})
  (route/not-found "404 Not Found"))

(defn -main
  "Starts the web server."
  []
  (let [ip (get (System/getenv) "OPENSHIFT_CLOJURE_HTTP_IP" "localhost")
        port (Integer/parseInt (get (System/getenv)
                                    "OPENSHIFT_CLOJURE_HTTP_PORT" "8080"))
        production? (get-conf-value :in-production)
        defaults-config (if production?
                          (assoc (assoc-in secure-site-defaults [:security
                                                                 :anti-forgery]
                                           false) :proxy true)
                          (assoc-in site-defaults [:security :anti-forgery]
                                    false))
        opts {:host ip :port port}]
    (if production?
      ;; TODO fix CSRF tokens
      (run (wrap-defaults routes defaults-config) opts)
      (run-dmc (wrap-defaults routes defaults-config) opts))))
