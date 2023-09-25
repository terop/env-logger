#!/usr/bin/env python3

"""A program for fetching and sending environment data to the data logger backend."""

import argparse
import asyncio
import json
import logging
import os
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
import serial
from requests.exceptions import ConnectionError

os.environ['RUUVI_BLE_ADAPTER'] = 'bleak'
from ruuvitag_sensor.ruuvi import RuuviTagSensor  # noqa: E402


def get_timestamp(timezone):
    """Return the current timestamp in ISO 8601 format."""
    return datetime.now(pytz.timezone(timezone)).isoformat()


def get_env_data(env_settings):
    """Fetch the environment data from the Arduino and Wio Terminal.

    Returns the parsed JSON object or an empty dictionary on failure.
    """
    # Read Arduino
    arduino_ok = True
    try:
        resp = requests.get(env_settings['arduino_url'], timeout=5)
    except (ConnectionError, OSError) as ex:
        logging.error('Connection problem to Arduino: %s', ex) # noqa: TRY400
        arduino_ok = False
    if arduino_ok and not resp.ok:
        logging.error('Cannot read Arduino data, status code: %s', resp.status_code)
        arduino_ok = False

    if arduino_ok:
        arduino_data = resp.json()

    # Read Wio Terminal
    try:
        with serial.Serial(env_settings['terminal_serial'], 115200, timeout=10) as ser:
            raw_data = ser.readline()
            if raw_data.decode() == '':
                logging.error('Got no serial data from Wio Terminal')
                return {}
            terminal_data = json.loads(raw_data)
    except serial.serialutil.SerialException:
        logging.exception('Cannot read Wio Terminal serial')
        return {}

    final_data = {'outsideTemperature': round(arduino_data['extTempSensor'], 2)
                  if arduino_ok else None,
                  'insideLight': terminal_data['light']}

    return final_data


async def scan_ruuvitags(rt_config, device):
    """Scan for RuuviTag(s)."""
    found_tags = {}

    def _get_tag_location(rt_config, mac):
        """Get RuuviTag location based on tag MAC address."""
        for tag in rt_config['tags']:
            if mac == tag['mac']:
                return tag['location']

        return None

    async def _async_scan(mac_addresses, bt_device):
        expected_tag_count = len(mac_addresses)

        async for tag_data in RuuviTagSensor.get_data_async(mac_addresses, bt_device):
            mac = tag_data[0]
            if mac in found_tags:
                continue

            found_tags[mac] = {'location': _get_tag_location(rt_config, mac),
                               'temperature': tag_data[1]['temperature'],
                               'pressure': tag_data[1]['pressure'],
                               'humidity': tag_data[1]['humidity'],
                               'battery_voltage': tag_data[1]['battery'] / 1000.0,
                               'rssi': tag_data[1]['rssi']}

            if len(found_tags) == expected_tag_count:
                break

    mac_addresses = [tag['mac'] for tag in rt_config['tags']]

    try:
        await asyncio.wait_for(_async_scan(mac_addresses, device), timeout=12)
    except asyncio.TimeoutError:
        logging.exception('RuuviTag scan timed out')

    return found_tags


def store_ruuvitags(config, tag_data):
    """Send provided RuuviTag data to the backend."""
    timestamp = get_timestamp(config['timezone'])

    for tag in tag_data.values():
        tag['timestamp'] = timestamp
        json_data = json.dumps(tag)

        resp = requests.post(config['ruuvitag']['url'],
                             params={'observation': json_data,
                                     'code': config['authentication_code']},
                             timeout=5)

        logging.info("RuuviTag observation request data: '%s', response: code %s, "
                     "text '%s'",
                     json_data, resp.status_code, resp.text)


def get_ble_beacons(config, device):
    """Return the MAC addresses and RSSI values of predefined Bluetooth BLE beacons."""
    min_ble_line_length = 15

    try:
        proc = subprocess.Popen([f'{config["beacon_scan_path"]}/ble_beacon_scan',
                                 '-t', str(config['beacon_scan_time']), '-d', device],
                                stdout=subprocess.PIPE)
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

    rssis = []
    split_output = output.split('\n')
    for line in split_output:
        if len(line) < min_ble_line_length:
            continue
        address, rssi = line.split(' ')
        if address == config['beacon_mac']:
            rssis.append(int(rssi))

    if len(rssis) > 0:
        return [{'mac': config['beacon_mac'],
                 'rssi': round(mean(rssis))}]

    return []


def store_to_db(timezone, config, data, auth_code):
    """Store the data in the backend database with a HTTP POST request."""
    if data == {}:
        logging.error('Received no data, stopping')
        return

    data['timestamp'] = get_timestamp(timezone)
    data = OrderedDict(sorted(data.items()))
    resp = requests.post(config['upload_url'],
                         params={'observation': json.dumps(data),
                                 'code': auth_code},
                         timeout=5)

    logging.info("Weather observation request data: '%s', response: code %s, text '%s'",
                 json.dumps(data), resp.status_code, resp.text)


def main():
    """Run the module code."""
    logging.basicConfig(format='%(asctime)s:%(levelname)s:%(message)s',
                        level=logging.INFO)

    parser = argparse.ArgumentParser(description='Scans environment data and sends it '
                                     'to the env-logger backend. A configuration file '
                                     'named "logger_config.json" is used unless '
                                     'provided with --config.')
    parser.add_argument('--config', type=str, help='Configuration file')
    parser.add_argument('--rt-device', type=str,
                        help='Bluetooth device to use for RuuviTag scanning')
    parser.add_argument('--ble-device', type=str,
                        help='Bluetooth device to use for BLE beacon scanning')
    parser.add_argument('--dummy', action='store_true',
                        help='Send dummy data (meant for testing)')

    args = parser.parse_args()
    config = args.config if args.config else 'logger_config.json'
    rt_device = args.rt_device if args.rt_device else 'hci0'
    ble_device = args.ble_device if args.ble_device else 'hci0'

    if not exists(config):
        logging.error('Could not find configuration file: %s', config)
        sys.exit(1)

    with open(config, 'r', encoding='utf-8') as config_file:
        try:
            config = json.load(config_file)
        except json.JSONDecodeError:
            logging.exception('Could not parse configuration file')
            sys.exit(1)

    env_config = config['environment']
    if args.dummy:
        env_data = {'insideLight': 10,
                    'outsideTemperature': 5,
                    'beacons': []}
    else:
        env_data = get_env_data(env_config)

        env_data['beacons'] = get_ble_beacons(env_config, ble_device)
        # Possible retry if first scan fails
        for _ in range(2):
            if env_data['beacons']:
                break

            orig_scan_time = env_config['beacon_scan_time']
            env_config['beacon_scan_time'] /= 2
            env_data['beacons'] = get_ble_beacons(env_config, ble_device)
            env_config['beacon_scan_time'] = orig_scan_time

    # This code only works with Python 3.10 and newer
    ruuvitags = asyncio.run(scan_ruuvitags(config['ruuvitag'], rt_device))
    store_ruuvitags(config, ruuvitags)

    store_to_db(config['timezone'], env_config, env_data, config['authentication_code'])


if __name__ == '__main__':
    main()
