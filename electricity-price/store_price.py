#!/usr/bin/env python3

"""A script for inserting electricity prices into a PostgreSQL database."""

import argparse
import json
import logging
import sys
from datetime import datetime, timedelta
from math import isinf
from os import environ
from pathlib import Path

import psycopg
from nordpool import elspot

VAT_MULTIPLIER = 1.255


def fetch_prices(config, fetch_date):
    """Fetch electricity spot prices from the Nord Pool API for the given price area.

    By default the next day is used or desired date can be provided.
    """
    area_code = config['area_code']

    today = datetime.now().date()
    start = datetime(today.year, today.month, today.day)

    if fetch_date:
        try:
            start = datetime.strptime(fetch_date, '%Y-%m-%d')
        except ValueError:
            logging.exception('Invalid date provided')
            sys.exit(1)
    else:
        start += timedelta(days=1)

    end = start + timedelta(hours=23)

    prices = []

    logging.info('Fetching price data for area code %s for interval [%s, %s]',
                 area_code, str(start), str(end))

    prices_spot = elspot.Prices()
    price_data = prices_spot.hourly(areas=[area_code], end_date=end)

    if not price_data or area_code not in price_data['areas']:
        logging.error('Price data fetch failed')
        sys.exit(1)

    for value in price_data['areas'][area_code]['values']:
        if isinf(value['value']):
            continue

        prices.append({'time': value['start'],
                       # Price is without VAT so it is manually added
                       'price': round((value['value'] / 10) * VAT_MULTIPLIER, 2)})

    return prices


def store_prices(db_config, price_data):
    """Store the prices to a database pointed by the DB config."""
    insert_query = 'INSERT INTO electricity_price (start_time, price) VALUES (%s, %s)'

    try:
        with psycopg.connect(create_db_conn_string(db_config)) as conn, \
             conn.cursor() as cursor:
            for price in price_data:
                cursor.execute(insert_query, (price['time'],
                                              round(float(price['price']), 2)))
    except psycopg.Error:
        logging.exception('Price insert failed')
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

    parser = argparse.ArgumentParser(description='Fetch and store electricity price '
                                     'into a database. By default fetches prices for '
                                     'the next day.')
    parser.add_argument('--config', type=str, help='configuration file to use')
    parser.add_argument('--date', type=str, help='date (in YYYY-MM-DD format) for '
                        'which to fetch data')

    args = parser.parse_args()
    config_file = args.config if args.config else 'config.json'
    price_array_min_length = 20

    if not Path(config_file).exists():
        logging.error('Could not find configuration file "%s"', config_file)
        sys.exit(1)

    with Path(config_file).open('r', encoding='utf-8') as cfg_file:
        config = json.load(cfg_file)

    prices = fetch_prices(config['fetch'], args.date)

    if len(prices) < price_array_min_length:
        logging.error('Price fetching failed, not enough data was received')
        sys.exit(1)

    store_prices(config['db'], prices)

    logging.info('Successfully stored electricity prices')


if __name__ == '__main__':
    main()
