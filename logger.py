#!/usr/bin/env python3
"""This is a program for sending some environment data to the data logger
web service."""

import json
from datetime import datetime
import pytz
import requests
# Bluetooth BLE requires a recent version of gatttlib and pybluez
from bluetooth.ble import BeaconService

URL = 'http://192.168.1.10/'
# Change this value when DB host changes
# Database insertion URL
DB_ADD_URL = 'http://localhost:8080/add'
# Bluetooth beacon MAC addresses
BEACON_MACS = ['EA:6E:BA:99:92:ED', '7C:EC:79:3F:BE:97']


def main():
    """Module main function."""
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
    payload = {'json-string': json.dumps(data)}
    resp = requests.get(DB_ADD_URL, params=payload)

    timestamp = datetime.now().isoformat()
    print('{0}: Response: code {1}, text {2}'.format(timestamp, resp.status_code, resp.text))


if __name__ == '__main__':
    main()
