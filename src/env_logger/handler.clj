(ns env-logger.handler
  (:require [cheshire.core :refer [generate-string parse-string]]
            [compojure.core :refer [GET defroutes]]
            [compojure.route :as route]
            [env-logger.config :refer [get-conf-value]]
            [env-logger.db :as db]
            [immutant.web :refer [run run-dmc]]
            [ring.middleware.defaults :refer :all]
            [selmer.parser :refer [render-file]]))

(defroutes routes
  (GET "/" [] "Welcome to the environment log viewer!")
  (GET "/add" [json-string] (generate-string (db/insert-observation
                                              (parse-string json-string true))))
  (GET "/fetch" [] {:headers {"Content-Type" "application/json"}
                    :body (generate-string (db/get-all-obs))})
  (GET "/plots" [ & params] (render-file "templates/plots.html"
                                         {:data (generate-string
                                                 (if (:all-data params)
                                                   (db/get-all-obs)
                                                   (db/get-last-n-days-obs
                                                    3)))}))
  ;; Serve static files
  (route/files "/" {:root "resources"})
  (route/not-found "404 Not Found"))

(defn -main
  "Starts the web server."
  []
  (let [ip (get (System/getenv) "OPENSHIFT_CLOJURE_HTTP_IP" "localhost")
        port (Integer/parseInt (get (System/getenv)
                                    "OPENSHIFT_CLOJURE_HTTP_PORT" "8080"))
        opts {:host ip :port port}]
    (if (get-conf-value :in-production)
      (run (wrap-defaults routes site-defaults) opts)
      (run-dmc (wrap-defaults routes site-defaults) opts))))
