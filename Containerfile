FROM docker.io/eclipse-temurin:25-alpine as temurin-jdk
LABEL org.opencontainers.image.authors="tero.paloheimo@iki.fi"

# Required for strip-debug to work
RUN apk add --no-cache binutils

# Compute runtime modules from compiled classes and build small JRE
COPY ./target/classes /tmp/classes
RUN BASE_MODS="$($JAVA_HOME/bin/jdeps \
    --multi-release 25 \
    --ignore-missing-deps \
    --print-module-deps \
    /tmp/classes)" \
    && EXTRA_MODS="jdk.crypto.ec,java.naming,java.management" \
    && MODS="${BASE_MODS},${EXTRA_MODS}" \
    && $JAVA_HOME/bin/jlink \
        --verbose \
        --module-path "$JAVA_HOME/jmods" \
        --add-modules "$MODS" \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress 2 \
        --output /customjre

# Main app image
FROM docker.io/alpine:latest
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=temurin-jdk /customjre ${JAVA_HOME}

RUN apk update && apk upgrade

RUN apk add --no-cache dumb-init

# Add user to run the app
ARG APPLICATION_USER=appuser
RUN adduser --no-create-home -u 1000 -D ${APPLICATION_USER}

RUN mkdir /app && chown -R ${APPLICATION_USER} /app

USER ${APPLICATION_USER}

COPY --chown=${APPLICATION_USER}:${APPLICATION_USER} \
    ./target/env-logger.jar /app/env-logger.jar
COPY --chown=${APPLICATION_USER}:${APPLICATION_USER} \
    ./resources/prod/config.edn /etc/config.edn
WORKDIR /app

EXPOSE 8080
ENTRYPOINT ["dumb-init", "java", "-Dconfig=/etc/config.edn", "-jar", "/app/env-logger.jar"]
