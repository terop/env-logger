#!/usr/bin/env python3

"""This a script for converting InfluxDB line format dump into a format
for inserting it into a PostgreSQL database table."""


from collections import OrderedDict
from datetime import datetime
import argparse
import os.path
import re
import sys

import pytz


def extract_observations(influxdb_backup):
    """Extract observation data from InfluxDB backup file. Returns an ordered dictionary
    (sorted by time) with all 'attributes' preserved."""
    locations = {'indoor': {}, 'balcony': {}}
    timezone = pytz.timezone('Europe/Helsinki')

    with open(influxdb_backup, 'r') as influx:
        lines = [line.strip() for line in influx.readlines()]
        # Strip header liens
        lines = lines[7:]

    pattern = re.compile(r'observations,location=(\w+) (\w+)=(.+) (\d+)')
    for line in lines:
        match = re.match(pattern, line)
        if not match:
            continue

        location = match.group(1)

        timestamp = match.group(4)
        timestamp = datetime.utcfromtimestamp(float('{}.{}'.format(timestamp[0:10],
                                                                   timestamp[10:])))
        timestamp = timestamp.astimezone(timezone)

        if timestamp not in locations[location]:
            locations[location][timestamp] = {}

        locations[location][timestamp]['location'] = location
        locations[location][timestamp]['timestamp'] = timestamp
        locations[location][timestamp][match.group(2)] = float(match.group(3))

    combined = {**locations['indoor'], **locations['balcony']}
    return OrderedDict(sorted(combined.items()))


def write_db_input_file(filename, rows):
    """Writes all data into a file which can be used with psql to insert the data."""
    row_count = len(rows.keys())

    with open(filename, 'w') as outfile:
        outfile.write("INSERT INTO ruuvitag_observations (recorded, location, temperature, "
                      "pressure, humidity) VALUES\n")

        for (i, key) in enumerate(rows.keys()):
            row = rows[key]
            outfile.write("    ('{}', '{}', {}, {}, {}){}\n".format(
                str(row['timestamp']), row['location'], row['temperature'],
                row['pressure'], row['humidity'],
                ',' if i != (row_count - 1) else ';'))


def main():
    """Module main function."""
    parser = argparse.ArgumentParser(description='Converts an InfluxDB data dump into PostgreSQL '
                                     'INSERT INTO statements.')
    parser.add_argument('influxdb_backup', type=str,
                        help='input InfluxDB data file')
    parser.add_argument('output_file', type=str,
                        help='a file into which SQL INSERT statements are written')

    args = parser.parse_args()

    if not os.path.exists(args.influxdb_backup):
        print('Error: InfluxDB backup file "{}" not found'.format(args.influxdb_backup),
              file=sys.stderr)
        exit(1)

    rows = extract_observations(args.influxdb_backup)
    write_db_input_file(args.output_file, rows)


if __name__ == '__main__':
    main()
