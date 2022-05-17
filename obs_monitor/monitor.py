#!/usr/bin/env python3
"""This is a small program for monitoring observations and alerting a maintainer
if observations are not received within a specified time."""

import argparse
import configparser
import json
import smtplib
import re
import sys
from datetime import datetime, timedelta
from email.mime.text import MIMEText
from os.path import exists

import psycopg
import requests
from bs4 import BeautifulSoup  # pylint: disable=import-error


class ObservationMonitor:
    """Class for monitoring environment observations."""

    def __init__(self, config, state):
        self._config = config
        self._state = state

    def get_obs_time(self):
        """Returns the recording time of the latest observation."""
        # pylint: disable=not-context-manager
        with psycopg.connect(create_db_conn_string(self._config['db'])) as conn:
            with conn.cursor() as cursor:
                cursor.execute('SELECT recorded FROM observations ORDER BY id DESC LIMIT 1')
                result = cursor.fetchone()
                return result[0] if result else datetime.now()

    def check_observation(self):
        """Checks when the last observation has been received and sends an email
        if the threshold is exceeded."""
        last_obs_time = self.get_obs_time()
        time_diff = datetime.now(tz=last_obs_time.tzinfo) - last_obs_time

        if int(time_diff.total_seconds() / 60) > int(self._config['observation']['Timeout']):
            if self._state['email_sent'] == 'False':
                # pylint: disable=consider-using-f-string
                if send_email(self._config['email'],
                              'env-logger: observation inactivity warning',
                              'No observations have been received in the env-logger '
                              'backend after {} (timeout {} minutes). Please check for '
                              'possible problems.'.format(last_obs_time.isoformat(),
                                                          self._config['observation']['Timeout'])):
                    self._state['email_sent'] = 'True'
                else:
                    self._state['email_sent'] = 'False'
        else:
            if self._state['email_sent'] == 'True':
                send_email(self._config['email'],
                           'env-logger: observation received',
                           f'An observation has been received at {last_obs_time.isoformat()}.')
                self._state['email_sent'] = 'False'

    def get_state(self):
        """Returns the observation state."""
        return self._state


class BeaconMonitor:
    """Class for monitoring BLE beacon scans."""
    def __init__(self, config, state):
        self._config = config
        self._state = state

    def get_beacon_scan_time(self):
        """Returns the recording time of the latest BLE beacon scan."""
        # pylint: disable=not-context-manager
        with psycopg.connect(create_db_conn_string(self._config['db'])) as conn:
            with conn.cursor() as cursor:
                cursor.execute('SELECT recorded FROM observations WHERE id = '
                               '(SELECT obs_id FROM beacons ORDER BY id DESC LIMIT 1)')
                result = cursor.fetchone()
                return result[0] if result else datetime.now()

    def check_beacon(self):
        """Checks the latest BLE beacon scan time and sends an email if the
        threshold is exceeded."""
        last_obs_time = self.get_beacon_scan_time()
        time_diff = datetime.now(tz=last_obs_time.tzinfo) - last_obs_time

        # Timeout is in hours
        if int(time_diff.total_seconds()) > int(self._config['blebeacon']['Timeout']) * 60 * 60:
            if self._state['email_sent'] == 'False':
                # pylint: disable=consider-using-f-string
                if send_email(self._config['email'],
                              'env-logger: BLE beacon inactivity warning',
                              'No BLE beacon has been scanned in env-logger '
                              'after {} (timeout {} hours). Please check for '
                              'possible problems.'.format(last_obs_time.isoformat(),
                                                          self._config['blebeacon']['Timeout'])):
                    self._state['email_sent'] = 'True'
        else:
            if self._state['email_sent'] == 'True':
                send_email(self._config['email'],
                           'env-logger: BLE beacon scanned',
                           f'BLE beacon scanned was at {last_obs_time.isoformat()}.')
                self._state['email_sent'] = 'False'

    def get_state(self):
        """Returns the BLE beacon scan state."""
        return self._state


