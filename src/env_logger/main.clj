(ns env-logger.main
  (:use [compojure.route :as route]
      	[compojure.handler :only [site]] 
      	[compojure.core :only [defroutes GET POST context]]
      	org.httpkit.server))
 
(defroutes all-routes
  (GET "/" [] "Hello HTTP!")
  ;(context "/user/:id" []
  ;         (GET / [] get-user-by-id)
  ;(route/files "/static/") ;; static file url prefix /static, in `public` folder
  (route/not-found "<p>Page not found.</p>"))

(defn -main [& args]
  (let [port (Integer/parseInt (get (System/getenv) "OPENSHIFT_CLOJURE_HTTP_PORT" "8080"))]
    (let [ip (get (System/getenv) "OPENSHIFT_CLOJURE_HTTP_IP" "0.0.0.0")]
  (run-server (site #'all-routes) {:ip ip :port port}))))
