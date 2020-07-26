FROM openjdk:11-jre-slim
LABEL maintainer="tero.paloheimo@iki.fi"

ADD target/uberjar/env-logger-0.2.11-SNAPSHOT-standalone.jar /usr/src/logger.jar
EXPOSE 8080
CMD ["java", "-jar", "/usr/src/logger.jar"]
