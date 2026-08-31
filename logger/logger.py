#!/usr/bin/env python3

"""A program for fetching and sending environment data to the data logger backend."""

import argparse
import asyncio
import json
import logging
import sys
import time
import tomllib
from dataclasses import dataclass
from datetime import datetime
from math import hypot
from pathlib import Path
from statistics import mean, median
from typing import Any
from zoneinfo import ZoneInfo

import requests
from bleak import BleakScanner
from bleak.exc import BleakDBusError, BleakError
from ruuvitag_sensor.ruuvi import RuuviTagSensor

logger = logging.getLogger(__name__)

AQI_MAX = 100
PM25_MAX = 60
PM25_MIN = 0
PM25_SCALE = AQI_MAX / (PM25_MAX - PM25_MIN)
CO2_MAX = 2300
CO2_MIN = 420
CO2_SCALE = AQI_MAX / (CO2_MAX - CO2_MIN)
ARDUINO_REQUEST_TIMEOUT = 5
ESP32_REQUEST_TIMEOUT = 10
OUTSIDE_ESP32_REQUEST_TIMEOUT = 10
AUTH_REQUEST_TIMEOUT = 10
UPLOAD_REQUEST_TIMEOUT = 15
RETRY_SLEEP_SECONDS = 10
RUUVI_TIMEOUT_ADVANCE_SECONDS = 2
RUUVI_PRE_SCAN_SLEEP_SECONDS = 4
RUUVI_MIN_RETRY_TIMEOUT = 6
BLE_SCAN_SECONDS = 8
BLE_BATTERY_RESCAN_SECONDS = 4


@dataclass(frozen=True)
class Esp32EnvData:
    """Environment values read from Xiao ESP32."""

    light: int | None = None
    temperature: float | None = None
    co2: int | None = None
    voc_index: int | None = None
    nox_index: int | None = None


def get_timestamp(timezone: str) -> str:
    """Return the current timestamp for the given timezone in ISO 8601 format."""
    return datetime.now(ZoneInfo(timezone)).isoformat()


def fetch_json(
    url: str,
    timeout: int | float,
    source_name: str,
) -> dict[str, Any] | None:
    """Fetch and decode JSON data from a given URL."""
    try:
        resp = requests.get(url, timeout=timeout)
        resp.raise_for_status()
    except (requests.RequestException, OSError) as err:
        logger.error('%s request failed: %s', source_name, err)
        return None

    try:
        return resp.json()
    except ValueError as err:
        logger.error('%s JSON response decode failed: %s', source_name, err)
        return None


def get_data_from_arduino(env_settings: dict[str, Any]) -> float | None:
    """Read environment data from Arduino.

    Returns the received data or None on failure.
    """
    arduino_data = fetch_json(env_settings['arduino_url'],
                              ARDUINO_REQUEST_TIMEOUT,
                              'Arduino')
    if not arduino_data:
        return None

    outside_temp = arduino_data.get('extTempSensor')
    if outside_temp is None:
        logger.error("Arduino response missing 'extTempSensor'")
        return None

    try:
        return round(float(outside_temp), 2)
    except (TypeError, ValueError) as err:
        logger.error("Arduino 'extTempSensor' value is invalid: %s", err)
        return None


def get_esp32_env_data(env_settings: dict[str, Any]) -> Esp32EnvData:
    """Read environment data from Xiao ESP32.

    Returns the received data, using None for values that cannot be read.
    """
    esp32_data = fetch_json(env_settings['esp32_url'],
                            ESP32_REQUEST_TIMEOUT,
                            'ESP32')
    if not esp32_data:
        return Esp32EnvData()

    logger.info('ESP32 values: humidity %s', esp32_data.get('humidity'))
    temperature = esp32_data.get('temperature')

    rounded_temperature = (
        round(temperature, 2) if temperature is not None else None
    )
    return Esp32EnvData(
        light=esp32_data.get('light'),
        temperature=rounded_temperature,
        co2=esp32_data.get('co2'),
        voc_index=esp32_data.get('vocIndex'),
        nox_index=esp32_data.get('noxIndex'),
    )


def get_outside_light_value(env_settings: dict[str, Any]) -> int | None:
    """Read light sensor value from outside Xiao ESP32.

    Returns the received data or None on failure.
    """
    esp32_data = fetch_json(env_settings['outside_esp32_url'],
                            OUTSIDE_ESP32_REQUEST_TIMEOUT,
                            'Outside ESP32')
    if not esp32_data:
        return None
    return esp32_data.get('light')


def calculate_iaqs(co2_value: int | None, pm25_value: int | None) -> int | None:
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


