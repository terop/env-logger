#!/usr/bin/env python3
"""This is a utility for adding a user into the environment loggers database."""

import argparse
import sys
import psycopg2


def add_user(connection, username, password_hash):
    """Adds the user defined by the given username to the users table.
    Returns True on success and False otherwise."""
    with connection.cursor() as cursor:
        try:
            cursor.execute('INSERT INTO users (username, pw_hash) VALUES (%s, %s)',
                           (username, password_hash))
        # pylint: disable=invalid-name
        except psycopg2.Error as e:
            print('An error occurred while inserting user {}: {}'.
                  format(username, e.pgerror), file=sys.stderr)
            return False
    return True


def add_yubikey(connection, username, yubikey_id):
    """Adds a Yubikey associated to the provided username. Returns True on
    success and False otherwise."""
    with connection.cursor() as cursor:
        try:
            cursor.execute('SELECT user_id FROM users WHERE username = %s',
                           (username,))
            result = cursor.fetchone()
            if len(result) != 1:
                print('Could not find user {} during Yubikey insert'.
                      format(username), file=sys.stderr)
                return False

            cursor.execute('INSERT INTO yubikeys (user_id, yubikey_id) VALUES (%s, %s)',
                           (result[0], yubikey_id))
        # pylint: disable=invalid-name
        except psycopg2.Error as e:
            print('An error occurred while inserting Yubikey for user {}: {}'.
                  format(username, e.pgerror), file=sys.stderr)
            return False
    return True


def main():
    """Main function of this module."""
    parser = argparse.ArgumentParser(description='Add a user to the environment '
                                     'logger\'s database.')
    parser.add_argument('db_name', type=str, help='Name of the database')
    parser.add_argument('username', type=str, help='The user\'s username')
    parser.add_argument('password_hash', type=str, help='Hash of the user\'s password')
    parser.add_argument('--yubikey', type=str, dest='yubikey',
                        help='Yubikey ID')
    args = parser.parse_args()

    with psycopg2.connect('dbname={}'.format(args.db_name)) as connection:
        if add_user(connection, args.username, args.password_hash):
            if args.yubikey:
                if add_yubikey(connection, args.username, args.yubikey):
                    print('Successfully inserted user {} with Yubikey'.
                          format(args.username))
            else:
                print('Successfully inserted user {}'.format(args.username))


if __name__ == '__main__':
    main()
