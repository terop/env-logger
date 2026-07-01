#!/usr/bin/env python3

"""Fix odd weather timestamp values in the database.

Sometimes the timestamp is off by many hours from the previous and this script
fixes those.
"""

from datetime import timedelta

import psycopg


def main():
    """Run the weather timestamp fix."""
    threshold_hours = 3
    # Change these as necessary
    db_name = 'env_logger'
    row_limit = 4000

    with psycopg.connect(f'dbname={db_name}') as conn, conn.cursor() as cursor:
        cursor.execute('SELECT id, time FROM weather_data ORDER by id DESC '
                       'LIMIT %s',
                       (row_limit,))
        rows = cursor.fetchall()
        i = 0

        while i < len(rows):
            diff_hours = (rows[i][1] - rows[i + 1][1]).total_seconds() / 3600
            if abs(diff_hours) >= threshold_hours:
                print(f'The difference {int(diff_hours)} hours of {rows[i]} '
                      f'and {rows[i + 1]} exceeds {threshold_hours} hours')
                if diff_hours > 0:
                    corr_index = i + 1
                    corrected = rows[corr_index][1] + \
                            timedelta(hours=int(diff_hours))
                else:
                    corr_index = i
                    corrected = rows[corr_index][1] + \
                            timedelta(hours=int(abs(diff_hours)) + 1)

                print(f'Correcting timestamp of row ID {rows[corr_index][0]} '
                      'to {corrected}')
                cursor.execute('UPDATE weather_data SET time = %s WHERE id = %s',
                               (corrected, rows[corr_index][0]))
            i += 2


if __name__ == '__main__':
    main()
