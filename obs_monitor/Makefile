DATE := $(shell date +%Y-%m-%d)
IMAGE_NAME := env-logger-obs-monitor:$(DATE)

image: update
	podman build -t $(IMAGE_NAME) .
	podman tag $(IMAGE_NAME) ${REGISTRY}/$(shell whoami)/$(IMAGE_NAME)

update: # update base image
	podman pull docker.io/python:3.14-slim ghcr.io/astral-sh/uv:python3.14-trixie-slim
