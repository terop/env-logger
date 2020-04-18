env-logger
=======

The env-logger is a simple data logger for collecting various physical
environment data, such as, temperature and brightness.

## Prerequisites

To build and run this application locally you will need [Leiningen][] 2.0.0 or
above installed. Additionally, a PostgreSQL server instance
is needed. Database definitions can be found in `db-def.sql` and
a database with the required tables must exist before the application
can be started. Users and Yubikeys must be added to the `users` and `yubikeys`
tables respectively before running the application. This can be done
with the `scripts/add_user.py` script. Dependencies needed by the script can be installed
with the `pip install -r requirements.txt` command. A user's password can be
hashed with the `(hashers/derive "<password>")` command in a Clojure REPL where
the `buddy-hashers` library is installed and imported as `hashers`.

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

### LDAP as a authentication back-end
It is possible to use LDAP as a back-end for authentication. The hashes of users'
passwords are stored in LDAP from where they are fetched during login. It is
possible to combine LDAP and Yubikeys: one can store Yubikey ID(s) in database
and password in LDAP. The username must be same in both places, obviously.

A user's password hash is stored in the `userPassword` attribute. Because the hash
is compared during login, it must be stored in plain format in LDAP. There will
be no security issue here because a hash stored in stead of a plaintext password.
Suitable password hashes can be obtained with the `derive` function from
`buddy-hashers` library, see the Prerequisites section for more information.

Assuming you have a LDAP tree with a root DN `dc=example,dc=com` you can use
LDIF below to create a suitable tree for storing users. The crucial part is that
there must be a OU called `users` where users are stored.

```
# Create OU for users
dn: ou=users,dc=example,dc=com
changetype: add
objectClass: organizationalUnit
objectClass: top
ou: users

# Create a user
dn: cn=theuser,ou=users,dc=example,dc=com
changetype: add
objectClass: organizationalPerson
objectClass: person
objectClass: top
sn: theuser
cn: theuser
userPassword: thepasswordhash
```

LDAP server settings are defined in the `ldap` block of `config.edn`. The actual
choice of using LDAP is controlled by the `use-ldap` setting in the same file.

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

Copyright © 2014-2020 Tero Paloheimo
