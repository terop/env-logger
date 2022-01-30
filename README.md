# env-logger

[![CircleCI](https://circleci.com/gh/terop/env-logger/tree/master.svg?style=svg)](https://circleci.com/gh/terop/env-logger/tree/master)

The env-logger is a simple data logger for collecting various physical
environment data, such as, temperature and brightness.

## Prerequisites

To build and run this application locally you will need a recent Clojure version
installed. Additionally, a PostgreSQL server instance
is needed. Database definitions can be found in `db-def.sql` and
a database with the required tables must exist before the application
can be started.

Users and Yubikeys are added to the `users` and `yubikeys`
tables respectively before running the application. This can be done
with the `scripts/add_user.py` script. In order the install the necessary
dependencies you need [pipenv](https://github.com/pypa/pipenv). Install dependencies
themselves with `pipenv install` command in the `scripts` directory and run the
script with `pipenv run python3 add_user.py <arguments>`.

A user's password can be hashed with the `(hashers/derive "<password>")` command
in a Clojure REPL where the `buddy-hashers` library is installed and imported
as `hashers`.

## Configuration

A sample configuration can be found in the `resources/config.edn_sample` file.
Copy or rename this file to `config.edn` in the `resources` directory and edit
it to fit your configuration. Some settings can be overridden with environment
variables. Accepted environment variables are described below.
* __APP_PORT__: The port which the application will be accessible through.
The default port is `8080`.
* __POSTGRESQL_DB_HOST__: Hostname of the database server.
* __POSTGRESQL_DB_PORT__: The port on which the database is listening.
* __POSTGRESQL_DB_NAME__: Name of the database.
* __POSTGRESQL_DB_USERNAME__: Username of the database user.
* __POSTGRESQL_DB_PASSWORD__: Password of the database user.

_NOTE_! The first variable is not defined in `config.edn`.

## Running
### Locally
To start the application locally run `clojure -M:run`.

### Docker / podman

This application can be also be run in a Docker or podman container. To build the
container call `make build` from root directory of the application.
The container will be called `env-logger`. The .jar file to run in in the
container can be executed with the `java -jar <name>.jar` command.

## License

See the MIT license in the LICENSE file.

Copyright © 2014-2022 Tero Paloheimo
