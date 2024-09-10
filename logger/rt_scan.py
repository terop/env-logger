#!/usr/bin/env python3

"""A program for fetching and sending environment data to the data logger backend."""

# ruff: noqa: TRY400

import argparse
import asyncio
import json
import logging
import sys
from ast import literal_eval
from pathlib import Path

from bleak.exc import BleakDBusError
from ruuvitag_sensor.ruuvi import RuuviTagSensor


async def scan_ruuvitags(bt_device, rt_config, tag_macs=None):
    """Scan for RuuviTag(s)."""
    found_tags = {}
    success = True

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

    macs = tag_macs if tag_macs else [tag['mac'] for tag in rt_config['tags']]

    try:
        await asyncio.wait_for(_async_scan(macs),
                               timeout=rt_config.get('scan_timeout', 5))
    except (asyncio.CancelledError, TimeoutError, BleakDBusError) as err:
        success = False
        match err:
            case asyncio.CancelledError():
                logging.error('RuuviTag scan was cancelled')
            case TimeoutError():
                logging.error('RuuviTag scan timed out')
            case _:
                logging.error('RuuviTag scan failed for some other reason')

    return {'tags': [tag for tag in found_tags.values()], 'success': success}


def main():
    """Run the module code."""
    logging.basicConfig(format='%(asctime)s:%(levelname)s:%(message)s',
                        level=logging.INFO)

    parser = argparse.ArgumentParser(
        description="""Scans environment data and sends it to the env-logger backend.
        A configuration file named "logger_config.json" is used unless provided with
        the --config flag.""")
    parser.add_argument('--config', type=str, help='Configuration file to use')
    parser.add_argument('--bt-device', type=str,
                        help='Bluetooth device to use (default: hci0)')
    parser.add_argument('--macs', type=str,
                        help='Specific MAC addresses to scan')

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

    # This code only works with Python 3.10 and newer
    macs = literal_eval(args.macs) if args.macs else None
    ret_val = asyncio.run(scan_ruuvitags(bt_device, config['ruuvitag'], macs))
    print(ret_val['tags'])
    if not ret_val['success']:
        sys.exit(1)


if __name__ == '__main__':
    main()
