#!/usr/bin/env python3

"""A program for fetching and sending environment data to the data logger backend."""

# ruff: noqa: TRY400

import argparse
import asyncio
import json
import logging
import sys
import time
from collections import OrderedDict
from datetime import datetime
from math import hypot
from pathlib import Path
from statistics import mean, median

import pytz
import requests
from bleak import BleakScanner
from bleak.exc import BleakDBusError, BleakError
from requests.exceptions import ConnectionError as requests_ConnectionError
from ruuvitag_sensor.ruuvi import RuuviTagSensor
from urllib3.exceptions import ConnectTimeoutError, MaxRetryError, ReadTimeoutError

logger = logging.getLogger(__name__)

AQI_MAX = 100
PM25_MAX = 60
PM25_MIN = 0
PM25_SCALE = AQI_MAX / (PM25_MAX - PM25_MIN)
CO2_MAX = 2300
CO2_MIN = 420
CO2_SCALE = AQI_MAX / (CO2_MAX - CO2_MIN)


def get_timestamp(timezone):
    """Return the current timestamp for the given timezone in ISO 8601 format."""
    return datetime.now(pytz.timezone(timezone)).isoformat()


def get_data_from_arduino(env_settings):
    """Read environment data from Arduino.

    Returns the received data or None on failure.
    """
    arduino_ok = True
    try:
        resp = requests.get(env_settings['arduino_url'], timeout=5)
    except (requests_ConnectionError, OSError) as err:
        logger.error('Connection problem to Arduino: %s', err)
        arduino_ok = False
    if arduino_ok and not resp.ok:
        logger.error('Cannot read Arduino data, status code: %s', resp.status_code)
        arduino_ok = False

    if arduino_ok:
        try:
            arduino_data = resp.json()
        except json.JSONDecodeError as err:
            logger.error('Arduino JSON response decode failed: %s', err)
            arduino_ok = False

    return round(arduino_data['extTempSensor'], 2) if arduino_ok else None


def get_esp32_env_data(env_settings):
    """Read environment data from Xiao ESP32.

    Returns the received data or None on failure for values which cannot be read.
    """
    esp32_ok = True
    request_ok = True

    try:
        resp = requests.get(env_settings['esp32_url'], timeout=10)
    except (requests_ConnectionError, OSError) as err:
        logger.error('ESP32 data request failed: %s', err)
        request_ok = False

    if not request_ok or not resp.ok:
        esp32_ok = False
    else:
        try:
            esp32_data = resp.json()
        except json.JSONDecodeError as err:
            logger.error('ESP32 JSON response decode failed: %s', err)
            esp32_ok = False

    if esp32_ok:
        logger.info('ESP32 values: humidity %s', esp32_data['humidity'])

        return (esp32_data['light'], round(esp32_data['temperature'], 2),
                esp32_data['co2'], esp32_data['vocIndex'], esp32_data['noxIndex'])
    return (None, None, None, None, None)


def get_outside_light_value(env_settings):
    """Read light sensor value from outside Xiao ESP32.

    Returns the received data or None on failure.
    """
    try:
        resp = requests.get(env_settings['outside_esp32_url'], timeout=10)
    except (requests_ConnectionError, OSError) as err:
        logger.error('ESP32 data request failed: %s', err)
        return None

    try:
        esp32_data = resp.json()
    except json.JSONDecodeError as err:
        logger.error('ESP32 JSON response decode failed: %s', err)
        return None

    return esp32_data['light']


def calculate_iaqs(co2_value, pm25_value):
    """Calculate the Ruuvi indoor air quality score (IAQS).

    Documentation for the calculation algorithm can be found at
    https://docs.ruuvi.com/ruuvi-air-firmware/ruuvi-indoor-air-quality-score-iaqs.
    """
    def _clamp(value, low, high):
        """Constrain the input value between low and high values."""
        return min(max(value, low), high)

    if co2_value is None or pm25_value is None:
        return None

    co2_clamped = _clamp(co2_value, CO2_MIN, CO2_MAX)
    pm25_clamped = _clamp(pm25_value, PM25_MIN, PM25_MAX)

    dx = (pm25_clamped - PM25_MIN) * PM25_SCALE
    dy = (co2_clamped - CO2_MIN) * CO2_SCALE

    r = hypot(dx, dy)
    return round(_clamp(AQI_MAX - r, 0, AQI_MAX))


