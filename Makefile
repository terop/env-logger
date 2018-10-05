
build: update # build container
	export LEIN_SNAPSHOTS_IN_RELEASE=1
	lein uberjar
	docker build -t env-logger:0.2.10 .

clean:
	lein clean

update: # update Docker base image
	docker pull openjdk:8-jre-alpine
