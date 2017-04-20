#!/usr/bin/env python3
"""A script for downloading the current FMI Testbed image. Prints an empty string on failure
and a filename where the latest Testbed image is stored on success."""

# PIP dependencies: requests, beautifulsoup4, lxml, pytz

import sys
from datetime import datetime

import requests
from bs4 import BeautifulSoup
import pytz


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
    image = download_image()
    if not image:
        print('')
        return 1

    helsinki = pytz.timezone('Europe/Helsinki')
    filename = 'testbed-{}.png'.format(
        helsinki.localize(datetime.now()).strftime('%Y-%m-%dT%H:%M%z'))

    with open(filename, 'wb') as tb_image:
        tb_image.write(image)

    print(filename)
    return 0

main()
