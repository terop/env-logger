#!/usr/bin/env python3
"""This is a utility for adding a user into the environment loggers database."""

import argparse
import sys

import psycopg


def add_user(connection, username, password_hash):
    """Adds the user defined by the given username to the users table.
    Returns True on success and False otherwise."""
    with connection.cursor() as cursor:
        try:
            cursor.execute('INSERT INTO users (username, pw_hash) VALUES (%s, %s)',
                           (username, password_hash))
        # pylint: disable=invalid-name
        except psycopg.Error as e:
            print(f'An error occurred while inserting user "{username}": {str(e)}',
                  file=sys.stderr)
            return False
    return True


def main():
    """Main function of this module."""
    parser = argparse.ArgumentParser(description='Add a user to the environment '
                                     'logger\'s database.')
    parser.add_argument('db_name', type=str, help='Name of the database')
    parser.add_argument('username', type=str, help='The user\'s username')
    parser.add_argument('password_hash', type=str, help='Hash of the user\'s password')
    args = parser.parse_args()

    # pylint: disable=not-context-manager
    with psycopg.connect(f'dbname={args.db_name}') as connection:
        if add_user(connection, args.username, args.password_hash):
            print('Successfully inserted user "{args.username}"')


if __name__ == '__main__':
    main()
