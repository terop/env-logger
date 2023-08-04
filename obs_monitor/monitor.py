#!/usr/bin/env python3
"""A program for monitoring observations and maintainer alerting.

A notification (email) is sent if observations are not received within a
specified time.
"""

import argparse
import configparser
import json
import logging
import smtplib
import ssl
import sys
from datetime import datetime
from email.mime.text import MIMEText
from os import environ
from os.path import exists, isdir
from zoneinfo import ZoneInfo

import psycopg


class ObservationMonitor:
    """Class for monitoring environment observations."""

    def __init__(self, config, state):
        """Class constructor."""
        self._config = config
        self._state = state

    def get_obs_time(self):
        """Return the recording time of the latest observation."""
        with psycopg.connect(create_db_conn_string(self._config['db'])) as conn:
            with conn.cursor() as cursor:
                cursor.execute('SELECT recorded FROM observations '
                               'ORDER BY id DESC LIMIT 1')
                result = cursor.fetchone()
                return result[0] if result else datetime.now()

    def check_observation(self):
        """Check when the last observation has been received.

        Sends an email if the threshold is exceeded.
        """
        last_obs_time = self.get_obs_time()
        last_obs_time_tz = last_obs_time.astimezone(
                    ZoneInfo(self._config['db']['DisplayTimezone']))
        time_diff = datetime.now(tz=last_obs_time.tzinfo) - last_obs_time

        if int(time_diff.total_seconds() / 60) > \
           int(self._config['observation']['Timeout']):
            if self._state['email_sent'] == 'False':
                if send_email(self._config['email'],
                              'env-logger: observation inactivity warning',
                              'No observations have been received in the env-logger '
                              'backend after {} (timeout {} minutes). Please check for '
                              'possible problems.'.format(last_obs_time_tz.isoformat(),
                                                          self._config['observation']['Timeout'])):
                    self._state['email_sent'] = 'True'
                else:
                    self._state['email_sent'] = 'False'
        elif self._state['email_sent'] == 'True':
            send_email(self._config['email'],
                       'env-logger: observation received',
                       'An observation has been received at '
                       f'{last_obs_time_tz.isoformat()}.')
            self._state['email_sent'] = 'False'

    def get_state(self):
        """Return the observation state."""
        return self._state


class BeaconMonitor:
    """Class for monitoring BLE beacon scans."""

    def __init__(self, config, state):
        """Class constructor."""
        self._config = config
        self._state = state

    def get_beacon_scan_time(self):
        """Return the recording time of the latest BLE beacon scan."""
        with psycopg.connect(create_db_conn_string(self._config['db'])) as conn:
            with conn.cursor() as cursor:
                cursor.execute('SELECT recorded FROM observations WHERE id = '
                               '(SELECT obs_id FROM beacons ORDER BY id DESC LIMIT 1)')
                result = cursor.fetchone()
                return result[0] if result else datetime.now()

    def check_beacon(self):
        """Check the latest BLE beacon scan time.

        Sends an email if the threshold is exceeded.
        """
        last_obs_time = self.get_beacon_scan_time()
        last_obs_time_tz = last_obs_time.astimezone(ZoneInfo( \
            self._config['db']['DisplayTimezone']))
        time_diff = datetime.now(tz=last_obs_time.tzinfo) - last_obs_time

        # Timeout is in hours
        if int(time_diff.total_seconds()) > \
           int(self._config['blebeacon']['Timeout']) * 60 * 60:
            if self._state['email_sent'] == 'False':
                if send_email(self._config['email'],
                              'env-logger: BLE beacon inactivity warning',
                              'No BLE beacon has been scanned in env-logger '
                              'after {} (timeout {} hours). Please check for '
                              'possible problems.'.format(last_obs_time_tz.isoformat(),
                                                          self._config['blebeacon']['Timeout'])):
                    self._state['email_sent'] = 'True'
        elif self._state['email_sent'] == 'True':
            send_email(self._config['email'],
                       'env-logger: BLE beacon scanned',
                       f'BLE beacon scanned was at {last_obs_time_tz.isoformat()}.')
            self._state['email_sent'] = 'False'

    def get_state(self):
        """Return the BLE beacon scan state."""
        return self._state


