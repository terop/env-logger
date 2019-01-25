#!/usr/bin/env python3
"""This is a program for sending environment data to the data logger
web service."""

import argparse
import json
import logging
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
from ruuvitag_sensor.ruuvitag import RuuviTag

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


def scan_ruuvitags(config, device, auth_code):
    """Scan RuuviTag(s) and upload data."""

    def read_tag(tag_mac, device):
        """Read data from the RuuviTag with the provided MAC address.
        Returns a dictionary containing the data."""
        sensor = RuuviTag(tag_mac, bt_device=device)
        sensor.update()

        # get latest state (does not get it from the device)
        return sensor.state

    for tag in config['tags']:
        all_data = read_tag(tag['tag_mac'], device)
        if all_data == {}:
            logging.error('Could not read data from tag with MAC: %s',
                          tag['tag_mac'])
            continue

        data = {'location': tag['location'],
                'temperature': all_data['temperature'],
                'pressure': all_data['pressure'],
                'humidity': all_data['humidity']}

        resp = requests.post(config['url'],
                             params={'observation': json.dumps(data),
                                     'code': auth_code})

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


def store_to_db(config, data, auth_code):
    """Stores the data in the backend database with a HTTP POST request."""
    if data == {}:
        logging.error('Received no data, stopping')
        return

    data['timestamp'] = datetime.now(
        pytz.timezone('Europe/Helsinki')).isoformat()
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
                                     'to the env-logger backend.')
    parser.add_argument('config', type=str, help='Configuration file')
    parser.add_argument('--device', type=str, help='Bluetooth device to use')
    parser.add_argument('--dummy', action='store_true',
                        help='Send dummy data (meant for testing)')

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

    scan_ruuvitags(config['ruuvitag'], device, config['authentication_code'])

    store_to_db(env_config, env_data, config['authentication_code'])


if __name__ == '__main__':
    main()
