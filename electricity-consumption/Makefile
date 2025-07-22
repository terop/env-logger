DATE := $(shell date +%Y-%m-%d)
IMAGE_NAME := env-logger-elec-consumption:$(DATE)

build: update
	podman build -t $(IMAGE_NAME) .
	podman tag $(IMAGE_NAME) ${REGISTRY}/$(shell whoami)/$(IMAGE_NAME)

update: # update base image
	podman pull python:3.13-alpine ghcr.io/astral-sh/uv:python3.13-alpine
