FROM clojure:temurin-17-tools-deps-focal as builder
LABEL maintainer="tero.paloheimo@iki.fi"
WORKDIR /usr/home/app
ADD . /usr/home/app
RUN clojure -T:build uberjar

FROM gcr.io/distroless/java17-debian11
COPY --from=builder /usr/home/app/target/env-logger*.jar env-logger.jar
EXPOSE 8080
CMD ["env-logger.jar"]
