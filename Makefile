
build: update # build container
	docker build -t env-logger .

clean:
	lein clean

update: # update runtime base image
	docker pull gcr.io/distroless/java-debian10:11
