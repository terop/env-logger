#!/usr/bin/env python3
"""A script for downloading the current FMI Testbed image and sending
it to a storage backend."""

import argparse
import base64
import sys

import requests
from bs4 import BeautifulSoup


def download_image():
    """Downloads the latest FMI Testbed image. Returns the image data on success
    and None otherwise."""
    try:
        resp = requests.get('http://testbed.fmi.fi')
    except requests.ConnectionError as ce:
        print('Failed to access Testbed page: {}'.format(ce), file=sys.stderr)
        return None

    if not resp.ok:
        return None

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
    except requests.ConnectionError as ce:
        print('Failed to download Testbed image: {}'.format(ce), file=sys.stderr)
        return None

    return resp.content


def main():
    """Module main function."""
    parser = argparse.ArgumentParser(description='FMI Testbed image downloading script.')
    parser.add_argument('backend_url', type=str, help='Backend URL')
    args = parser.parse_args()

    image = download_image()
    if image:
        resp = requests.post(args.backend_url, data={'image': base64.b64encode(image)})
        if not resp.ok:
            print('Failed to send Testbed image: HTTP status {}'.format(resp.status_code),
                  file=sys.stderr)
            exit(1)
        print('Image storage status: status code {}, response {}'
              .format(resp.status_code, resp.text))

main()
