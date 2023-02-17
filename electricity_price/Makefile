DATE := $(shell date +%Y-%m-%d)

build: update
	podman build -t env-logger-elec-price:$(DATE) .

update: # update base image
	podman pull python:3.10-slim
