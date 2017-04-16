FROM java:8-jre-alpine
MAINTAINER Tero Paloheimo <tero.paloheimo@iki.fi>
ADD target/env-logger-0.2.7-SNAPSHOT-standalone.jar /usr/src/logger.jar
EXPOSE 8080
CMD ["java", "-jar", "/usr/src/logger.jar"]
