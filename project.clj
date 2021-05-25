(defproject env-logger "0.2.11-SNAPSHOT"
  :description "A simple data logger"
  :url "https://github.com/terop/env-logger"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [ring "1.9.3"]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-json "0.5.1"]
                 [compojure "1.6.2"]
                 [org.postgresql/postgresql "42.2.20"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [honeysql "1.0.461"]
                 [clojure.java-time "0.3.2"]
                 [org.threeten/threeten-extra "1.6.0"]
                 [cheshire "5.10.0"]
                 [selmer "1.12.40"]
                 [clj-http "3.12.2"]
                 [org.clojure/tools.logging "1.1.0"]
                 [org.slf4j/slf4j-log4j12 "1.7.30"]
                 [com.yubico/yubico-validation-client2 "3.1.0"]
                 [buddy/buddy-hashers "1.8.1"]
                 [buddy/buddy-auth "3.0.1"]
                 [buddy/buddy-sign "3.4.1"]
                 [org.clojars.pntblnk/clj-ldap "0.0.17"]]
  :main ^:skip-aot env-logger.handler
  :aot [env-logger.handler
        clojure.tools.logging.impl]
  :target-path "target/%s/"
  :profiles
  {:dev {:dependencies [[clj-http-fake "1.0.3"]]
         :resource-paths ["resources"]
         :env {:squiggly {:checkers [:eastwood :kibit :typed]}}}
   :uberjar {:aot :all}})
