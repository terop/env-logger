#!/usr/bin/env python3

"""This is a script for fixing odd weather timestamp values in the database. Sometimes
the timestamp is off by many hours from the previous and this script fixes those."""

from datetime import timedelta

import psycopg


def main():
    """Module main function."""
    # pylint: disable=invalid-name
    THRESHOLD_HOURS = 3
    # Change these as necessary
    DB_NAME = 'env_logger'
    ROW_LIMIT = 4000

    # pylint: disable=not-context-manager
    with psycopg.connect(f'dbname={DB_NAME}') as conn:
        with conn.cursor() as cursor:
            cursor.execute('SELECT id, time FROM weather_data ORDER by id DESC LIMIT %s',
                           (ROW_LIMIT,))
            rows = cursor.fetchall()
            i = 0

            while i < len(rows):
                diff_hours = (rows[i][1] - rows[i + 1][1]).total_seconds() / 3600
                if abs(diff_hours) >= THRESHOLD_HOURS:
                    print(f'The difference {int(diff_hours)} hours of {rows[i]} and {rows[i + 1]} '
                          f'exceeds {THRESHOLD_HOURS} hours')
                    if diff_hours > 0:
                        corr_index = i + 1
                        corrected = rows[corr_index][1] + timedelta(hours=int(diff_hours))
                    else:
                        corr_index = i
                        corrected = rows[corr_index][1] + timedelta(hours=int(abs(diff_hours)) + 1)

                    print(f'Correcting timestamp of row ID {rows[corr_index][0]} to {corrected}')
                    cursor.execute('UPDATE weather_data SET time = %s WHERE id = %s',
                                   (corrected, rows[corr_index][0]))
                i += 2


if __name__ == '__main__':
    main()
