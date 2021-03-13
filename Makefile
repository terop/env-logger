
build: update # build container
	podman build -t env-logger .

clean:
	lein clean

update: # update runtime base image
	podman pull gcr.io/distroless/java-debian10:11
