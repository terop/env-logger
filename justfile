date := `date +%Y-%m-%d`
IMAGE_NAME := f'env-logger:{{date}}'

default: build

build: clean uberjar update # build container
    [ -n "${REGISTRY:-}" ] || { echo "REGISTRY is not set" >&2; exit 1; }
    podman build -t {{IMAGE_NAME}} .
    podman tag {{IMAGE_NAME}} ${REGISTRY}/$(whoami)/{{IMAGE_NAME}}

clean:
    clojure -T:build clean

uberjar: # build the jar
    clojure -T:build uber
    mv target/env-logger-*.jar target/env-logger.jar

update: # update base images
    podman pull docker.io/amazoncorretto:25-alpine docker.io/alpine:latest
