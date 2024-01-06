DATE := $(shell date +%Y-%m-%d)
IMAGE_NAME := env-logger-obs-monitor:$(DATE)

image:
	podman pull python:3.12-alpine
	podman build -t $(IMAGE_NAME) .
	podman tag $(IMAGE_NAME) $(shell whoami)/$(IMAGE_NAME)
