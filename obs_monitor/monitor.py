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
from pathlib import Path
from zoneinfo import ZoneInfo

import psycopg

smtp_connection = None


class ObservationMonitor:
    """Class for monitoring environment observations."""

    def __init__(self, config, state):
        """Class constructor."""
        self._config = config
        self._state = state

    def get_obs_time(self):
        """Return the recording time of the latest observation."""
        with psycopg.connect(create_db_conn_string(self._config['db'])) as conn, \
             conn.cursor() as cursor:
            cursor.execute('SELECT recorded FROM observations '
                           'ORDER BY id DESC LIMIT 1')
            result = cursor.fetchone()
            return result[0] if result else datetime.now()

    def check_observation(self):
        """Check when the last observation has been received.

        Sends an email if the threshold is exceeded.
        """
        logging.info('Starting observation inactivity check')

        last_obs_time = self.get_obs_time()
        last_obs_time_tz = last_obs_time.astimezone(
                    ZoneInfo(self._config['db']['DisplayTimezone']))
        time_diff = datetime.now(tz=last_obs_time.tzinfo) - last_obs_time

        if int(time_diff.total_seconds() / 60) > \
           int(self._config['observation']['Timeout']):
            logging.warning('No observations received since %s',
                            last_obs_time_tz.isoformat())
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
                       'An observation has been detected at '
                       f'{last_obs_time_tz.isoformat()}.')
            logging.info('An observation was detected at %s',
                         last_obs_time_tz.isoformat())
            self._state['email_sent'] = 'False'

    def get_state(self):
        """Return the observation state."""
        return self._state


