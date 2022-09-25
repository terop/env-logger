#!/usr/bin/env python3

"""This script inserts electricity prices into a PostgreSQL database."""

import argparse
import json
import logging
import subprocess
import sys
from datetime import date, datetime, timedelta
from os import environ
from os.path import exists
from pathlib import Path
from zoneinfo import ZoneInfo

import psycopg  # pylint: disable=import-error


def store_prices(db_config, price_file):
    """Stores the prices to a database pointed by the DB config."""
    if not exists(price_file):
        logging.error('Cannot find price file "%s"', price_file)
        sys.exit(1)

    with open(price_file, 'r', encoding='utf-8') as price_f:
        try:
            prices = json.load(price_f)
        except json.JSONDecodeError:
            logging.error('Cannot decode price file "%s"', price_file)
            sys.exit(1)

    if len(prices) < 20:
        logging.error('Prices array does not contain enough data')
        sys.exit(1)

    insert_query = 'INSERT INTO electricity_price (start_time, price) VALUES (%s, %s)'
    midnight = datetime.combine(date.today(), datetime.min.time())

    try:
        with psycopg.connect(create_db_conn_string(db_config)) as conn:
            with conn.cursor() as cursor:
                for price in prices:
                    hour = int(price['hour'].split('-')[0])
                    timestamp = midnight + timedelta(hours=hour)
                    timestamp_utc = timestamp.astimezone(ZoneInfo(db_config['store_tz']))

                    cursor.execute(insert_query, (timestamp_utc,
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


def run_scraper(output_file):
    """Runs the Web scraper. Returns True on success and False otherwise."""
    scrapy_path = Path('.') / 'spot_price'

    try:
        res = subprocess.run(f'scrapy crawl spot_price -O {output_file}',
                             shell=True, check=True, cwd=scrapy_path)
        if res.returncode != 0:
            logging.error('scrapy call failed with return code %s', res.returncode)
            return False
    except subprocess.CalledProcessError as cpe:
        logging.error('scrapy call failed: %s', cpe)
        return False

    return True


def main():
    """Module main function."""
    logging.basicConfig(format='%(asctime)s:%(levelname)s:%(message)s', level=logging.INFO)

    parser = argparse.ArgumentParser(description='Stores electricity prices into a database.')
    parser.add_argument('--config', type=str, help='configuration file to use')

    args = parser.parse_args()
    config_file = args.config if args.config else 'config.json'

    if not exists(config_file):
        logging.error('Could not find configuration file "%s"', args.config_file)
        sys.exit(1)

    with open(config_file, 'r', encoding='utf-8') as cfg_file:
        config = json.load(cfg_file)

    price_json_file = 'prices.json'

    if not run_scraper(price_json_file):
        sys.exit(1)

    store_prices(config['db'], Path('.') / 'spot_price' / price_json_file)


if __name__ == '__main__':
    main()
