#!/usr/bin/env python3
"""This is a program for sending environment data to the data logger
web service."""

import argparse
import json
import logging
import os
import signal
import subprocess
import sys
from collections import OrderedDict
from datetime import datetime
from os.path import exists
from shutil import which
from statistics import mean
from time import sleep

import pytz
import requests

# NOTE! The ble_beacon_scan program's rights must be adjusted with the following command:
# sudo setcap 'cap_net_raw,cap_net_admin+eip' ble_beacon_scan
# Otherwise beacon scanning will not work as a non-root user!


def get_timestamp(timezone):
    """Returns the current timestamp in ISO 8601 format."""
    return datetime.now(pytz.timezone(timezone)).isoformat()


def get_env_data(arduino_url):
    """Fetches the environment data from the Arduino. Returns the parsed
    JSON object or an empty dictionary on failure."""
    resp = requests.get(arduino_url)
    if not resp.ok:
        return {}
    return resp.json()

# NOTE! Bluewalker (https://gitlab.com/jtaimisto/bluewalker) is needed for RuuviTag scanning.
# Install it with "go get gitlab.com/jtaimisto/bluewalker". The binary must be placed in
# PATH. Passwordless sudo access is needed for bluewalker and hciconfig commands.


def scan_ruuvitags(config, device):
    """Scan RuuviTag(s) and upload data."""

    def _get_tag_location(config, tag_mac):
        """Get RuuviTag location based on tag MAC address."""
        for tag in config['ruuvitag']['tags']:
            if tag_mac == tag['tag_mac'].lower():
                return tag['location']

        return None

    json_filename = 'bluewalker.json'

    command = which('bluewalker', os.X_OK)
    if not command:
        logging.error('Could not find the bluewalker binary')
        sys.exit(1)

    # The Bluetooth device must be down before starting the scan
    try:
        subprocess.run('sudo hciconfig {} down'.format(device),
                       shell=True, check=True)
    except subprocess.CalledProcessError as cpe:
        logging.error('Failed to set device %s down before scan, rc: %s',
                      device, cpe.returncode)
        sys.exit(1)

    mac_addr = ''
    tag_macs = [tag['tag_mac'] for tag in config['ruuvitag']['tags']]
    for mac in tag_macs:
        mac_addr += '{}{},random'.format(';' if mac_addr else '', mac)

    try:
        subprocess.run('sudo {} -device {} -duration 5 -ruuvi -json '
                       '-filter-addr "{}" -output-file {}'.
                       format(command, device, mac_addr, json_filename),
                       shell=True, check=True)
    except subprocess.CalledProcessError as cpe:
        logging.error('bluewalker command failed with rc: %s', cpe.returncode)
        sys.exit(1)

    with open(json_filename, 'r') as json_file:
        tag_data = [json.loads(line.strip()) for line in json_file.readlines()]

    os.remove(json_filename)

    if not tag_data:
        return

    tag_found = {}
    for tag in tag_data:
        mac = tag['device']['address']
        if mac in tag_found:
            continue

        tag_found[mac] = True

        data = {'timestamp': get_timestamp(config['timezone']),
                'location': _get_tag_location(config, mac),
                'temperature': tag['sensors']['temperature'],
                'pressure': tag['sensors']['pressure'] / 100.0,
                'humidity': tag['sensors']['humidity'],
                'battery_voltage': tag['sensors']['voltage'] / 1000.0}

        resp = requests.post(config['ruuvitag']['url'],
                             params={'observation': json.dumps(data),
                                     'code': config['authentication_code']})

        logging.info("RuuviTag observation request data: '%s', response: code %s, text '%s'",
                     json.dumps(data), resp.status_code, resp.text)


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
                                               'rssi': round(mean(addresses[address]))}.items())))
    return beacons


def store_to_db(timezone, config, data, auth_code):
    """Stores the data in the backend database with a HTTP POST request."""
    if data == {}:
        logging.error('Received no data, stopping')
        return

    data['timestamp'] = get_timestamp(timezone)
    data = OrderedDict(sorted(data.items()))
    resp = requests.post(config['upload_url'],
                         params={'obs-string': json.dumps(data),
                                 'code': auth_code})

    logging.info("Weather observation request data: '%s', response: code %s, text '%s'",
                 json.dumps(data), resp.status_code, resp.text)


def main():
    """Module main function."""
    logging.basicConfig(format='%(asctime)s %(message)s', level=logging.INFO)

    parser = argparse.ArgumentParser(description='Scans environment data and sends it '
                                     'to the env-logger backend. A configuration file '
                                     'named "logger_config.json" is used unless '
                                     'provided with --config.')
    parser.add_argument('--config', type=str, help='Configuration file')
    parser.add_argument('--device', type=str, help='Bluetooth device to use')
    parser.add_argument('--dummy', action='store_true',
                        help='Send dummy data (meant for testing)')

    args = parser.parse_args()
    config = args.config if args.config else 'logger_config.json'
    device = args.device if args.device else 'hci0'

    if not exists(config):
        logging.error('Could not find configuration file: %s', config)
        sys.exit(1)

    with open(config, 'r') as config_file:
        try:
            config = json.load(config_file)
        except json.JSONDecodeError as err:
            logging.error('Could not parse configuration file: %s', err)
            sys.exit(1)

    if args.dummy:
        env_data = {'inside_light': 10,
                    'inside_temp': 20,
                    'outside_temp': 5,
                    'beacons': []}
    else:
        env_config = config['environment']
        env_data = get_env_data(env_config['arduino_url'])

        env_data['beacons'] = get_ble_beacons(env_config)
        # Possible retry if first scan fails
        for _ in range(2):
            if env_data['beacons']:
                break

            orig_scan_time = env_config['beacon_scan_time']
            env_config['beacon_scan_time'] /= 2
            env_data['beacons'] = get_ble_beacons(env_config)
            env_config['beacon_scan_time'] = orig_scan_time

    scan_ruuvitags(config, device)

    store_to_db(config['timezone'], env_config, env_data, config['authentication_code'])


if __name__ == '__main__':
    main()
