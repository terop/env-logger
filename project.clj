(defproject env-logger "0.1.1-SNAPSHOT"
  :description "A simple data logger"
  :url "https://github.com/terop/env-logger"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.immutant/web "2.1.5"]
                 [compojure "1.5.1"]
                 [ring "1.5.0"]
                 [ring/ring-defaults "0.2.1"]
                 [org.postgresql/postgresql "9.4.1212"]
                 [org.clojure/java.jdbc "0.6.2-alpha2"]
                 [honeysql "0.8.1"]
                 [clj-time "0.12.2"]
                 [cheshire "5.6.3"]
                 [selmer "1.10.0"]
                 [clj-http "2.3.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.slf4j/slf4j-log4j12 "1.7.21"]
                 [com.yubico/yubico-validation-client2 "3.0.1"]
                 [buddy/buddy-hashers "1.1.0"]
                 [buddy/buddy-auth "1.3.0"]]
  :main env-logger.handler
  :aot [env-logger.handler
        clojure.tools.logging.impl]
  :profiles
  {:dev {:dependencies [[clj-http-fake "1.0.2"]]
         :resource-paths ["resources"]
         :env {:squiggly {:checkers [:eastwood :kibit :typed]}}}})
