DATE := $(shell date +%Y-%m-%d)
IMAGE_NAME := env-logger:$(DATE)

build: uberjar update # build container
	podman build -t $(IMAGE_NAME) .
	podman tag $(IMAGE_NAME) $(shell whoami)/$(IMAGE_NAME)

uberjar: # build the jar
	clojure -T:build uber
	mv target/env-logger-*.jar target/env-logger.jar

update: # update base images
	podman pull amazoncorretto:17-alpine alpine:latest
