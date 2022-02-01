build: update # build container
	podman build -t env-logger .

update: # update runtime base image
	podman pull gcr.io/distroless/java17-debian11:latest
	podman pull clojure:openjdk-17-tools-deps-bullseye
