#!/usr/bin/env python3
"""This is a program for sending environment data to the data logger
web service."""

import argparse
import json
import signal
import subprocess
import sys
from collections import OrderedDict
from datetime import datetime
from os.path import exists
from statistics import mean
from time import sleep

import pytz
import requests

# NOTE! The ble_beacon_scan program's rights must be adjusted with the following command:
# sudo setcap 'cap_net_raw,cap_net_admin+eip' ble_beacon_scan
# Otherwise beacon scanning will not work as a non-root user!


def get_env_data(arduino_url):
    """Fetches the environment data from the Arduino. Returns the parsed
    JSON object or an empty dictionary on failure."""
    resp = requests.get(arduino_url)
    if not resp.ok:
        return {}
    return resp.json()


def get_ble_beacons(config):
    """Returns the MAC addresses and RSSI values of predefined Bluetooth BLE
    beacons in the vicinity in a dict."""
    try:
        proc = subprocess.Popen(['{}/ble_beacon_scan'.format(config['beacon_scan_path']),
                                 '-t', str(config['beacon_scan_time'])], stdout=subprocess.PIPE)
        sleep(config['beacon_scan_time'] + 2)
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
        if address in config['beacon_mac']:
            beacons.append(OrderedDict(sorted({'mac': address,
                                               'rssi': round(mean(addresses[address]))}.
                                              items())))
    return beacons


def store_in_db(config, data):
    """Stores the data in the backend database with a HTTP POST request."""
    if data == {}:
        print('Received no data, exiting', file=sys.stderr)
        return

    data['timestamp'] = datetime.now(
        pytz.timezone('Europe/Helsinki')).isoformat()
    data = OrderedDict(sorted(data.items()))
    resp = requests.post(config['upload_url'],
                         params={'obs-string': json.dumps(data),
                                 'code': config['authentication_code']})

    timestamp = datetime.now().isoformat()
    print('{}: Request data: {}, response: code {}, text {}'
          .format(timestamp, {'obs-string': json.dumps(data)},
                  resp.status_code, resp.text))


def main():
    """Module main function."""
    parser = argparse.ArgumentParser(description='Sends data to the env-logger backend.')
    parser.add_argument('config', type=str, help='Configuration file')
    parser.add_argument('--dummy', action='store_true',
                        help='send dummy data (meant for testing)')

    args = parser.parse_args()

    if not exists(args.config):
        print('Could not find configuration file: {}'.format(args.config), file=sys.stderr)
        sys.exit(1)

    with open(args.config, 'r') as config_file:
        try:
            config = json.load(config_file)
        except json.JSONDecodeError as err:
            print('Could not parse configuration file: {}'.format(err), file=sys.stderr)
            sys.exit(1)

    if args.dummy:
        env_data = {'inside_light': 10,
                    'inside_temp': 20,
                    'outside_temp': 5,
                    'beacons': []}
    else:
        env_data = get_env_data(config['arduino_url'])

        env_data['beacons'] = get_ble_beacons(config)
        for _ in range(2):
            if env_data['beacons']:
                break

            orig_scan_time = config['beacon_scan_time']
            config['beacon_scan_time'] /= 2
            env_data['beacons'] = get_ble_beacons(config)
            config['beacon_scan_time'] = orig_scan_time

    store_in_db(config, env_data)


if __name__ == '__main__':
    main()
