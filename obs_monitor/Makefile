DATE := $(shell date +%Y-%m-%d)

image:
	podman pull python:3.10-alpine
	podman build -t env-logger-obs-monitor:$(DATE) .
