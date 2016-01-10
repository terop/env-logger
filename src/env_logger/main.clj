(ns env-logger.main
  (:require [compojure.route :as route]
            [compojure.handler :refer [site]]
            [compojure.core :refer [defroutes GET POST]]
            [ring.middleware.defaults :refer :all]
            [immutant.web :refer [run run-dmc]]
            [cheshire.core :refer [generate-string parse-string]]
            [selmer.parser :refer [render-file]]
            [env-logger.db :as db]))

(defroutes routes
  (GET "/" [] "Welcome to the environment log viewer!")
  (GET "/add" [json-string] (db/insert-observation (parse-string json-string
                                                                 true)))
  (GET "/fetch" [ & date-format] {:headers {"Content-Type" "application/json"}
                                  :body (generate-string
                                         (db/get-all-obs
                                          (let [df (get date-format
                                                        :date-format "")]
                                            ;; TODO validate date format string
                                            (if (not= df "")
                                              (keyword df) :mysql))))})
  (GET "/plots" [ & params] (render-file "templates/plots.html"
                                         {:data (generate-string
                                                 (if (:all-data params)
                                                   (db/get-all-obs :rfc822)
                                                   (db/get-last-n-days-obs 3
                                                                           :rfc822)))}))
  ;; Serve static files
  (route/files "/" {:root "resources"})
  (route/not-found "404 Not Found"))

(defn -main
  "Starts the web server."
  []
  (let [ip (get (System/getenv) "OPENSHIFT_CLOJURE_HTTP_IP" "localhost")
        port (Integer/parseInt (get (System/getenv)
                                    "OPENSHIFT_CLOJURE_HTTP_PORT" "8080"))]
    (run (wrap-defaults routes site-defaults) {:host ip :port port})))