class OutsideTemperatureMonitor:
    """Class for monitoring outside temperature values."""

    def __init__(self, config, state):
        """Class constructor."""
        self._config = config
        self._state = state

    def check_ot_rec_status(self):
        """Return the outside temperature value status.

        Returns False if a value is seen within the configured timeout. Otherwise True
        is returned with a timestamp value of the latest observed value.
        """
        end_threshold = 30
        timezone = ZoneInfo(self._config['db']['DisplayTimezone'])
        current_dt = datetime.now(tz=timezone)

        with psycopg.connect(create_db_conn_string(self._config['db'])) as conn, \
             conn.cursor() as cursor:
            cursor.execute('SELECT outside_temperature FROM observations '
                           'ORDER BY id DESC LIMIT 1')
            result = cursor.fetchone()
            if result[0] is None:
                cursor.execute('SELECT recorded FROM observations WHERE '
                               'outside_temperature IS NULL ORDER BY id DESC LIMIT 30')
                result = cursor.fetchall()
                for res in result:
                    recorded_tz = res[0].astimezone(timezone)
                    time_diff_min = (current_dt - recorded_tz).total_seconds() / 60
                    if time_diff_min > int(self._config['outsidetemp']['Timeout']) and \
                       time_diff_min < end_threshold:
                        logging.warning('No outside temperature value received '
                                        'since %s',
                                        recorded_tz.isoformat())
                        return (True, recorded_tz.isoformat())

            return (False,)

    def handle_status_data(self):
        """Check the outside temperature status data.

        Sends an email if the threshold is exceeded.
        """
        logging.info('Starting outside temperature value inactivity check')

        ot_status = self.check_ot_rec_status()

        if ot_status[0]:
            if self._state['email_sent'] == 'False':
                if send_email(self._config['email'],
                              'env-logger: outside temperature value inactivity '
                              'warning',
                              'No outside temperature values have been received '
                              'in the env-logger backend after {} (timeout {} '
                              'minutes). Please check for possible problems.'
                              .format(ot_status[1],
                                      self._config['outsidetemp']['Timeout'])):
                    self._state['email_sent'] = 'True'
                else:
                    self._state['email_sent'] = 'False'
        elif self._state['email_sent'] == 'True':
            dt_str = datetime.now(
                tz=ZoneInfo(self._config['db']['DisplayTimezone'])).isoformat()
            send_email(self._config['email'],
                       'env-logger: outside temperature value received',
                       f'An outside temperature value has been detected at {dt_str}.')
            logging.info('An outside temperature value was detected at %s', dt_str)
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
        with psycopg.connect(create_db_conn_string(self._config['db'])) as conn, \
             conn.cursor() as cursor:
            cursor.execute('SELECT recorded FROM observations WHERE id = '
                           '(SELECT obs_id FROM beacons ORDER BY id DESC LIMIT 1)')
            result = cursor.fetchone()
            return result[0] if result else datetime.now()

    def check_beacon(self):
        """Check the latest BLE beacon scan time.

        Sends an email if the threshold is exceeded.
        """
        logging.info('Starting BLE beacon inactivity check')

        last_obs_time = self.get_beacon_scan_time()
        last_obs_time_tz = last_obs_time.astimezone(ZoneInfo(
            self._config['db']['DisplayTimezone']))
        time_diff = datetime.now(tz=last_obs_time.tzinfo) - last_obs_time

        # Timeout is in hours
        if int(time_diff.total_seconds()) > \
           int(self._config['blebeacon']['Timeout']) * 60 * 60:
            logging.warning('No BLE beacon has been scanned after %s',
                            last_obs_time_tz.isoformat())
            if self._state['email_sent'] == 'False' and \
               send_email(self._config['email'],
                          'env-logger: BLE beacon inactivity warning',
                          'No BLE beacon has been scanned in env-logger '
                          'after {} (timeout {} hours). Please check for '
                          'possible problems.'.format(last_obs_time_tz.isoformat(),
                                                      self._config['blebeacon']['Timeout'])):
                self._state['email_sent'] = 'True'
        elif self._state['email_sent'] == 'True':
            send_email(self._config['email'],
                       'env-logger: BLE beacon scanned',
                       f'BLE beacon was detected at {last_obs_time_tz.isoformat()}.')
            logging.info('BLE beacon was detected at %s',
                         last_obs_time_tz.isoformat())
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

        with psycopg.connect(create_db_conn_string(self._config['db'])) as conn, \
             conn.cursor() as cursor:
            for name in self._config['ruuvitag']['Name'].split(','):
                cursor.execute("""SELECT recorded FROM ruuvitag_observations WHERE
                name = %s ORDER BY recorded DESC LIMIT 1""", (name,))

                result = cursor.fetchone()
                results[name] = result[0] if result else datetime.now()

        return results

    def check_ruuvitag(self):
        """Check the latest RuuviTag beacon scan time.

        Sends an email if the threshold is exceeded.
        """
        logging.info('Starting RuuviTag observation inactivity check')

        last_obs_time = self.get_ruuvitag_scan_time()

        for name in self._config['ruuvitag']['Name'].split(','):
            time_diff = datetime.now(tz=last_obs_time[name].tzinfo) \
                - last_obs_time[name]
            last_obs_time_tz = last_obs_time[name].astimezone(
                ZoneInfo(self._config['db']['DisplayTimezone']))

            # Timeout is in minutes
            if int(time_diff.total_seconds()) > \
               int(self._config['ruuvitag']['Timeout']) * 60:
                if self._state[name]['email_sent'] == 'False':
                    logging.warning('No RuuviTag observation for name "%s" has '
                                    'been scanned after %s',
                                    name, last_obs_time_tz.isoformat())
                    if send_email(self._config['email'],
                                  f'env-logger: RuuviTag beacon "{name}" '
                                  'inactivity warning',
                                  'No RuuviTag observation for name "{}" has '
                                  'been scanned in env-logger after {} '
                                  '(timeout {} minutes). Please check for possible '
                                  'problems.'
                                  .format(name, last_obs_time_tz.isoformat(),
                                          self._config['ruuvitag']['Timeout'])):
                        self._state[name]['email_sent'] = 'True'
                    else:
                        self._state[name]['email_sent'] = 'False'
            elif self._state[name]['email_sent'] == 'True':
                send_email(self._config['email'],
                           f'env-logger: Ruuvitag beacon "{name}" scanned',
                           f'A RuuviTag observation for name "{name}" '
                           f'was detected at {last_obs_time_tz.isoformat()}.')
                logging.info('A RuuviTag observation for name "%s" was '
                             'detected at %s',
                             name, last_obs_time_tz.isoformat())
                self._state[name]['email_sent'] = 'False'

    def get_state(self):
        """Return the RuuviTag scan state."""
        return self._state


