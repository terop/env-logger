#!/usr/bin/env python3
"""A script for downloading the current FMI Testbed image and sending
it to a storage backend."""

# PIP dependencies: requests, beautifulsoup4, lxml

import argparse
import base64
import sys
from datetime import datetime

import requests
from bs4 import BeautifulSoup


def download_image():
    """Downloads the latest FMI Testbed image. Returns the image data on success
    and None otherwise."""
    timestamp = datetime.now().isoformat()
    try:
        resp = requests.get('http://testbed.fmi.fi')
    # pylint: disable=invalid-name
    except requests.ConnectionError as ce:
        print('{}: Failed to access Testbed page: {}'.format(timestamp, ce),
              file=sys.stderr)
        return None

    if not resp.ok:
        return None

    # pylint: disable=invalid-name
    bs = BeautifulSoup(resp.text, 'lxml')
    images = bs.find_all('img')

    for img in images:
        if img['src'].find('data/area/') > -1:
            radar_img = img
            break

    if not radar_img:
        return None

    try:
        resp = requests.get('http://testbed.fmi.fi/{}'.format(radar_img['src']))
    # pylint: disable=invalid-name
    except requests.ConnectionError as ce:
        print('{}: Failed to download Testbed image: {}'.format(timestamp, ce),
              file=sys.stderr)
        return None

    return resp.content


def main():
    """Module main function."""
    parser = argparse.ArgumentParser(description='FMI Testbed image downloading script.')
    parser.add_argument('backend_url', type=str, help='Backend URL')
    parser.add_argument('authentication_code', type=str, help='Request authentication code')
    args = parser.parse_args()

    timestamp = datetime.now().isoformat()
    image = download_image()
    if image:
        resp = requests.post(args.backend_url, data={'image': base64.b64encode(image),
                                                     'code': args.authentication_code})
        if not resp.ok:
            print('{}: Failed to send Testbed image: HTTP status code {} response {}'
                  .format(timestamp, resp.status_code, resp.text), file=sys.stderr)
            exit(1)
        print('{}: Image storage status: HTTP status code {}, response {}'
              .format(timestamp, resp.status_code, resp.text))

main()
