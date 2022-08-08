# env-logger

[![CircleCI](https://circleci.com/gh/terop/env-logger/tree/master.svg?style=svg)](https://circleci.com/gh/terop/env-logger/tree/master)

The env-logger is a data logger and visualisation Web application for collecting
and displaying various physical environment data, such as, temperature and brightness.

## Prerequisites

To build and run this application locally you will need a recent Clojure version
installed. Additionally, a PostgreSQL server instance
is needed. Database definitions can be found in `db-def.sql` and
a database with the required tables must exist before the application
can be started.

Users are added to the `users` tables respectively before running the application.
This can be done with the `scripts/add_user.py` script. In order the install the
necessary dependencies you need [pipenv](https://github.com/pypa/pipenv).
Install dependencies themselves with `pipenv install` command in the `scripts`
directory and run the script with `pipenv run python3 add_user.py <arguments>`.

A user's password can be hashed with the `(hashers/derive "<password>")` command
in a Clojure REPL where the `buddy-hashers` library is installed and imported
as `hashers`.

## Authentication

This application supports both password and WebAuthn based authentication.
Both methods work independently of each other and currently cannot be combined
into a two factor authentication mechanism.

To register a WebAuthn authenticator go the `<app url>/register` page when
logged in. There you can register as many authenticators as needed. When an
authenticator has been registered it can be used on the login page.

## Configuration

A sample configuration can be found in the `resources/config.edn_sample` file.
The configuration file used in development is `resources/dev/config.edn` and the
one for production in `resources/prod/config.edn`. After copying the
sample file edit either or both file(s) as needed.
Some settings can be overridden with environment variables. Accepted environment
variables are described below.

* __APP_PORT__: The port which the application will be accessible through.
The default port is `8080`.
* __POSTGRESQL_DB_HOST__: Hostname of the database server.
* __POSTGRESQL_DB_PORT__: The port on which the database server is listening.
* __POSTGRESQL_DB_NAME__: Name of the database.
* __POSTGRESQL_DB_USERNAME__: Username of the database user.
* __POSTGRESQL_DB_PASSWORD__: Password of the database user.

## Running
### Locally

To start the application locally run `clojure -M:run`. If the `target/classes`
directory does not exist then you need to run the `clojure -T:build build-java`
command to create the required Java .class file.

### Docker / podman

This application can be also be run in a Docker or podman container. To build the
container call `make build` from root directory of the application.
The container will be called `env-logger`. The .jar file to run in in the
container can be executed with the `java -jar <name>.jar` command.

## License

See the MIT license in the LICENSE file.

Copyright © 2014-2022 Tero Paloheimo
