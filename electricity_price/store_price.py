#!/usr/bin/env python3

"""This script inserts electricity prices into a PostgreSQL database."""

import argparse
import json
import logging
import sys
from datetime import date, datetime, timedelta
from os import environ
from os.path import exists

import pandas as pd  # pylint: disable=import-error
import psycopg  # pylint: disable=import-error
from entsoe import EntsoePandasClient  # pylint: disable=import-error

VAT_MULTIPLIER = 1.24
VAT_MULTIPLIER_DECREASED = 1.10


# pylint: disable=too-many-locals
def fetch_prices(config, current_date=False):
    """Fetches electricity spot prices from the ENTSO-E transparency platform
    for the given price are for the next day."""
    country_code = config['country_code']
    timezone = config['tz']

    today = datetime.now().date()
    start = datetime(today.year, today.month, today.day)
    if not current_date:
        start += timedelta(days=1)
    end = start + timedelta(hours=23)

    prices = []
    vat_decrease_end = date(2023, 5, 1)

    # Use temporarily decrease electricity VAT (10 %) until 1st of May 2023
    vat_multiplier = VAT_MULTIPLIER_DECREASED if today < vat_decrease_end else \
        VAT_MULTIPLIER

    client = EntsoePandasClient(api_key=config['entsoe_api_key'])

    logging.info('Fetching price data for country code %s for interval [%s, %s]',
                 country_code, str(start), str(end))

    data = client.query_day_ahead_prices(country_code,
                                         start=pd.Timestamp(start, tz=timezone),
                                         end=pd.Timestamp(end, tz=timezone))

    for i in range(data.size):
        prices.append({'time': data.index[i].to_pydatetime(),
                       # Price is without VAT so it is manually added
                       'price': round((data[i] / 10) * vat_multiplier, 2)})

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
    parser.add_argument('--current-date', action='store_true',
                        help='use current date as date during fetching')

    args = parser.parse_args()
    config_file = args.config if args.config else 'config.json'

    if not exists(config_file):
        logging.error('Could not find configuration file "%s"', args.config_file)
        sys.exit(1)

    with open(config_file, 'r', encoding='utf-8') as cfg_file:
        config = json.load(cfg_file)

    prices = fetch_prices(config['fetch'], args.current_date)

    if len(prices) < 20:
        logging.error('Price fetching failed, not enough data was received')
        sys.exit(1)

    store_prices(config['db'], prices)

    logging.info('Successfully stored electricity prices')


if __name__ == '__main__':
    main()
