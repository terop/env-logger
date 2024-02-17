#!/usr/bin/env python3

"""A program for fetching and sending environment data to the data logger backend."""

import argparse
import asyncio
import json
import logging
import os
import sys
from collections import OrderedDict
from datetime import datetime
from pathlib import Path
from statistics import mean, median

import pytz
import requests
import serial
from bleak import BleakScanner
from bleak.exc import BleakDBusError, BleakError
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
        logging.error('Connection problem to Arduino: %s', ex)  # noqa: TRY400
        arduino_ok = False
    if arduino_ok and not resp.ok:
        logging.error('Cannot read Arduino data, status code: %s', resp.status_code)
        arduino_ok = False

    if arduino_ok:
        arduino_data = resp.json()

    # Read Wio Terminal
    terminal_ok = True
    if not Path(env_settings['terminal_serial']).exists():
        logging.warning('Could not find Wio Terminal device "%s", '
                        'skipping Wio Terminal read',
                        env_settings['terminal_serial'])
        terminal_ok = False
    else:
        try:
            with serial.Serial(env_settings['terminal_serial'], 115200, timeout=10) \
                 as ser:
                raw_data = ser.readline()
                if not raw_data.decode():
                    logging.error('Got no serial data from Wio Terminal')
                    terminal_ok = False

                terminal_data = json.loads(raw_data)
        except serial.serialutil.SerialException:
            logging.exception('Cannot read Wio Terminal serial')
            terminal_ok = False

    final_data = {'outsideTemperature': round(arduino_data['extTempSensor'], 2)
                  if arduino_ok else None,
                  'insideLight': terminal_data['light'] if terminal_ok else None}

    return final_data


async def scan_ruuvitags(rt_config):
    """Scan for RuuviTag(s)."""
    found_tags = {}

    def _get_tag_location(mac):
        """Get RuuviTag location based on tag MAC address."""
        for tag in rt_config['tags']:
            if mac == tag['mac']:
                return tag['location']

        return None

    async def _async_scan(mac_addresses):
        expected_tag_count = len(mac_addresses)

        async for tag_data in RuuviTagSensor.get_data_async(mac_addresses):
            mac = tag_data[0]
            if mac in found_tags:
                continue

            found_tags[mac] = {'location': _get_tag_location(mac),
                               'temperature': tag_data[1]['temperature'],
                               'pressure': tag_data[1]['pressure'],
                               'humidity': tag_data[1]['humidity'],
                               'battery_voltage': tag_data[1]['battery'] / 1000.0,
                               'rssi': tag_data[1]['rssi']}

            if len(found_tags) == expected_tag_count:
                break

    mac_addresses = [tag['mac'] for tag in rt_config['tags']]

    try:
        await asyncio.wait_for(_async_scan(mac_addresses), timeout=10)
    except (asyncio.CancelledError, TimeoutError, BleakDBusError):
        logging.error('RuuviTag scan was cancelled or timed out, retrying')  # noqa: TRY400
        remaining_macs = [mac for mac in mac_addresses if mac not in found_tags]
        try:
            await asyncio.wait_for(_async_scan(remaining_macs), timeout=10)
        except asyncio.CancelledError:
            logging.error('RuuviTag scan cancelled on retry')  # noqa: TRY400
        except TimeoutError:
            logging.error('RuuviTag scan timed out on retry')  # noqa: TRY400
        except BleakDBusError as bde:
            logging.error('Bleak error during RuuviTag retry scan: %s', bde)  # noqa: TRY400

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


async def get_ble_beacon(config, bt_device):
    """Scan and return data on the configured Bluetooth LE beacon.

    Returns the MAC address, RSSI value and possibly battery level of the
    Bluetooth LE beacon.
    """
    scan_time = 11
    data = {'rssi': [], 'battery': []}
    battery_service_uuid = '00002080-0000-1000-8000-00805f9b34fb'

    def callback(device, ad):
        if device.address == config['beacon_mac']:
            data['rssi'].append(ad.rssi)
            if battery_service_uuid in ad.service_data \
               and ad.service_data[battery_service_uuid] is not None:
                data['battery'].append(ad.service_data[battery_service_uuid][0])

    try:
        additional_arguments = {'adapter': bt_device}
        scanner = BleakScanner(callback, **additional_arguments)

        await scanner.start()
        await asyncio.sleep(scan_time)
        await scanner.stop()
    except BleakError as be:
        logging.error('BLE beacon scan failed: %s', be)  # noqa: TRY400
        return {}

    if data['rssi']:
        return {'mac': config['beacon_mac'],
                'rssi': round(mean(data['rssi'])),
                'battery': round(median(data['battery'])) if data['battery'] else None}

    return {}


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
    parser.add_argument('--dummy', action='store_true',
                        help='Send dummy data (meant for testing)')
    parser.add_argument('--bt-device', type=str,
                        help='Bluetooth device to use (default: hci0)')

    args = parser.parse_args()
    config = args.config if args.config else 'logger_config.json'
    bt_device = args.bt_device if args.bt_device else 'hci0'

    if not Path(config).exists() or not Path(config).is_file():
        logging.error('Could not find configuration file: %s', config)
        sys.exit(1)

    with Path(config).open('r', encoding='utf-8') as config_file:
        try:
            config = json.load(config_file)
        except json.JSONDecodeError:
            logging.exception('Could not parse configuration file')
            sys.exit(1)

    env_config = config['environment']
    if args.dummy:
        env_data = {'insideLight': 10,
                    'outsideTemperature': 5,
                    'beacon': {}}
    else:
        env_data = get_env_data(env_config)

        env_data['beacon'] = asyncio.run(get_ble_beacon(env_config, bt_device))

    # This code only works with Python 3.10 and newer
    ruuvitags = asyncio.run(scan_ruuvitags(config['ruuvitag']))
    store_ruuvitags(config, ruuvitags)

    store_to_db(config['timezone'], env_config, env_data, config['authentication_code'])


if __name__ == '__main__':
    main()