def process_ruuvi_device_data(device_type, device_data):
    """Process 'raw' Ruuvi device data."""
    if device_type == 'tag':
        return {'temperature': device_data[1]['temperature'],
                'pressure': device_data[1]['pressure'],
                'humidity': device_data[1]['humidity'],
                'battery_voltage': device_data[1]['battery'] / 1000.0,
                'rssi': device_data[1]['rssi']}

    if device_type == 'air':
        if not device_data[1]['calibration_in_progress']:
            # Data should not be used when calibration is in progress
            return {'co2': device_data[1]['co2'],
                    'nox': device_data[1]['nox'],
                    'voc': device_data[1]['voc'],
                    'pm_2_5': device_data[1]['pm_2_5'],
                    'iaqs': calculate_iaqs(device_data[1]['co2'],
                                           device_data[1]['pm_2_5'])}
    else:
        logger.error('Unknown Ruuvi device configured')

    return None


async def scan_ruuvi_devices(device_config, bt_device):  # noqa: C901,PLR0915
    """Scan for Ruuvi devices (Tag and Air)."""
    scan_timeout = device_config.get('scan_timeout', 5)
    pre_scan_sleep = 4
    found_devices = {}
    devices = {}
    device_names = {}

    for device in device_config['devices']:
        devices[device['mac']] = device['type']
        device_names[device['mac']] = device['name']

    async def _async_device_scan(devices, timeout, run_until_completion=False):
        expected_device_count = len(devices)
        found_count = 0
        start_time = datetime.now()
        timeout_advance = 2

        if run_until_completion:
            logger.info('Sleeping %s seconds before starting scan', pre_scan_sleep)
            await asyncio.sleep(pre_scan_sleep)
            logger.info('Starting scan')

        # In "timeout mode" look for all Ruuvi devices so that the stopping logic
        # will work
        device_macs = list(devices.keys()) if not run_until_completion else []

        async for device_data in RuuviTagSensor.get_data_async(device_macs, bt_device):
            elapsed_time = (datetime.now() - start_time).seconds
            if not run_until_completion and elapsed_time + timeout_advance >= timeout:
                logger.info('Stopping before timeout after running %s seconds',
                            elapsed_time)
                break

            mac = device_data[0]
            if mac not in device_macs or mac in found_devices:
                continue

            proc_data = process_ruuvi_device_data(devices[mac], device_data)
            if proc_data:
                found_devices[mac] = proc_data
                found_devices[mac]['name'] = device_names.get(mac)
                found_devices[mac]['type'] = devices[mac]

            found_count += 1

            if found_count == expected_device_count:
                break

    try:
        await asyncio.wait_for(_async_device_scan(devices, scan_timeout),
                               timeout=scan_timeout)

        if len(found_devices) < len(devices):
            # Try scan again for remaining devices
            logger.info('Retrying Ruuvi device scan')
            found_devices_macs = list(found_devices.keys())
            retry_devices = {}
            for mac in list(devices.keys()):
                if mac not in found_devices_macs:
                    retry_devices[mac] = devices[mac]

            min_retry_timeout = 6
            # Use a shorter time for retry as there is likely less Ruuvi devices to
            # look for
            retry_timeout = max(scan_timeout - 10, min_retry_timeout)

            await asyncio.wait_for(_async_device_scan(retry_devices, retry_timeout,
                                               run_until_completion=True),
                                   timeout=retry_timeout + pre_scan_sleep)

    except (asyncio.CancelledError, BleakError, BleakDBusError, TimeoutError) as err:
        match err:
            case BleakError():
                logger.error('Error from Bleak: %s', err)
            case asyncio.CancelledError():
                logger.error('Ruuvi device scan was cancelled')
            case TimeoutError():
                logger.error('Ruuvi device scan timed out')
            case _:
                logger.error('Ruuvi device scan failed for some other reason: %s', err)

    return [device for device in found_devices.values()]


