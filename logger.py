#!/usr/bin/env python3

import requests
import json
from datetime import datetime
import pytz

URL = 'http://192.168.1.10/'
# Change this value when DB host changes
# OpenShift database URL
DB_ADD_URL = 'http://logger-tpalohei.rhcloud.com/add'
# Local database URL
# DB_ADD_URL = 'http://localhost:8080/add'

def main():
    """Module main function."""
    send_to_db(get_data())

def get_data():
    """Fetches the environment data from the Arduino. Returns the parsed
    JSON object or an empty dictionary on failure."""
    resp = requests.get(URL)
    if resp.status_code != 200:
        # Request failed
        return {}
    return resp.json()

def send_to_db(data):
    """Sends the data to the remote database with a HTTP GET reqeust."""
    if data == {}:
        print("Received no data, exiting")
        return

    data['timestamp'] = datetime.now(
                            pytz.timezone('Europe/Helsinki')).isoformat()
    payload = {'json-string': json.dumps(data)}
    r = requests.get(DB_ADD_URL, params=payload)

if __name__ == '__main__':
    main()
