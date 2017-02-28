(defproject env-logger "0.2.5-SNAPSHOT"
  :description "A simple data logger"
  :url "https://github.com/terop/env-logger"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.immutant/web "2.1.6"]
                 [compojure "1.5.2"]
                 [ring "1.5.1"]
                 [ring/ring-defaults "0.2.3"]
                 [org.postgresql/postgresql "42.0.0"]
                 [org.clojure/java.jdbc "0.6.2-alpha2"]
                 [honeysql "0.8.2"]
                 [clj-time "0.13.0"]
                 [cheshire "5.7.0"]
                 [selmer "1.10.6"]
                 [clj-http "2.3.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.slf4j/slf4j-log4j12 "1.7.24"]
                 [com.yubico/yubico-validation-client2 "3.0.1"]
                 [buddy/buddy-hashers "1.2.0"]
                 [buddy/buddy-auth "1.4.1"]
                 [org.jsoup/jsoup "1.10.2"]
                 [org.clojars.pntblnk/clj-ldap "0.0.12"]]
  :main env-logger.handler
  :aot [env-logger.handler
        clojure.tools.logging.impl]
  :profiles
  {:dev {:dependencies [[clj-http-fake "1.0.2"]]
         :resource-paths ["resources"]
         :env {:squiggly {:checkers [:eastwood :kibit :typed]}}}})
