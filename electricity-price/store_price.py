#!/usr/bin/env python3

"""This script inserts electricity prices into a PostgreSQL database."""

import argparse
import json
import logging
import sys
from datetime import date, datetime, timedelta
from math import isinf
from os import environ
from os.path import exists

from nordpool import elspot  # pylint: disable=import-error
import psycopg  # pylint: disable=import-error

VAT_MULTIPLIER = 1.24
VAT_MULTIPLIER_DECREASED = 1.10


def fetch_prices(config, fetch_date):
    """Fetches electricity spot prices from the Nord Pool API for the given price area
    for the next day by default or some given date."""
    area_code = config['area_code']

    today = datetime.now().date()
    start = datetime(today.year, today.month, today.day)

    if fetch_date:
        try:
            start = datetime.strptime(fetch_date, '%Y-%m-%d')
        except ValueError as err:
            logging.error('Invalid date provided: %s', err)
            sys.exit(1)
    else:
        start += timedelta(days=1)

    end = start + timedelta(hours=23)

    prices = []
    vat_decrease_end = date(2023, 5, 1)

    # Use temporarily decrease electricity VAT (10 %) until 1st of May 2023
    vat_multiplier = VAT_MULTIPLIER_DECREASED if today < vat_decrease_end else \
        VAT_MULTIPLIER

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
                       'price': round((value['value'] / 10) * vat_multiplier, 2)})

    return prices


def store_prices(db_config, price_data):
    """Stores the prices to a database pointed by the DB config."""
    insert_query = 'INSERT INTO electricity_price (start_time, price) VALUES (%s, %s)'

    try:
        with psycopg.connect(create_db_conn_string(db_config)) as conn:
            with conn.cursor() as cursor:
                for price in price_data:
                    cursor.execute(insert_query, (price['time'],
                                                  round(float(price['price']), 2)))
    except psycopg.Error as pge:
        logging.error('Price insert failed: %s', pge)
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

    parser = argparse.ArgumentParser(description='Stores electricity prices into a database.')
    parser.add_argument('--config', type=str, help='configuration file to use')
    parser.add_argument('--date', type=str, help='date (in YYYY-MM-DD format) for which '
                        'to fetch data')

    args = parser.parse_args()
    config_file = args.config if args.config else 'config.json'

    if not exists(config_file):
        logging.error('Could not find configuration file "%s"', config_file)
        sys.exit(1)

    with open(config_file, 'r', encoding='utf-8') as cfg_file:
        config = json.load(cfg_file)

    prices = fetch_prices(config['fetch'], args.date)

    if len(prices) < 20:
        logging.error('Price fetching failed, not enough data was received')
        sys.exit(1)

    store_prices(config['db'], prices)

    logging.info('Successfully stored electricity prices')


if __name__ == '__main__':
    main()
