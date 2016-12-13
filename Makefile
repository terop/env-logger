
build: # build container
	lein uberjar
	docker build -t env-logger .

clean:
	lein clean
