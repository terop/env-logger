
build: # build container
	lein uberjar
	docker build -t env-logger:0.1.0 .

clean:
	lein clean
