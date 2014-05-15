(ns env-logger.main
  (:require [compojure.route :as route]            
            [compojure.handler :refer [site]] 
      	    [compojure.core :refer [defroutes GET POST]]
      	    [org.httpkit.server :as kit]
            [env-logger.db :as db]))
 
(defroutes routes
  (GET "/" [] "Hello HTTP!")
  (GET "/add" [] (db/insert-observation {:timestamp "2014-05-15T15:18:18.766910+03"
                                      :temperature 22
                                      :brightness 150}))
  ;(route/files "/static/") ;; static file url prefix /static, in `public` folder
  (route/not-found "<p>Page not found.</p>"))

(defn -main [& args]
  (let [port (Integer/parseInt (get (System/getenv) "OPENSHIFT_CLOJURE_HTTP_PORT" "8080"))]
    (let [ip (get (System/getenv) "OPENSHIFT_CLOJURE_HTTP_IP" "0.0.0.0")]
      (kit/run-server (site #'routes) {:ip ip :port port}))))