class RuuvitagMonitor:
    """Class for monitoring RuuviTag beacon observations."""
    def __init__(self, config, state):
        self._config = config
        self._state = state

    def get_ruuvitag_scan_time(self):
        """Returns recording time of the latest RuuviTag beacon observation."""
        results = {}

        # pylint: disable=not-context-manager
        with psycopg.connect(create_db_conn_string(self._config['db'])) as conn:
            with conn.cursor() as cursor:
                for location in self._config['ruuvitag']['Location'].split(','):
                    cursor.execute("""SELECT recorded FROM ruuvitag_observations WHERE
                    location = %s ORDER BY recorded DESC LIMIT 1""", (location,))

                    result = cursor.fetchone()
                    results[location] = result[0] if result else datetime.now()

        return results

    def check_ruuvitag(self):
        """Checks the latest RuuviTag beacon scan time and sends an email if the
        threshold is exceeded."""
        last_obs_time = self.get_ruuvitag_scan_time()

        for location in self._config['ruuvitag']['Location'].split(','):
            time_diff = datetime.now(tz=last_obs_time[location].tzinfo) - last_obs_time[location]

            # Timeout is in minutes
            if int(time_diff.total_seconds()) > int(self._config['ruuvitag']['Timeout']) * 60:
                if self._state[location]['email_sent'] == 'False':
                    # pylint: disable=consider-using-f-string
                    if send_email(self._config['email'],
                                  f'env-logger: RuuviTag beacon "{location}" inactivity warning',
                                  'No RuuviTag observation for location "{}" has been '
                                  'scanned in env-logger after {} (timeout {} minutes). '
                                  'Please check for possible problems.'
                                  .format(location, last_obs_time[location].isoformat(),
                                          self._config['ruuvitag']['Timeout'])):
                        self._state[location]['email_sent'] = 'True'
                    else:
                        self._state[location]['email_sent'] = 'False'
            else:
                if self._state[location]['email_sent'] == 'True':
                    send_email(self._config['email'],
                               f'env-logger: Ruuvitag beacon "{location}" scanned',
                               f'A RuuviTag observation for location "{location}" '
                               f'was scanned at {last_obs_time[location].isoformat()}.')
                    self._state[location]['email_sent'] = 'False'

    def get_state(self):
        """Returns the RuuviTag scan state."""
        return self._state


def send_email(config, subject, message):
    """Sends an email with provided subject and message to specified recipients."""
    msg = MIMEText(message)
    msg['Subject'] = subject
    msg['From'] = config['Sender']
    msg['To'] = config['Recipient']

    try:
        with smtplib.SMTP_SSL(config['Server']) as server:
            server.login(config['User'], config['Password'])
            server.send_message(msg)
    except smtplib.SMTPException as smtp_e:
        print(f'Failed to send email with subject "{subject}": {str(smtp_e)}', file=sys.stderr)
        return False

    return True


def create_db_conn_string(db_config):
    """Create the database connection string."""
    return f'host={db_config["Host"]} user={db_config["User"]} password={db_config["Password"]} ' \
        f'dbname={db_config["Database"]}'


def main():
    """Module main function."""
    state_file_name = 'monitor_state.json'

    parser = argparse.ArgumentParser(description='Monitors observation reception.')
    parser.add_argument('--config', type=str, help='configuration file to use')

    args = parser.parse_args()
    config_file = args.config if args.config else 'monitor.cfg'

    if not exists(config_file):
        print(f'Error: could not find configuration file {args.config_file}', file=sys.stderr)
        sys.exit(1)

    config = configparser.ConfigParser()
    config.read(config_file)

    try:
        with open(state_file_name, 'r', encoding='utf-8') as state_file:
            state = json.load(state_file)
    except FileNotFoundError:
        state = {'observation': {'email_sent': 'False'},
                 'blebeacon': {'email_sent': 'False'},
                 'ruuvitag': {}}
        for location in config['ruuvitag']['Location'].split(','):
            state['ruuvitag'][location] = {}
            state['ruuvitag'][location]['email_sent'] = 'False'

    if config['observation']['Enabled'] == 'True':
        obs = ObservationMonitor(config, state['observation'])
        obs.check_observation()
        state['observation'] = obs.get_state()
    if config['blebeacon']['Enabled'] == 'True':
        beacon = BeaconMonitor(config, state['blebeacon'])
        beacon.check_beacon()
        state['blebeacon'] = beacon.get_state()
    if config['ruuvitag']['Enabled'] == 'True':
        ruuvitag = RuuvitagMonitor(config, state['ruuvitag'])
        ruuvitag.check_ruuvitag()
        state['ruuvitag'] = ruuvitag.get_state()

    with open(state_file_name, 'w', encoding='utf-8') as state_file:
        json.dump(state, state_file, indent=4)


if __name__ == '__main__':
    main()
