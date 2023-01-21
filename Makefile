DATE := $(shell date +%Y-%m-%d)

build: uberjar update # build container
	podman build -t env-logger:$(DATE) .

uberjar: # build the jar
	clojure -T:build uber
	mv target/env-logger-*.jar target/env-logger.jar

update: # update base images
	podman pull amazoncorretto:17-alpine alpine:latest
