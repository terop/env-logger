
build: update # build container
	podman build -t env-logger .

clean:
	lein clean
	rm -rf target/

update: # update runtime base image
	podman pull gcr.io/distroless/java-debian11:latest
