DATE := $(shell date +%Y-%m-%d)
IMAGE_NAME := env-logger:$(DATE)

build: clean uberjar update # build container
	podman build -t $(IMAGE_NAME) .
	podman tag $(IMAGE_NAME) ${REGISTRY}/$(shell whoami)/$(IMAGE_NAME)

clean:
	clojure -T:build clean

uberjar: # build the jar
	clojure -T:build uber
	mv target/env-logger-*.jar target/env-logger.jar

update: # update base images
	podman pull amazoncorretto:24-alpine alpine:latest
