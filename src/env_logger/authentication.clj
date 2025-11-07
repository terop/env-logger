(ns env-logger.authentication
  "A namespace for authentication related functions")

;; Helpers

(def response-unauthorized {:status 401
                            :headers {"Content-Type" "text/plain"}
                            :body "Unauthorized"})
(def response-server-error {:status 500
                            :headers {"Content-Type" "text/plain"}
                            :body "Internal Server Error"})