class RuuvitagMonitor:
    """Class for monitoring RuuviTag beacon observations."""

    def __init__(self, config, state):
        """Class constructor."""
        self._config = config
        self._state = state

    def get_ruuvitag_scan_time(self):
        """Return recording time of the latest RuuviTag beacon observation."""
        results = {}

        with psycopg.connect(create_db_conn_string(self._config['db'])) as conn:
            with conn.cursor() as cursor:
                for location in self._config['ruuvitag']['Location'].split(','):
                    cursor.execute("""SELECT recorded FROM ruuvitag_observations WHERE
                    location = %s ORDER BY recorded DESC LIMIT 1""", (location,))

                    result = cursor.fetchone()
                    results[location] = result[0] if result else datetime.now()

        return results

    def check_ruuvitag(self):
        """Check the latest RuuviTag beacon scan time.

        Sends an email if the threshold is exceeded.
        """
        last_obs_time = self.get_ruuvitag_scan_time()

        for location in self._config['ruuvitag']['Location'].split(','):
            time_diff = datetime.now(tz=last_obs_time[location].tzinfo) \
                - last_obs_time[location]
            last_obs_time_tz = last_obs_time[location].astimezone(
                ZoneInfo(self._config['db']['DisplayTimezone']))

            # Timeout is in minutes
            if int(time_diff.total_seconds()) > \
               int(self._config['ruuvitag']['Timeout']) * 60:
                if self._state[location]['email_sent'] == 'False':
                    if send_email(self._config['email'],
                                  f'env-logger: RuuviTag beacon "{location}" '
                                  'inactivity warning',
                                  'No RuuviTag observation for location "{}" has '
                                  'been scanned in env-logger after {} '
                                  '(timeout {} minutes). Please check for possible '
                                  'problems.'
                                  .format(location, last_obs_time_tz.isoformat(),
                                          self._config['ruuvitag']['Timeout'])):
                        self._state[location]['email_sent'] = 'True'
                    else:
                        self._state[location]['email_sent'] = 'False'
            elif self._state[location]['email_sent'] == 'True':
                send_email(self._config['email'],
                           f'env-logger: Ruuvitag beacon "{location}" scanned',
                           f'A RuuviTag observation for location "{location}" '
                           f'was scanned at {last_obs_time_tz.isoformat()}.')
                self._state[location]['email_sent'] = 'False'

    def get_state(self):
        """Return the RuuviTag scan state."""
        return self._state


def send_email(config, subject, message):
    """Send an email with provided subject and message to specified recipient(s)."""
    msg = MIMEText(message)
    msg['Subject'] = subject
    msg['From'] = config['Sender']
    msg['To'] = config['Recipient']

    email_password = environ['EMAIL_PASSWORD'] if 'EMAIL_PASSWORD' in environ \
        else config['Password']
    try:
        with smtplib.SMTP_SSL(config['Server'],
                              context=ssl.create_default_context()) as server:
            server.login(config['User'], email_password)
            server.send_message(msg)
    except smtplib.SMTPException as smtp_e:
        logging.error('Failed to send email with subject "%s": %s',
                      subject, str(smtp_e))
        return False

    return True


def create_db_conn_string(db_config):
    """Create the database connection string."""
    db_config = {
        'host': environ['DB_HOST'] if 'DB_HOST' in environ else db_config['Host'],
        'name': environ['DB_NAME'] if 'DB_NAME' in environ else db_config['Name'],
        'username': environ['DB_USERNAME'] if 'DB_USERNAME' in environ \
        else db_config['User'],
        'password': environ['DB_PASSWORD'] if 'DB_PASSWORD' in environ \
        else db_config['Password']
    }

    return f'host={db_config["host"]} user={db_config["username"]} ' \
        f'password={db_config["password"]} dbname={db_config["name"]}'


def main():
    """Run the module code."""
    logging.basicConfig(format='%(asctime)s:%(levelname)s:%(message)s',
                        level=logging.INFO)

    parser = argparse.ArgumentParser(description='Monitors observation reception.')
    parser.add_argument('--config', type=str, help='configuration file to use '
                        '(default: monitor.cfg)')

    args = parser.parse_args()
    config_file = args.config if args.config else 'monitor.cfg'

    if not exists(config_file):
        logging.error('Could not find configuration file "%s"', args.config_file)
        sys.exit(1)

    config = configparser.ConfigParser()
    config.read(config_file)

    state_file_dir = None
    if 'STATE_FILE_DIRECTORY' in environ:
        state_file_dir = environ['STATE_FILE_DIRECTORY']
        if not exists(state_file_dir) or not isdir(state_file_dir):
            logging.error('State file directory "%s" does not exist', state_file_dir)
            sys.exit(1)

    state_file_name = 'monitor_state.json' if not state_file_dir \
        else f'{state_file_dir}/monitor_state.json'

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
