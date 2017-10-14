#!/usr/bin/env python3
"""This is a program for sending environment data to the data logger
web service. Data is read from a RuuviTag sensor."""

# This script does approximately the same things as logger.py but this script
# reads data from a RuuviTag sensor

from datetime import datetime
from distutils.util import strtobool
import argparse
import json
import sys

from ruuvitag_sensor.ruuvitag import RuuviTag
from influxdb import InfluxDBClient


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

    parser = argparse.ArgumentParser(description='Record sensor values from a RuuviTag '
                                     'and save it to InfluxDB server.')
    parser.add_argument('config', type=str, help='configuration file')

    args = parser.parse_args()
    with open(args.config, 'r') as cfg_json:
        try:
            config = json.load(cfg_json)
        except json.JSONDecodeError as err:
            print('{}: could not parse configuration file: {}'.
                  format(timestamp, err))
            return 1

    all_data = read_tag(config['tag_mac'])
    if all_data == {}:
        print('{}: Could not read data from tag with MAC: {}'.format(timestamp,
                                                                     config['tag_mac']),
              file=sys.stderr)
        return 1

    data = {'temperature': all_data['temperature'],
            'pressure': all_data['pressure'],
            'humidity': all_data['humidity']}

    db_conf = config['db_conf']
    client = InfluxDBClient(host=db_conf['host'], port=db_conf['port'],
                            username=db_conf['username'], password=db_conf['password'],
                            database=db_conf['database'],
                            ssl=bool(strtobool(db_conf['use_tls'])))
    if not write_data(client, data, config['influx_tags']):
        print('{}: Could not write data {} to database'.format(timestamp, data),
              file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    main()
