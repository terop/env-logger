env-logger
=======

The env-logger is a simple data logger for collecting various physical
environment data, such as, temperature and brightness.

## Prerequisites

To build and run this application locally you will need [Leiningen][] 2.0.0 or
above installed. Additionally, a PostgreSQL server instance
is needed. Database definitions can be found in `table-def.sql` and
a database with the required tables must exist before the application
can be started.

[leiningen]: https://github.com/technomancy/leiningen

## Configuration

A sample configuration can be found in the `resources/config.edn_sample` file.
Copy or rename this file to `config.edn` in the `resources` directory and edit
it to fit your configuration. Some settings can be overridden with environment
variables. Accepted environment variables are described below.
* __APP_IP__: The IP address to which the application will be bound to. The
default IP address is `0.0.0.0`.
* __APP_PORT__: The port which the application will be accessible through.
The default port is `8080`.
* __POSTGRESQL_DB_HOST__: Hostname of the database server.
* __POSTGRESQL_DB_PORT__: The port on which the database is listening.
* __POSTGRESQL_DB_NAME__: Name of the database.
* __POSTGRESQL_DB_USERNAME__: Username of the database user.
* __POSTGRESQL_DB_PASSWORD__: Password of the database user.

_NOTE_! The two first variables are not defined in `config.edn`.

## Running
### Locally
To start a web server for the application, run:

    lein run

or

    lein trampoline run

### Docker

This application can be also be run in a Docker container. To build the
container call `make build` from root directory of the application.
The container will be called `env-logger`. The .jar file to run in in the
container can be executed with the `java -jar <name>.jar` command.

## License

See the MIT license in the LICENSE file.

Copyright © 2014-2016 Tero Paloheimo
