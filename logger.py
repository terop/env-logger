#!/usr/bin/env python3
"""This is a program for sending some environment data to the data logger
web service."""

import argparse
import json
from datetime import datetime
import pytz
import requests

URL = 'http://192.168.1.10/'
# Change this value when DB host changes
# Database insertion URL
DB_ADD_URL = 'http://localhost:8080/observations'
# Bluetooth beacon MAC addresses
BEACON_MACS = ['EA:6E:BA:99:92:ED', '7C:EC:79:3F:BE:97']


def main():
    """Module main function."""
    parser = argparse.ArgumentParser(description='Sends data to the env-logger backend.')
    parser.add_argument('--dummy', action='store_true',
                        help='send dummy data (meant for testing)')

    args = parser.parse_args()
    if args.dummy:
        env_data = {'inside_light': 10,
                    'inside_temp': 20,
                    'beacons': []}
    else:
        env_data = get_env_data()
        env_data['beacons'] = get_ble_beacons()
    send_to_db(env_data)


def get_env_data():
    """Fetches the environment data from the Arduino. Returns the parsed
    JSON object or an empty dictionary on failure."""
    resp = requests.get(URL)
    if resp.status_code != 200:
        # Request failed
        return {}
    return resp.json()


def get_ble_beacons():
    """Returns the MAC addresses and RSSI values of predefined Bluetooth BLE
    beacons in the vicinity in a dict."""
    # Inspired by
    # https://github.com/karulis/pybluez/blob/master/examples/ble/beacon_scan.py

    # Bluetooth BLE requires a recent version of gatttlib and pybluez
    from bluetooth.ble import BeaconService

    service = BeaconService()
    devices = service.scan(5)
    beacons = []

    # RSSI is data[4]
    for address, data in list(devices.items()):
        if address in BEACON_MACS:
            beacons.append({'mac': address, 'rssi': data[4]})
    return beacons


def send_to_db(data):
    """Sends the data to the remote database with a HTTP GET request."""
    if data == {}:
        print('Received no data, exiting')
        return

    data['timestamp'] = datetime.now(
        pytz.timezone('Europe/Helsinki')).isoformat()
    payload = {'obs-string': json.dumps(data)}
    resp = requests.post(DB_ADD_URL, params=payload)

    timestamp = datetime.now().isoformat()
    print('{}: Request data: {}, Response: code {}, text {}'
          .format(timestamp, payload, resp.status_code, resp.text))


if __name__ == '__main__':
    main()
