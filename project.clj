(defproject env-logger "0.1.0-SNAPSHOT"
	:description "A simple data logger"
	:url "http://example.com/FIXME"
    :dependencies [[org.clojure/clojure "1.5.1"]
                   [http-kit "2.1.12"]
                   [compojure "1.1.7"]]
  :main env-logger.main
  :profiles
  {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]]}})