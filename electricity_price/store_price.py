#!/usr/bin/env python3

"""This script inserts electricity prices into a PostgreSQL database."""

import argparse
import json
import logging
import sys
from datetime import date, datetime, timedelta, time
from os import environ
from os.path import exists
from zoneinfo import ZoneInfo
import xml.etree.ElementTree as ET

import psycopg  # pylint: disable=import-error
import requests

VAT_MULTIPLIER = 1.24
VAT_MULTIPLIER_DECREASED = 1.10


# pylint: disable=too-many-locals
def fetch_prices(config):
    """Fetches electricity spot prices from the ENTSO-E transparency platform
    for the given price are for the next day."""
    api_token = config['entsoe_api_token']
    price_area = config['price_area']

    today = datetime.utcnow().date()
    start = datetime(today.year, today.month, today.day) + timedelta(days=1)
    end = start + timedelta(hours=23)
    interval = f"{start.isoformat()}Z/{end.isoformat()}Z"

    prices = []
    vat_decrease_end = date(2023, 5, 1)

    # Use temporarily decrease electricity VAT (10 %) until 1st of May 2023
    vat_multiplier = VAT_MULTIPLIER_DECREASED if today < vat_decrease_end else \
        VAT_MULTIPLIER

    url = f"https://transparency.entsoe.eu/api?documentType=A44&in_Domain={price_area}" \
        f"&out_Domain={price_area}&TimeInterval={interval}&securityToken={api_token}"

    logging.info('Fetching price data for area %s over interval %s', price_area, interval)

    resp = requests.get(url, timeout=10)
    if not resp.ok:
        logging.error("Electricity price fetch failed with status code %s", resp.status_code)
        sys.exit(1)

    # Remove XML namespace data from response to make parsing easier
    root = ET.fromstring(
        resp.content.decode('utf-8').
        replace(' xmlns="urn:iec62325.351:tc57wg16:451-3:publicationdocument:7:0"', ''))
    for child in root.findall('./TimeSeries/Period/Point'):
        hour = int(child[0].text)
        if hour < 24:
            p_time = datetime.combine(start, time(hour=hour))
        else:
            p_time = datetime.combine(start + timedelta(days=1), time(hour=hour - 24))

        prices.append({'time': p_time,
                       # Price is without VAT so it is manually added
                       'price': round((float(child[1].text) / 10) * vat_multiplier, 2)})

    return prices


def store_prices(db_config, price_data):
    """Stores the prices to a database pointed by the DB config."""
    if len(price_data) < 20:
        logging.error('Prices array does not contain enough data')
        sys.exit(1)

    insert_query = 'INSERT INTO electricity_price (start_time, price) VALUES (%s, %s)'
    offset = ZoneInfo(db_config['source_tz']).utcoffset(datetime.now())
    fix_date = False

    if offset.total_seconds() > 0 and \
       datetime.now().astimezone(ZoneInfo(db_config['source_tz'])).day != datetime.now().day:
        fix_date = True

    try:
        with psycopg.connect(create_db_conn_string(db_config)) as conn:
            with conn.cursor() as cursor:
                for price in price_data:
                    timestamp = price['time'] - offset
                    if fix_date:
                        # Fix situation where the timestamp date is incorrect due to timezone
                        # differences between the input timezone and timezone of the
                        # system running this script
                        timestamp += timedelta(days=1)

                    cursor.execute(insert_query, (timestamp,
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

    args = parser.parse_args()
    config_file = args.config if args.config else 'config.json'

    if not exists(config_file):
        logging.error('Could not find configuration file "%s"', args.config_file)
        sys.exit(1)

    with open(config_file, 'r', encoding='utf-8') as cfg_file:
        config = json.load(cfg_file)

    store_prices(config['db'], fetch_prices(config['fetch']))

    logging.info('Successfully stored new electricity prices')


if __name__ == '__main__':
    main()
