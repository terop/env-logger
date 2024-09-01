#!/usr/bin/env python3

"""A program for fetching and sending environment data to the data logger backend."""

# ruff: noqa: TRY400

import argparse
import asyncio
import json
import logging
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
from ruuvitag_sensor.ruuvi import RuuviTagSensor
from urllib3.exceptions import ConnectTimeoutError, MaxRetryError, ReadTimeoutError


def get_timestamp(timezone):
    """Return the current timestamp for the given timezone in ISO 8601 format."""
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
        logging.error('Connection problem to Arduino: %s', ex)
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


async def scan_ruuvitags(bt_device, rt_config):
    """Scan for RuuviTag(s)."""
    found_tags = {}

    def _get_tag_name(mac):
        """Get RuuviTag name based on tag MAC address."""
        for tag in rt_config['tags']:
            if mac == tag['mac']:
                return tag['name']

        return None

    async def _async_scan(macs):
        expected_tag_count = len(macs)
        found_count = 0

        async for tag_data in RuuviTagSensor.get_data_async(macs, bt_device):
            mac = tag_data[0]
            if mac in found_tags:
                continue

            found_tags[mac] = {'name': _get_tag_name(mac),
                               'temperature': tag_data[1]['temperature'],
                               'pressure': tag_data[1]['pressure'],
                               'humidity': tag_data[1]['humidity'],
                               'battery_voltage': tag_data[1]['battery'] / 1000.0,
                               'rssi': tag_data[1]['rssi']}
            found_count += 1

            if found_count == expected_tag_count:
                break

    macs = [tag['mac'] for tag in rt_config['tags']]

    try:
        await asyncio.wait_for(_async_scan(macs),
                               timeout=rt_config.get('scan_timeout', 20))
    except (asyncio.CancelledError, TimeoutError, BleakDBusError) as err:
        match err:
            case asyncio.CancelledError():
                logging.error('RuuviTag scan was cancelled')
            case TimeoutError():
                logging.error('RuuviTag scan timed out')
            case _:
                logging.error('RuuviTag scan failed for some other reason')

    return [tag for tag in found_tags.values()]


def store_ruuvitags(config, timestamp, tags):
    """Send provided RuuviTag data to the backend."""
    json_data = json.dumps(tags)

    try:
        resp = requests.post(config['ruuvitag']['url'],
                             params={'observation': json_data,
                                     'timestamp': timestamp,
                                     'code': config['authentication_code']},
                             timeout=15)
    except (ConnectTimeoutError, MaxRetryError, OSError, ReadTimeoutError) as err:
        logging.error('RuuviTag data store failed: %s', err)
        return

    logging.info("RuuviTag observation: timestamp '%s', data: '%s', "
                 "response: code %s, text '%s'",
                 timestamp, json_data, resp.status_code, resp.text)


async def scan_ble_beacon(config, bt_device):
    """Scan and return data on the configured Bluetooth LE beacon.

    Returns the MAC address, RSSI value and possibly battery level of the
    Bluetooth LE beacon.
    """
    scan_time = 8
    data = {'rssi': [], 'battery': []}
    battery_service_uuid = '00002080-0000-1000-8000-00805f9b34fb'

    def callback(device, ad):
        if device.address == config['ble_beacon_mac']:
            data['rssi'].append(ad.rssi)
            if battery_service_uuid in ad.service_data \
               and ad.service_data[battery_service_uuid] is not None:
                data['battery'].append(ad.service_data[battery_service_uuid][0])

    try:
        scanner = BleakScanner(callback, adapter=bt_device)

        await scanner.start()
        await asyncio.sleep(scan_time)
        await scanner.stop()
    except BleakError as be:
        logging.error('BLE beacon scan failed: %s', be)
        return {}

    if data['rssi']:
        if not data['battery'] and config['ble_beacon_rescan_battery']:
            logging.info('Rescanning BLE beacon for battery data')
            try:
                scanner = BleakScanner(callback, adapter=bt_device)

                await scanner.start()
                await asyncio.sleep(4)
                await scanner.stop()
            except BleakError as be:
                logging.error('BLE beacon scan failed: %s', be)

        return {'mac': config['ble_beacon_mac'],
                'rssi': round(mean(data['rssi'])),
                'battery': round(median(data['battery'])) if data['battery'] else None}

    return {}


def store_to_db(config, timestamp, data):
    """Store the observation data to the backend database."""
    if data == {}:
        logging.error('Received no data, stopping')
        return

    data['timestamp'] = timestamp
    data = OrderedDict(sorted(data.items()))

    try:
        resp = requests.post(config['environment']['upload_url'],
                             params={'observation': json.dumps(data),
                                     'code': config['authentication_code']},
                             timeout=15)
    except (ConnectTimeoutError, MaxRetryError, OSError, ReadTimeoutError,
            TimeoutError) as err:
        logging.error('Observation data store failed: %s', err)
        return

    logging.info("Observation data: '%s', response: code %s, text '%s'",
                 json.dumps(data), resp.status_code, resp.text)


def main():
    """Run the module code."""
    logging.basicConfig(format='%(asctime)s:%(levelname)s:%(message)s',
                        level=logging.INFO)

    parser = argparse.ArgumentParser(
        description="""Scans environment data and sends it to the env-logger backend.
        A configuration file named "logger_config.json" is used unless provided with
        the --config flag.""")
    parser.add_argument('--config', type=str, help='Configuration file to use')
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

    logging.info('Logger run started')

    if args.dummy:
        env_data = {'insideLight': 10,
                    'outsideTemperature': 5}
    else:
        env_data = get_env_data(env_config)

    logging.info('BLE beacon scan started')
    env_data['beacon'] = asyncio.run(scan_ble_beacon(env_config, bt_device))
    timestamp = get_timestamp(config['timezone'])
    store_to_db(config, timestamp, env_data)

    # This code only works with Python 3.10 and newer
    logging.info('RuuviTag scan started')
    ruuvitags = asyncio.run(scan_ruuvitags(bt_device, config['ruuvitag']))
    store_ruuvitags(config, timestamp, ruuvitags)


if __name__ == '__main__':
    main()