def create_smtp_connection(config):
    """Create a SMTP SSL connection which can be used to send email."""
    email_username = environ['EMAIL_USERNAME'] if 'EMAIL_USERNAME' in environ \
        else config['Username']
    email_password = environ['EMAIL_PASSWORD'] if 'EMAIL_PASSWORD' in environ \
        else config['Password']

    try:
        instance = smtplib.SMTP_SSL(config['Server'],
                                    context=ssl.create_default_context())
        instance.login(email_username, email_password)
    except smtplib.SMTPException:
        logging.exception('Cannot open SMTP connection')
        return None

    return instance


def send_email(config, subject, message):
    """Send an email with provided subject and message to specified recipient(s)."""
    msg = MIMEText(message)
    msg['Subject'] = subject
    msg['From'] = config['Sender']
    msg['To'] = config['Recipient']

    global smtp_connection  # noqa: PLW0603

    if not smtp_connection:
        smtp_connection = create_smtp_connection(config)
        if not smtp_connection:
            logging.error('Could not open SMTP connection, aborting message sending')
            return False

    try:
        smtp_connection.send_message(msg)
    except smtplib.SMTPException:
        logging.exception('Failed to send email with subject "%s"',
                          subject)
        return False

    return True


def create_db_conn_string(db_config):
    """Create the database connection string."""
    db_config = {
        'host': environ['DB_HOST'] if 'DB_HOST' in environ else db_config['Host'],
        'name': environ['DB_NAME'] if 'DB_NAME' in environ else db_config['Name'],
        'username': environ['DB_USERNAME'] if 'DB_USERNAME' in environ
        else db_config['User'],
        'password': environ['DB_PASSWORD'] if 'DB_PASSWORD' in environ
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

    if not Path(config_file).exists():
        logging.error('Could not find configuration file "%s"', args.config_file)
        sys.exit(1)

    config = configparser.ConfigParser()
    config.read(config_file)

    state_file_dir = None
    if 'STATE_FILE_DIRECTORY' in environ:
        state_file_dir = environ['STATE_FILE_DIRECTORY']
        if not Path(state_file_dir).exists() or not Path(state_file_dir).is_dir():
            logging.error('State file directory "%s" does not exist', state_file_dir)
            sys.exit(1)

    state_file_name = 'monitor_state.json' if not state_file_dir \
        else f'{state_file_dir}/monitor_state.json'

    try:
        with Path(state_file_name).open('r', encoding='utf-8') as state_file:
            state = json.load(state_file)
    except FileNotFoundError:
        state = {'observation': {'email_sent': 'False'},
                 'outsidetemp': {'email_sent': 'False'},
                 'blebeacon': {'email_sent': 'False'},
                 'ruuvitag': {}}
        for name in config['ruuvitag']['Name'].split(','):
            state['ruuvitag'][name] = {}
            state['ruuvitag'][name]['email_sent'] = 'False'

    if config['observation']['Enabled'] == 'True':
        obs = ObservationMonitor(config, state['observation'])
        obs.check_observation()
        state['observation'] = obs.get_state()
    if config['outsidetemp']['Enabled'] == 'True':
        otm = OutsideTemperatureMonitor(config, state['outsidetemp'])
        otm.handle_status_data()
        state['outsidetemp'] = otm.get_state()
    if config['blebeacon']['Enabled'] == 'True':
        beacon = BeaconMonitor(config, state['blebeacon'])
        beacon.check_beacon()
        state['blebeacon'] = beacon.get_state()
    if config['ruuvitag']['Enabled'] == 'True':
        ruuvitag = RuuvitagMonitor(config, state['ruuvitag'])
        ruuvitag.check_ruuvitag()
        state['ruuvitag'] = ruuvitag.get_state()

    with Path(state_file_name).open('w', encoding='utf-8') as state_file:
        json.dump(state, state_file, indent=4)

    if smtp_connection:
        smtp_connection.quit()

if __name__ == '__main__':
    main()
