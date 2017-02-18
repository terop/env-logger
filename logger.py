#!/usr/bin/env python3
"""This is a program for sending some environment data to the data logger
web service."""

import argparse
import json
import signal
import subprocess
from collections import OrderedDict
from datetime import datetime
from statistics import mean
from time import sleep
import pytz
import requests

# NOTE! The ble_beacon_scan program's rights must be adjusted with the following command:
# sudo setcap 'cap_net_raw,cap_net_admin+eip' ble_beacon_scan
# Otherwise beacon scanning will not work as a non-root user!

URL = 'http://192.168.1.10/'
# Change this value when DB host changes
# Database insertion URL
DB_ADD_URL = 'http://localhost:8080/observations'
# Bluetooth beacon MAC addresses
BEACON_MACS = ['EA:6E:BA:99:92:ED']
# Bluetooth LE scanning time
SCAN_TIME = 10


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
    try:
        proc = subprocess.Popen(['/home/tpalohei/env-logger/ble_beacon_scan', '-t', str(SCAN_TIME)],
                                stdout=subprocess.PIPE)
        sleep(SCAN_TIME + 2)
        if proc.poll() is None:
            proc.send_signal(signal.SIGINT)
    except subprocess.CalledProcessError:
        return []
    except subprocess.TimeoutExpired:
        return []
    (stdout_data, stderr_data) = proc.communicate()
    if not stdout_data or stderr_data:
        return []
    output = stdout_data.decode('utf-8').strip()

    addresses = {}
    split_output = output.split('\n')
    for line in split_output:
        if len(line) < 15:
            continue
        address, rssi = line.split(' ')
        if address in addresses:
            addresses[address].append(int(rssi))
        else:
            addresses[address] = [int(rssi)]

    beacons = []
    for address in addresses:
        if address in BEACON_MACS:
            beacons.append(OrderedDict(sorted({'mac': address,
                                               'rssi': round(mean(addresses[address]))}.
                                              items())))
    return beacons


def send_to_db(data):
    """Sends the data to the remote database with a HTTP GET request."""
    if data == {}:
        print('Received no data, exiting')
        return

    data['timestamp'] = datetime.now(
        pytz.timezone('Europe/Helsinki')).isoformat()
    data = OrderedDict(sorted(data.items()))
    payload = {'obs-string': json.dumps(data)}
    resp = requests.post(DB_ADD_URL, params=payload)

    timestamp = datetime.now().isoformat()
    print('{}: Request data: {}, response: code {}, text {}'
          .format(timestamp, payload, resp.status_code, resp.text))


if __name__ == '__main__':
    main()
