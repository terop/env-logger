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

logger = logging.getLogger(__name__)


def login_to_carunaplus(config):
    """Log in to the Caruna+ service."""
    logger.info('Logging in to Caruna+')

    password = config.get('password', None)
    if not password:
        password_file = environ.get('CARUNA_PASSWORD_FILE', None)
        if password_file:
            with Path.open(password_file, 'r') as pw_file:
                password = pw_file.readline().strip()
        else:
            logger.error('No Caruna password provided, exiting')
            sys.exit(1)

    authenticator = Authenticator(config['username'], password)
    try:
        login_result = authenticator.login()
    except Exception:
        logger.exception('Login failed')
        sys.exit(1)

    token = login_result['token']
    customer_id = login_result['user']['ownCustomerNumbers'][0]

    client = CarunaPlus(token)

    metering_points = client.get_assets(customer_id)
    if len(metering_points) == 0 or not metering_points[0]:
        logger.error('No metering points found')
        sys.exit(1)
    if 'assetId' not in metering_points[0]:
        logger.error('Asset ID not found in metering point')
        sys.exit(1)

    asset_id = metering_points[0]['assetId']

    return (client, customer_id, asset_id)


def fetch_consumption_data(login_data, manual_fetch_date=None):
    """Fetch electricity consumption data from the Caruna API.

    By default data for the previous day is fetched.
    """
    if not manual_fetch_date:
        fetch_date = date.today() - timedelta(days=1)
    else:
        try:
            fetch_dt = datetime.strptime(manual_fetch_date, '%Y-%m-%d')
        except ValueError:
            logger.exception('Invalid date provided')
            sys.exit(1)

        fetch_date = fetch_dt.date()

    logger.info('Fetching consumption data for %s', str(fetch_date))

    raw_consumption = login_data[0].get_energy(login_data[1], login_data[2],
                                               TimeSpan.DAILY, fetch_date.year,
                                               fetch_date.month, fetch_date.day)
    consumption = []
    for point in raw_consumption:
        if 'invoicedConsumption' in point:
            if point['invoicedConsumption'] is None:
                logger.error('Got a None value for consumption at %s, exiting',
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
        logger.exception('Data insert failed')
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
            logger.error('No database server password provided, exiting')
            sys.exit(1)

    return f'host={db_config["host"]} user={db_config["username"]} ' \
        f'password={db_config["password"]} dbname={db_config["name"]}'


def handle_storage(args, db_config, consumption_data):
    """Handle the consumption storage process."""
    required_data_array_length = 24

    if args.verbose:
        logger.info('Consumption data: length %s, array %s',
                    len(consumption_data), consumption_data)

    if not args.force_store and len(consumption_data) < required_data_array_length:
        logger.error('Data fetching failed, not enough data was received')
        logger.info('Received data array length: %s', len(consumption_data))
        sys.exit(1)

    if args.no_store:
        logger.info('Not storing data to database')
        return

    store_consumption(db_config, consumption_data)

    logger.info('Successfully stored electricity consumption data')


def main():
    """Run the module code."""
    logging.basicConfig(format='%(asctime)s:%(levelname)s:%(message)s',
                        level=logging.INFO)

    parser = argparse.ArgumentParser(description='Stores electricity consumption data '
                                     'into a PostgreSQL database. By default data for '
                                     'the previous day is fetched.')
    parser.add_argument('--config', type=str, help='configuration file to use')
    parser.add_argument('--date', type=str, help='date (in YYYY-MM-DD format) for '
                        'which to fetch data, multiple comma separated values are '
                        'supported')
    parser.add_argument('--force-store', action='store_true',
                        help='store data despite missing values')
    parser.add_argument('--no-store', action='store_true',
                        help='do not store consumption data to database')
    parser.add_argument('--verbose', action='store_true',
                        help='print returned consumption data')

    args = parser.parse_args()
    config_file = args.config if args.config else 'config.json'

    if not Path(config_file).exists():
        logger.error('Could not find configuration file "%s"', config_file)
        sys.exit(1)

    with Path(config_file).open('r', encoding='utf-8') as cfg_file:
        config = json.load(cfg_file)

    login_data = login_to_carunaplus(config['fetch'])

    if args.date:
        for date in args.date.split(','):
            handle_storage(args, config['db'],
                           fetch_consumption_data(login_data, date))
    else:
        handle_storage(args, config['db'],
                       fetch_consumption_data(login_data))


if __name__ == '__main__':
    main()
