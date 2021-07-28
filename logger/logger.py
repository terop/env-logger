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
import serial


def get_timestamp(timezone):
    """Returns the current timestamp in ISO 8601 format."""
    return datetime.now(pytz.timezone(timezone)).isoformat()


def get_env_data(env_settings):
    """Fetches the environment data from the Arduino and Wio Terminal.
    Returns the parsed JSON object or an empty dictionary on failure."""
    # Read Arduino
    resp = requests.get(env_settings['arduino_url'])
    if not resp.ok:
        logging.error('Cannot read Arduino data')
        return {}

    arduino_data = resp.json()

    if env_settings['skip_terminal_reading']:
        return arduino_data

    # Read Wio Terminal
    try:
        with serial.Serial(env_settings['terminal_serial'], 115200, timeout=10) as ser:
            raw_data = ser.readline()
            if raw_data.decode() == '':
                logging.error('Got no serial data from Wio Terminal')
                return {}
            terminal_data = json.loads(raw_data)
    except serial.serialutil.SerialException as exc:
        logging.error('Cannot read Wio Terminal serial: %s', exc)
        return {}

    final_data = {'insideTemperature': round(terminal_data['temperature'], 2),
                  'outsideTemperature': round(arduino_data['outsideTemperature'], 2),
                  'insideLight': terminal_data['light']}

    return final_data


def scan_ruuvitags(config, device):
    """Scan RuuviTag(s) and upload data."""

    def _get_tag_location(config, mac):
        """Get RuuviTag location based on tag MAC address."""
        for tag in config['ruuvitag']['tags']:
            if mac == tag['mac'].lower():
                return tag['location']

        return None

    def _run_bluewalker(addr_filter):
        """Run bluewalker."""
        json_filename = 'bluewalker.json'

        # The Bluetooth device must be down before starting the scan
        try:
            subprocess.run(f'sudo hciconfig {device} down',
                           shell=True, check=True)
        except subprocess.CalledProcessError as cpe:
            logging.error('Failed to set device %s down before scan, rc: %s',
                          device, cpe.returncode)
            sys.exit(1)

        try:
            subprocess.run(f'sudo {command} -device {device} -duration 10 -ruuvi -json '
                           f'-filter-addr "{addr_filter}" -output-file {json_filename}',
                           shell=True, check=True)
        except subprocess.CalledProcessError as cpe:
            logging.error('bluewalker command failed with rc: %s', cpe.returncode)
            return

        with open(json_filename, 'r') as json_file:
            tag_data = [json.loads(line.strip()) for line in json_file.readlines()]

        os.remove(json_filename)

        if not tag_data:
            return

        tag_found = {}
        timestamp = get_timestamp(config['timezone'])
        for tag in tag_data:
            mac = tag['device']['address']
            if mac in tag_found:
                continue

            tag_found[mac] = True

            data = {'timestamp': timestamp,
                    'location': _get_tag_location(config, mac),
                    'temperature': round(tag['sensors']['temperature'], 2),
                    'pressure': round(tag['sensors']['pressure'] / 100.0, 2),
                    'humidity': round(tag['sensors']['humidity'], 2),
                    'battery_voltage': tag['sensors']['voltage'] / 1000.0}

            resp = requests.post(config['ruuvitag']['url'],
                                 params={'observation': json.dumps(data),
                                         'code': config['authentication_code']})

            logging.info("RuuviTag observation request data: '%s', response: code %s, text '%s'",
                         json.dumps(data), resp.status_code, resp.text)

        return tag_found

    command = which('bluewalker', os.X_OK)
    if not command:
        logging.error('Could not find the bluewalker binary')
        sys.exit(1)

    mac_addrs = ';'.join([f'{tag["mac"]},random' for tag
                          in config['ruuvitag']['tags']])

    scanned_tags = _run_bluewalker(mac_addrs)
    rescan_addrs = []

    for tag in config['ruuvitag']['tags']:
        if tag['mac'].lower() not in scanned_tags:
            rescan_addrs.append(f'{tag["mac"]},random')

    if len(rescan_addrs) > 0:
        # Do a second scan for the missing tags
        sleep(5)
        _run_bluewalker(';'.join(rescan_addrs))


def get_ble_beacons(config, device):
    """Returns the MAC addresses and RSSI values of predefined Bluetooth BLE
    beacons in the vicinity in a dict."""
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
        if len(line) < 15:
            continue
        address, rssi = line.split(' ')
        if address == config['beacon_mac']:
            rssis.append(int(rssi))

    if len(rssis) > 0:
        return [{'mac': config['beacon_mac'],
                 'rssi': round(mean(rssis))}]

    return []


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
    logging.basicConfig(format='%(asctime)s:%(levelname)s:%(message)s', level=logging.INFO)

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

    with open(config, 'r') as config_file:
        try:
            config = json.load(config_file)
        except json.JSONDecodeError as err:
            logging.error('Could not parse configuration file: %s', err)
            sys.exit(1)

    env_config = config['environment']
    if args.dummy:
        env_data = {'insideLight': 10,
                    'insideTemperature': 20,
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

    scan_ruuvitags(config, rt_device)

    store_to_db(config['timezone'], env_config, env_data, config['authentication_code'])


if __name__ == '__main__':
    main()
