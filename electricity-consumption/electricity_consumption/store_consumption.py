#!/usr/bin/env python3

"""A script for fetching electricity consumption data from the Caruna API.

The fetched data is stored into a PostgreSQL database.
"""

import argparse
import json
import logging
import sys
from datetime import date, datetime, timedelta
from os import environ
from pathlib import Path

import psycopg
from pycaruna import Authenticator, CarunaPlus, TimeSpan


def fetch_consumption_data(config, manual_fetch_date):
    """Fetch electricity consumption data from the Caruna API.

    By default data for the previous day is fetched.
    """
    if not manual_fetch_date:
        fetch_date = date.today() - timedelta(days=1)
    else:
        try:
            fetch_dt = datetime.strptime(manual_fetch_date, '%Y-%m-%d')
        except ValueError:
            logging.exception('Invalid date provided')
            sys.exit(1)

        fetch_date = fetch_dt.date()

    logging.info('Fetching consumption data for %s', str(fetch_date))

    authenticator = Authenticator(config['username'], config['password'])
    try:
        login_result = authenticator.login()
    except Exception:
        logging.exception('Login failed')
        sys.exit(1)

    token = login_result['token']
    customer_id = login_result['user']['ownCustomerNumbers'][0]

    client = CarunaPlus(token)

    metering_points = client.get_assets(customer_id)
    if len(metering_points) == 0 or not metering_points[0]:
        logging.error('No metering points found')
        sys.exit(1)
    if 'assetId' not in metering_points[0]:
        logging.error('Asset ID not found in metering point')
        sys.exit(1)

    asset_id = metering_points[0]['assetId']
    raw_consumption = client.get_energy(customer_id, asset_id, TimeSpan.DAILY,
                                        fetch_date.year, fetch_date.month,
                                        fetch_date.day)

    consumption = []
    for point in raw_consumption:
        if 'invoicedConsumption' in point:
            if point['invoicedConsumption'] is None:
                logging.error('Got a None value for consumption at %s, exiting',
                              point['timestamp'])
                sys.exit(1)

            consumption.append({'time': point['timestamp'],
                                'consumption': point['invoicedConsumption']})

    return consumption


def store_consumption(db_config, consumption_data):
    """Store consumption data to a database pointed by the DB config."""
    insert_query = 'INSERT INTO electricity_consumption (time, consumption) ' \
        'VALUES (%s, %s)'

    try:
        with psycopg.connect(create_db_conn_string(db_config)) as conn, \
             conn.cursor() as cursor:
            for point in consumption_data:
                cursor.execute(insert_query, (point['time'],
                                              point['consumption']))
    except psycopg.Error:
        logging.exception('Data insert failed')
        sys.exit(1)


def create_db_conn_string(db_config):
    """Create the database connection string."""
    db_config = {
        'host': environ['DB_HOST'] if 'DB_HOST' in environ else db_config['host'],
        'name': environ['DB_NAME'] if 'DB_NAME' in environ else db_config['dbname'],
        'username': environ['DB_USERNAME'] if 'DB_USERNAME' in environ
        else db_config['username'],
        'password': db_config.get('password', None)
    }
    if not db_config['password']:
        password_file = environ.get('DB_PASSWORD_FILE', None)
        if password_file:
            with Path.open(password_file, 'r') as pw_file:
                db_config['password'] = pw_file.readline().strip()
        else:
            logging.error('No database server password provided, exiting')
            sys.exit(1)

    return f'host={db_config["host"]} user={db_config["username"]} ' \
        f'password={db_config["password"]} dbname={db_config["name"]}'


def main():
    """Run the module code."""
    logging.basicConfig(format='%(asctime)s:%(levelname)s:%(message)s',
                        level=logging.INFO)

    parser = argparse.ArgumentParser(description='Stores electricity consumption data '
                                     'into a PostgreSQL database. By default data for '
                                     'the previous day is fetched.')
    parser.add_argument('--config', type=str, help='configuration file to use')
    parser.add_argument('--date', type=str, help='date (in YYYY-MM-DD format) for '
                        'which to fetch data')
    parser.add_argument('--force-store', action='store_true',
                        help='store data despite missing values')
    parser.add_argument('--no-store', action='store_true',
                        help='do not store consumption data to database')
    parser.add_argument('--verbose', action='store_true',
                        help='print returned consumption data')

    args = parser.parse_args()
    config_file = args.config if args.config else 'config.json'
    required_data_array_length = 24

    if not Path(config_file).exists():
        logging.error('Could not find configuration file "%s"', config_file)
        sys.exit(1)

    with Path(config_file).open('r', encoding='utf-8') as cfg_file:
        config = json.load(cfg_file)

    consumption_data = fetch_consumption_data(config['fetch'], args.date)

    if args.verbose:
        logging.info('Consumption data: length %s, array %s',
                     len(consumption_data), consumption_data)

    if not args.force_store and len(consumption_data) < required_data_array_length:
        logging.error('Data fetching failed, not enough data was received')
        logging.info('Received data array length: %s', len(consumption_data))
        sys.exit(1)

    if args.no_store:
        logging.info('Not storing data to database')
        sys.exit(0)

    store_consumption(config['db'], consumption_data)

    logging.info('Successfully stored electricity consumption data')


if __name__ == '__main__':
    main()
