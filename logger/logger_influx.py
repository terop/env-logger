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

# Suppress warning about unverified HTTPS requests
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)


def read_tag(tag_mac, device):
    """Read data from the tag with the provided MAC address. Returns a dictionary
    containing the data."""
    sensor = RuuviTag(tag_mac, bt_device=device)
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
    parser.add_argument('--device', type=str, help='Bluetooth device to use')

    args = parser.parse_args()
    device = args.device if args.device else 'hci0'

    with open(args.config, 'r') as config_file:
        try:
            config = json.load(config_file)
        except json.JSONDecodeError as err:
            print('{}: could not parse configuration file: {}'.
                  format(timestamp, err), file=sys.stderr)
            return

    database = config['database']
    client = InfluxDBClient(host=database['host'],
                            port=database['port'],
                            username=database['username'],
                            password=database['password'],
                            database=database['database'],
                            ssl=bool(strtobool(database['use_tls'])))

    for tag in config['tags']:
        all_data = read_tag(tag['tag_mac'], device)
        if all_data == {}:
            print('{}: Could not read data from tag with MAC: {}'.format(timestamp,
                                                                         tag['tag_mac']),
                  file=sys.stderr)
            continue

        data = {'temperature': all_data['temperature'],
                'pressure': all_data['pressure'],
                'humidity': all_data['humidity']}

        if not write_data(client, data, tag['influx_tags']):
            print('{}: Could not write data {} to database'.format(timestamp, data),
                  file=sys.stderr)
            continue


if __name__ == '__main__':
    main()
