
build: # build container
	lein uberjar
	docker build -t env-logger:0.2.0 .

clean:
	lein clean
