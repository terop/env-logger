# env-logger

[![CircleCI](https://circleci.com/gh/terop/env-logger/tree/master.svg?style=svg)](https://circleci.com/gh/terop/env-logger/tree/master)

The env-logger is a data logger and visualisation Web application for collecting
and displaying various physical environment data, such as, temperature, humidity
and light value.

## Prerequisites

To build and run this application locally you will need a recent Clojure version
installed. Additionally, a PostgreSQL server instance
is needed. Database definitions can be found in `db-def.sql` and
a database with the required tables must exist before the application
can be started.

## Authentication

This application uses OpenID Connect based authentication. Keycloak has been used
for development but other OpenID Connect implementations should work as well.
See the `oid-auth` section in the configuration file for the settings to be
configured.

## Configuration

A sample configuration can be found in the `resources/config.edn_sample` file.
The configuration file used in development is `resources/dev/config.edn` and the
one for production in `resources/prod/config.edn`. After copying the
sample file edit either or both file(s) as needed.
Some settings can be overridden with environment variables or by reading from a
file. Accepted environment variables are described below.

* __APP_PORT__: The port which the application will be accessible through.
The default port is `8080`.
* __POSTGRESQL_DB_HOST__: Hostname of the database server.
* __POSTGRESQL_DB_PORT__: The port on which the database server is listening.
* __POSTGRESQL_DB_NAME__: Name of the database.
* __POSTGRESQL_DB_USERNAME__: Username of the database user.

The database password cannot be set through an environment variable due to
security reasons. Instead it can be read from a file whose name is set in the
`POSTGRESQL_DB_PASSWORD_FILE` environment variable. This file must only contain
the database password on one line.

## Running
### Locally

To start the application locally run `clojure -M:run`.

### Docker / podman

This application can be also be run in a Docker or Podman container. To build the
container call `make build` from root directory of the application.
The container will be called `env-logger`. The .jar file to run in in the
container can be executed with the `java -jar <name>.jar` command.

## License

See the MIT license in the LICENSE file.

Copyright © 2014-2022 Tero Paloheimo