def process_ruuvi_device_data(
    device_type: str,
    device_data: tuple[str, dict[str, Any]],
) -> dict[str, Any] | None:
    """Process 'raw' Ruuvi device data."""
    if device_type == 'tag':
        return {'temperature': device_data[1]['temperature'],
                'pressure': device_data[1]['pressure'],
                'humidity': device_data[1]['humidity'],
                'battery_voltage': device_data[1]['battery'] / 1000.0,
                'rssi': device_data[1]['rssi']}

    if device_type == 'air':
        data = device_data[1]
        if any(data[k] is None for k in ('co2', 'nox', 'voc')):
            logger.error('Received invalid measurement values from Ruuvi Air, '
                         'not sending')
            return None

        return {'co2': data['co2'],
                'nox': data['nox'],
                'voc': data['voc'],
                'pm_2_5': data['pm_2_5'],
                'iaqs': calculate_iaqs(data['co2'], data['pm_2_5'])}

    logger.error('Unknown Ruuvi device configured')

    return None


async def scan_ruuvi_devices(device_config: dict[str, Any], bt_device: str):  # noqa: C901,PLR0915
    """Scan for Ruuvi devices (Tag and Air)."""
    scan_timeout = device_config.get('scan_timeout', 5)
    pre_scan_sleep = RUUVI_PRE_SCAN_SLEEP_SECONDS
    found_devices = {}
    devices = {}
    device_names = {}

    for device in device_config['devices']:
        devices[device['mac']] = device['type']
        device_names[device['mac']] = device['name']

    async def _async_device_scan(devices: dict[str, str],
                                 scan_duration: int,
                                 run_until_completion: bool = False):
        expected_device_count = len(devices)
        found_count = 0
        start_time = time.monotonic()
        timeout_advance = RUUVI_TIMEOUT_ADVANCE_SECONDS

        if run_until_completion:
            logger.info('Sleeping %s seconds before starting scan', pre_scan_sleep)
            await asyncio.sleep(pre_scan_sleep)
            logger.info('Starting scan')

        # In "timeout mode" look for all Ruuvi devices so that the stopping logic
        # will work
        device_macs = list(devices.keys()) if not run_until_completion else []

        async for device_data in RuuviTagSensor.get_data_async(device_macs, bt_device):
            elapsed_time = int(time.monotonic() - start_time)
            if (not run_until_completion
                    and elapsed_time + timeout_advance >= scan_duration):
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
            retry_devices = {mac: devices[mac] for mac in devices
                             if mac not in found_devices}

            min_retry_timeout = RUUVI_MIN_RETRY_TIMEOUT
            # Use a shorter time for retry as there is likely less Ruuvi devices to
            # look for
            retry_timeout = max(scan_timeout - 10, min_retry_timeout)

            await asyncio.wait_for(_async_device_scan(retry_devices, retry_timeout,
                                               run_until_completion=True),
                                   timeout=retry_timeout + pre_scan_sleep)

    except (asyncio.CancelledError, BleakError, BleakDBusError, TimeoutError) as err:
        missing_names = [device_names[mac] for mac in devices
                         if mac not in found_devices]
        match err:
            case BleakError():
                logger.error('Error from Bleak: %s', err)
            case asyncio.CancelledError():
                logger.error('Ruuvi device scan was cancelled')
            case TimeoutError():
                if missing_names:
                    logger.error('Ruuvi device scan timed out, devices not found: %s',
                                 ','.join(missing_names))
                else:
                    logger.error('Ruuvi device scan timed out')
            case _:
                logger.error('Ruuvi device scan failed for some other reason: %s', err)

    return list(found_devices.values())


async def scan_ble_beacon(config: dict[str, Any], bt_device: str) -> dict[str, Any]:
    """Scan and return data on the configured Bluetooth LE beacon.

    Returns the MAC address, RSSI value and possibly battery level of the
    Bluetooth LE beacon.
    """
    data: dict[str, list[int]] = {'rssi': [], 'battery': []}
    battery_service_uuid = '00002080-0000-1000-8000-00805f9b34fb'

    def callback(device, ad):
        if device.address == config['ble_beacon_mac']:
            data['rssi'].append(ad.rssi)
            if battery_service_uuid in ad.service_data \
               and ad.service_data[battery_service_uuid] is not None:
                data['battery'].append(ad.service_data[battery_service_uuid][0])

    try:
        scanner = BleakScanner(callback, bluez={'adapter': bt_device})

        await scanner.start()
        await asyncio.sleep(BLE_SCAN_SECONDS)
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
                await asyncio.sleep(BLE_BATTERY_RESCAN_SECONDS)
                await scanner.stop()
            except BleakError as be:
                logger.error('BLE beacon scan failed: %s', be)

        return {'mac': config['ble_beacon_mac'],
                'rssi': round(mean(data['rssi'])),
                'battery': round(median(data['battery'])) if data['battery'] else None}

    return {}


async def do_scan(config: dict[str, Any], bt_device: str) -> dict[str, Any]:
    """Scan for BLE beacon and Ruuvi device(s)."""
    results = {}

    logger.info('BLE beacon scan started')
    results['ble_beacon'] = await scan_ble_beacon(config['environment'], bt_device)

    logger.info('Ruuvi device scan started')
    results['ruuvi_device'] = await scan_ruuvi_devices(config['ruuvi_device'],
                                                       bt_device)

    return results


