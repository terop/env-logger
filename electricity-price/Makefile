DATE := $(shell date +%Y-%m-%d)
IMAGE_NAME := env-logger-elec-price:$(DATE)

build: update
	podman build -t $(IMAGE_NAME) .
	podman tag $(IMAGE_NAME) ${REGISTRY}/$(shell whoami)/$(IMAGE_NAME)

update: # update base image
	podman pull docker.io/python:3.14-alpine ghcr.io/astral-sh/uv:python3.14-alpine
