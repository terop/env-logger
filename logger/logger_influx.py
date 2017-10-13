#!/usr/bin/env python3
"""This is a program for sending environment data to the data logger
web service. Data is read from a RuuviTag sensor."""

# This script does approximately the same things as logger.py but this script
# reads data from a RuuviTag sensor

from datetime import datetime
import sys

from ruuvitag_sensor.ruuvitag import RuuviTag
from influxdb import InfluxDBClient

# MODIFY these as needed!
TAG_MAC = 'F3:13:DD:06:E1:7D'
DB_CONF = {'host': 'example.com',
           'port': 8086,
           'username': 'user',
           'password': 'p4ssword',
           'database': 'mydb',
           'use_tls': True}
INFLUXDB_TAGS = {'location': 'indoor'}


def read_tag(tag_mac):
    """Read data from the tag with the provided MAC address. Returns a dictionary
    containing the data."""
    sensor = RuuviTag(tag_mac)
    state = sensor.update()

    # get latest state (does not get it from the device)
    state = sensor.state

    return state


def write_data(client, data, tags):
    """Writes the provided data to the database pointed by the client. Returns True
    on success and False otherwise."""
    json_body = [{
        'measurement': 'observations',
        'time': datetime.utcnow().isoformat(),
        'fields': data,
        'tags': tags
    }]
    return client.write_points(json_body)


def main():
    """Main function of the module."""
    timestamp = datetime.now().isoformat()
    all_data = read_tag(TAG_MAC)
    if all_data == {}:
        print('{}: Could not read data from tag with MAC: {}'.format(timestamp, TAG_MAC),
              file=sys.stderr)
        return 1

    data = {'temperature': all_data['temperature'],
            'pressure': all_data['pressure'],
            'humidity': all_data['humidity']}

    client = InfluxDBClient(host=DB_CONF['host'], port=DB_CONF['port'],
                            username=DB_CONF['username'], password=DB_CONF['password'],
                            database=DB_CONF['database'], ssl=DB_CONF['use_tls'])
    if not write_data(client, data, INFLUXDB_TAGS):
        print('{}: Could not write data {} to database'.format(timestamp, data),
              file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    main()