def store_observation(config: dict[str, Any],
                      access_token: str,
                      timestamp: str,
                      data: dict[str, Any]) -> None:
    """Store the observation data to the backend database."""
    if not data:
        logger.error('Received no data, stopping')
        return

    max_attempts = 2
    data['timestamp'] = timestamp
    data = dict(sorted(data.items()))
    payload = json.dumps(data)

    for attempt in range(1, max_attempts + 1):
        try:
            resp = requests.post(config['environment']['upload_url'],
                                 headers={'Bearer': access_token},
                                 params={'observation': payload},
                                 timeout=UPLOAD_REQUEST_TIMEOUT)
            resp.raise_for_status()
        except (requests.RequestException, OSError, TimeoutError) as err:
            logger.error('Observation data store failed (attempt %s/%s): %s',
                         attempt, max_attempts, err)
            if attempt < max_attempts:
                time.sleep(RETRY_SLEEP_SECONDS)
            continue

        logger.info("Observation data: '%s', response: code %s, text '%s'",
                    payload, resp.status_code, resp.text)
        return


def get_access_token(config: dict[str, Any]) -> str | None:
    """Fetch JWT access token used for observation storage."""
    try:
        resp = requests.post(config['auth']['token_endpoint'],
                             data={'grant_type': 'client_credentials',
                                   'client_id': config['auth']['client_id'],
                                   'client_secret': config['auth']['client_secret']},
                             timeout=AUTH_REQUEST_TIMEOUT)
        resp.raise_for_status()
    except (requests.RequestException, OSError) as err:
        logger.error('JWT token fetch failed: %s', err)
        return None

    try:
        return resp.json()['access_token']
    except (ValueError, KeyError) as err:
        logger.error('JWT token response did not contain access token: %s', err)
        return None


def store_ruuvi_device_data(config: dict[str, Any],
                            access_token: str,
                            timestamp: str,
                            device_data: list[dict[str, Any]]) -> None:
    """Send provided Ruuvi device data to the backend."""
    json_data = json.dumps(device_data)
    max_attempts = 2

    for attempt in range(1, max_attempts + 1):
        try:
            resp = requests.post(config['ruuvi_device']['url'],
                                 headers={'Bearer': access_token},
                                 params={'observation': json_data,
                                         'timestamp': timestamp},
                                 timeout=UPLOAD_REQUEST_TIMEOUT)
            resp.raise_for_status()
        except (requests.RequestException, OSError) as err:
            logger.error('Ruuvi device data store failed (attempt %s/%s): %s',
                         attempt, max_attempts, err)
            if attempt < max_attempts:
                time.sleep(RETRY_SLEEP_SECONDS)
            continue

        logger.info("Ruuvi device observation: timestamp '%s', data: '%s', "
                    "response: code %s, text '%s'",
                    timestamp, json_data, resp.status_code, resp.text)
        return


def build_dummy_env_data() -> dict[str, Any]:
    """Build dummy environment data for test runs."""
    return {'insideLight': 10,
            'insideTemperature': 21,
            'co2': 700,
            'vocIndex': 100,
            'noxIndex': 1,
            'outsideTemperature': 5}


def build_env_data(env_config: dict[str, Any]) -> dict[str, Any]:
    """Build environment data from configured sensors."""
    esp32_data = get_esp32_env_data(env_config)
    return {'outsideTemperature': get_data_from_arduino(env_config),
            'insideLight': esp32_data.light,
            'insideTemperature': esp32_data.temperature,
            'co2': esp32_data.co2,
            'vocIndex': esp32_data.voc_index,
            'noxIndex': esp32_data.nox_index,
            'outsideLight': get_outside_light_value(env_config)}


def main() -> None:
    """Run the module code."""
    logging.basicConfig(format='%(asctime)s:%(levelname)s:%(message)s',
                        level=logging.INFO)

    parser = argparse.ArgumentParser(
        description="""Scans environment data and sends it to the env-logger backend.
        A configuration file named "logger_config.toml" is used unless provided with
        the --config flag.""")
    parser.add_argument('--config', type=str,
                        help='TOML configuration file to use')
    parser.add_argument('--dummy', action='store_true',
                        help='Send dummy data (meant for testing)')
    parser.add_argument('--bt-device', type=str,
                        help='Bluetooth device to use (default: hci0)')

    args = parser.parse_args()
    config_file = args.config or 'logger_config.toml'
    bt_device = args.bt_device or 'hci0'

    if not Path(config_file).exists() or not Path(config_file).is_file():
        logger.error('Could not find configuration file: %s', config_file)
        sys.exit(1)

    with Path(config_file).open('rb') as conf_file:
        try:
            config = tomllib.load(conf_file)
        except tomllib.TOMLDecodeError:
            logger.exception('Could not parse configuration file')
            sys.exit(1)

    env_config = config['environment']
    access_token = get_access_token(config)
    if not access_token:
        sys.exit(1)

    logger.info('Logger run started')

    env_data = build_dummy_env_data() if args.dummy else build_env_data(env_config)

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
