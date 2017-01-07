
build: update # build container
	lein uberjar
	docker build -t env-logger .

clean:
	lein clean

update: # update Docker base image
	docker pull java:8-jre-alpine
