
build: update # build container
	lein uberjar
	docker build -t env-logger:0.2.1 .

clean:
	lein clean

update: # update Docker base image
	docker pull java:8-jre-alpine
