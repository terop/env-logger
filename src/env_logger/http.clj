(ns env-logger.http
  "Minimal HTTP client wrapper around java.net.http."
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Builder
            HttpRequest HttpRequest$Builder HttpResponse HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private client
  (-> (HttpClient/newBuilder)
      (HttpClient$Builder/.connectTimeout (Duration/ofSeconds 30))
      (HttpClient$Builder/.build)))

(defn http-get
  "Perform an HTTP GET and return a map with :status and :body."
  [url]
  (let [request (-> (HttpRequest/newBuilder)
                    (HttpRequest$Builder/.uri (URI/create url))
                    (HttpRequest$Builder/.GET)
                    (HttpRequest$Builder/.timeout (Duration/ofSeconds 30))
                    (HttpRequest$Builder/.build))
        response (HttpClient/.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (HttpResponse/.statusCode response)
     :body (HttpResponse/.body response)}))
