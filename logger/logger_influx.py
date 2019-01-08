#!/usr/bin/env python3
"""This is a program for sending environment data to the data logger
web service. Data is read from a RuuviTag sensor."""

# This script does approximately the same things as logger.py but this script
# reads data from a RuuviTag sensor

from datetime import datetime
from distutils.util import strtobool
from os.path import exists
import argparse
import json
import logging
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


def scan_tags(config, device):
    """Scan tag(s) and upload data."""
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
            logging.error('Could not read data from tag with MAC: %s',
                          tag['tag_mac'])
            continue

        data = {'temperature': all_data['temperature'],
                'pressure': all_data['pressure'],
                'humidity': all_data['humidity']}

        if not write_data(client, data, tag['influx_tags']):
            logging.error('Could not write data %s to database', data)
            continue


def main():
    """Main function of the module."""
    logging.basicConfig(format='%(asctime)s %(message)s')

    parser = argparse.ArgumentParser(description='Record sensor values from a RuuviTag '
                                     'and save it to InfluxDB server.')
    parser.add_argument('config', type=str, help='Configuration file')
    parser.add_argument('--device', type=str, help='Bluetooth device to use')

    args = parser.parse_args()
    device = args.device if args.device else 'hci0'

    if not exists(args.config):
        logging.error('Could not find configuration file: %s', args.config)
        sys.exit(1)

    with open(args.config, 'r') as config_file:
        try:
            config = json.load(config_file)
        except json.JSONDecodeError as err:
            logging.error('Could not parse configuration file: %s', err)
            sys.exit(1)

    scan_tags(config, device)


if __name__ == '__main__':
    main()
