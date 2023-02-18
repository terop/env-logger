#!/usr/bin/env python3

"""This script fetches electricity consumption data from Caruna's API and stores
it into a PostgreSQL database."""

import argparse
import json
import logging
import sys
from datetime import date, timedelta
from os import environ
from os.path import exists

import psycopg  # pylint: disable=import-error
from pycaruna import Authenticator, CarunaPlus, TimeSpan  # pylint: disable=import-error


def fetch_consumption_data(config):
    """Fetches electricity consumption data from the Caruna API. By default data
    for the previous day is fetched."""
    fetch_date = date.today() - timedelta(days=1)

    logging.info('Fetching consumption data for %s', str(fetch_date))

    authenticator = Authenticator(config['username'], config['password'])
    try:
        login_result = authenticator.login()
    except Exception as ex:  # pylint: disable=broad-exception-caught
        logging.error('Login failed: %s', str(ex))
        sys.exit(1)

    token = login_result['token']
    customer_id = login_result['user']['ownCustomerNumbers'][0]

    client = CarunaPlus(token)

    metering_points = client.get_assets(customer_id)
    if len(metering_points) == 0:
        logging.error('No metering points found')
        sys.exit(1)

    asset_id = metering_points[0]['assetId']
    raw_consumption = client.get_energy(customer_id, asset_id, TimeSpan.DAILY,
                                        fetch_date.year, fetch_date.month, fetch_date.day)

    consumption = []
    for point in raw_consumption:
        if 'invoicedConsumption' in point:
            consumption.append({'time': point['timestamp'],
                                'consumption': point['invoicedConsumption']})

    return consumption


def store_consumption(db_config, consumption_data):
    """Stores consumption data to a database pointed by the DB config."""
    insert_query = 'INSERT INTO electricity_usage (time, usage) VALUES (%s, %s)'

    try:
        with psycopg.connect(create_db_conn_string(db_config)) as conn:
            with conn.cursor() as cursor:
                for point in consumption_data:
                    cursor.execute(insert_query, (point['time'],
                                                  point['consumption']))
    except psycopg.Error as pge:
        logging.error('Data insert failed: %s', pge)
        sys.exit(1)


def create_db_conn_string(db_config):
    """Creates the database connection string."""
    db_config = {
        'host': environ['DB_HOST'] if 'DB_HOST' in environ else db_config['host'],
        'name': environ['DB_NAME'] if 'DB_NAME' in environ else db_config['dbname'],
        'username': environ['DB_USERNAME'] if 'DB_USERNAME' in environ else db_config['username'],
        'password': environ['DB_PASSWORD'] if 'DB_PASSWORD' in environ else db_config['password']
    }

    return f'host={db_config["host"]} user={db_config["username"]} ' \
        f'password={db_config["password"]} dbname={db_config["name"]}'


def main():
    """Module main function."""
    logging.basicConfig(format='%(asctime)s:%(levelname)s:%(message)s', level=logging.INFO)

    parser = argparse.ArgumentParser(description='Stores electricity consumption data '
                                     'into a database.')
    parser.add_argument('--config', type=str, help='configuration file to use')

    args = parser.parse_args()
    config_file = args.config if args.config else 'config.json'

    if not exists(config_file):
        logging.error('Could not find configuration file "%s"', config_file)
        sys.exit(1)

    with open(config_file, 'r', encoding='utf-8') as cfg_file:
        config = json.load(cfg_file)

    consumption_data = fetch_consumption_data(config['fetch'])

    if len(consumption_data) < 20:
        logging.error('Data fetching failed, not enough data was received')
        sys.exit(1)

    store_consumption(config['db'], consumption_data)

    logging.info('Successfully stored electricity consumption data')


if __name__ == '__main__':
    main()