def store_ruuvi_device_data(config, access_token, timestamp, device_data):
    """Send provided Ruuvi device data to the backend."""
    json_data = json.dumps(device_data)
    max_attempts = 2
    attempt_count = 0

    while attempt_count < max_attempts:
        try:
            resp = requests.post(config['ruuvi_device']['url'],
                                 headers={'Bearer': access_token},
                                 params={'observation': json_data,
                                         'timestamp': timestamp},
                                 timeout=15)
        except (ConnectTimeoutError, MaxRetryError, OSError, ReadTimeoutError) as err:
            logger.error('Ruuvi device data store failed: %s', err)
            attempt_count += 1
            time.sleep(10)
            continue

        logger.info("Ruuvi device observation: timestamp '%s', data: '%s', "
                    "response: code %s, text '%s'",
                    timestamp, json_data, resp.status_code, resp.text)
        break


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
        logger.error('BLE beacon scan failed: %s', be)
        return {}

    if data['rssi']:
        if not data['battery'] and config['ble_beacon_rescan_battery']:
            logger.info('Rescanning BLE beacon for battery data')
            try:
                scanner = BleakScanner(callback, adapter=bt_device)

                await scanner.start()
                await asyncio.sleep(4)
                await scanner.stop()
            except BleakError as be:
                logger.error('BLE beacon scan failed: %s', be)

        return {'mac': config['ble_beacon_mac'],
                'rssi': round(mean(data['rssi'])),
                'battery': round(median(data['battery'])) if data['battery'] else None}

    return {}


async def do_scan(config, bt_device):
    """Scan for BLE beacon and Ruuvi device(s)."""
    results = {}

    logger.info('BLE beacon scan started')
    results['ble_beacon'] = await scan_ble_beacon(config['environment'], bt_device)

    logger.info('Ruuvi device scan started')
    results['ruuvi_device'] = await scan_ruuvi_devices(config['ruuvi_device'],
                                                       bt_device)

    return results


def store_observation(config, access_token, timestamp, data):
    """Store the observation data to the backend database."""
    if data == {}:
        logger.error('Received no data, stopping')
        return

    max_attempts = 2
    attempt_count = 0
    data['timestamp'] = timestamp
    data = OrderedDict(sorted(data.items()))

    while attempt_count < max_attempts:
        try:
            resp = requests.post(config['environment']['upload_url'],
                                 headers={'Bearer': access_token},
                                 params={'observation': json.dumps(data)},
                                 timeout=15)
        except (ConnectTimeoutError, MaxRetryError, OSError, ReadTimeoutError,
                TimeoutError) as err:
            logger.error('Observation data store failed: %s', err)
            attempt_count += 1
            time.sleep(10)
            continue

        logger.info("Observation data: '%s', response: code %s, text '%s'",
                    json.dumps(data), resp.status_code, resp.text)
        break


def get_access_token(config):
    """Fetch JWT access token used for observation storage."""
    resp = requests.post(config['auth']['token_endpoint'],
                         data={'grant_type': 'client_credentials',
                               'client_id': config['auth']['client_id'],
                               'client_secret': config['auth']['client_secret']},
                         timeout=10)
    if not resp.ok:
        logger.error('JWT token fetch failed')
        return None

    return resp.json()['access_token']


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
    config_file = args.config or 'logger_config.json'
    bt_device = args.bt_device or 'hci0'

    if not Path(config_file).exists() or not Path(config_file).is_file():
        logger.error('Could not find configuration file: %s', config_file)
        sys.exit(1)

    with Path(config_file).open('r', encoding='utf-8') as conf_file:
        try:
            config = json.load(conf_file)
        except json.JSONDecodeError:
            logger.exception('Could not parse configuration file')
            sys.exit(1)

    env_config = config['environment']
    access_token = get_access_token(config)
    if not access_token:
        sys.exit(1)

    logger.info('Logger run started')

    if args.dummy:
        env_data = {'insideLight': 10,
                    'insideTemperature': 21,
                    'co2': 700,
                    'vocIndex': 100,
                    'noxIndex': 1,
                    'outsideTemperature': 5}
    else:
        env_data = {'outsideTemperature': get_data_from_arduino(env_config)}

        esp32_data = get_esp32_env_data(env_config)
        env_data['insideLight'] = esp32_data[0]
        env_data['insideTemperature'] = esp32_data[1]
        env_data['co2'] = esp32_data[2]
        env_data['vocIndex'] = esp32_data[3]
        env_data['noxIndex'] = esp32_data[4]
        env_data['outsideLight'] = get_outside_light_value(env_config)

    timestamp = get_timestamp(config['timezone'])
    scan_result = asyncio.run(do_scan(config, bt_device))

    env_data['beacon'] = scan_result['ble_beacon']
    if env_data['insideLight'] is not None:
        # Only send environment data when required values are available
        store_observation(config, access_token, timestamp, env_data)

    store_ruuvi_device_data(config, access_token,
                            timestamp, scan_result['ruuvi_device'])


if __name__ == '__main__':
    main()
