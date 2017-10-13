#!/usr/bin/env python3

"""A scrip for migrating Testbed images from database to external storage."""

import argparse
from pathlib import Path

import psycopg2


def read_images(cursor):
    """Returns the ID, recorded datetime and image content of all Testbed images."""
    cursor.execute('SELECT id, recorded, testbed_image FROM observations WHERE '
                   'testbed_image IS NOT NULL ORDER BY id ASC')
    return cursor.fetchall()


def handle_image(image, image_dir, cursor):
    """Creates a directory as needed, writes the image to a file and updates the database."""
    name = image[1].strftime('testbed-%Y-%m-%dT%H:%M%z.png')
    dir_name = image[1].strftime('%Y-%m-%d')
    path = Path('{}/{}'.format(image_dir, dir_name))

    if not path.exists():
        path.mkdir(mode=0o755)
    with open('{}/{}'.format(str(path), name), 'wb') as img:
        img.write(image[2])

    cursor.execute('UPDATE observations SET tb_image_name = %s WHERE id = %s', (name, image[0]))


def main():
    # pylint: disable=missing-docstring
    parser = argparse.ArgumentParser(description='Migrate env-logger Testbed images.')
    parser.add_argument('db_name', type=str, help='database name')
    parser.add_argument('db_user', type=str, help='database user')
    parser.add_argument('db_password', type=str, help='database password')
    parser.add_argument('image_dest_dir', type=str, help='destination directory for images')

    args = parser.parse_args()
    with psycopg2.connect(dbname=args.db_name, user=args.db_user,
                          password=args.db_password) as conn:
        with conn.cursor() as cursor:
            images = read_images(cursor)
            for idx, image in enumerate(images):
                if idx % 200 == 0:
                    print('Processing image number {} of {}'.format(idx, len(images)))
                handle_image(image, args.image_dest_dir, cursor)
        conn.commit()

if __name__ == '__main__':
    main()
