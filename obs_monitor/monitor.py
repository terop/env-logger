#!/usr/bin/env python3
"""This is a small program for monitoring observations and alerting a maintainer
if observations are not received within a specified time."""

import argparse
import configparser
import smtplib
import ssl
import sys
from datetime import datetime
from email.mime.text import MIMEText
from os.path import exists

import psycopg2


def get_latest_obs_time(config):
    """Returns the recording time latest observation."""
    with psycopg2.connect(host=config['Host'], database=config['Database'],
                          user=config['User'], password=config['Password']) as conn:
        with conn.cursor() as cursor:
            cursor.execute('SELECT recorded FROM observations ORDER BY id DESC LIMIT 1')
            return cursor.fetchone()[0]


def send_email(config, subject, message):
    """Sends an email with provided subject and message to specified recipients."""
    msg = MIMEText(message)
    msg['Subject'] = subject
    msg['From'] = config['Sender']
    msg['To'] = config['Recipient']

    context = ssl.create_default_context()
    try:
        server = smtplib.SMTP_SSL(config['Server'],
                                  port=465, context=context)
        server.login(config['User'], config['Password'])
        server.send_message(msg)
        server.quit()
    except smtplib.SMTPException as smtp_e:
        print('Failed to send email with subject "{}": {}'
              .format(subject, str(smtp_e)), file=sys.stderr)
        return False

    return True


def check_obs_time(config, last_obs_time):
    """Check last observation time and send an email if the threshold is
    exceeded. Returns 'True' when an email is sent and 'False' otherwise."""
    time_diff = datetime.now(tz=last_obs_time.tzinfo) - last_obs_time
    diff_minutes = int(time_diff.seconds / 60)
    if diff_minutes > int(config['monitor']['Threshold']):
        if config['monitor']['EmailSent'] == 'False':
            if send_email(config['email'],
                          'env-logger inactivity warning',
                          'No observations have been received in env-logger backend for '
                          '{} minutes. Consider checking possible problems.'
                          .format(diff_minutes)):
                return 'True'
            else:
                return 'False'
        else:
            return 'True'
    else:
        if config['monitor']['EmailSent'] == 'True':
            send_email(config['email'], 'env-logger back online',
                       'env-logger is back online at {}.'
                       .format(datetime.now().isoformat()))
        return 'False'


def main():
    """Module main function."""
    parser = argparse.ArgumentParser(description='Monitors observation reception.')
    parser.add_argument('config_file', type=str, help='configuration file to use')

    args = parser.parse_args()
    if not exists(args.config_file):
        print('Error: could not find configuration file {}'.format(args.config_file),
              file=sys.stderr)
        exit(1)

    config = configparser.ConfigParser()
    config.read(args.config_file)

    email_sent = check_obs_time(config, get_latest_obs_time(config['db']))
    config['monitor']['EmailSent'] = email_sent
    with open(args.config_file, 'w') as config_file:
        config.write(config_file)


if __name__ == '__main__':
    main()
