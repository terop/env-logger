(defproject env-logger "0.2.8-SNAPSHOT"
  :description "A simple data logger"
  :url "https://github.com/terop/env-logger"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.immutant/web "2.1.9"]
                 [compojure "1.6.0"]
                 [ring "1.6.2"]
                 [ring/ring-defaults "0.3.1"]
                 [org.postgresql/postgresql "42.1.4"]
                 [org.clojure/java.jdbc "0.7.0"]
                 [honeysql "0.9.0"]
                 [clj-time "0.14.0"]
                 [cheshire "5.8.0"]
                 [selmer "1.11.0"]
                 [clj-http "3.7.0"]
                 [org.clojure/tools.logging "0.4.0"]
                 [org.slf4j/slf4j-log4j12 "1.7.25"]
                 [com.yubico/yubico-validation-client2 "3.0.1"]
                 [buddy/buddy-hashers "1.2.0"]
                 [buddy/buddy-auth "2.0.0"]
                 [org.clojars.pntblnk/clj-ldap "0.0.12"]]
  :main env-logger.handler
  :aot [env-logger.handler
        clojure.tools.logging.impl]
  :profiles
  {:dev {:dependencies [[clj-http-fake "1.0.3"]]
         :resource-paths ["resources"]
         :env {:squiggly {:checkers [:eastwood :kibit :typed]}}}})
