#!/usr/bin/env python3
"""A script for downloading the current FMI Testbed image.

Prints an empty string on failure and a filename where the latest Testbed image
is stored on success.
"""

import sys
from datetime import datetime
from pathlib import Path

import pytz
import requests
from bs4 import BeautifulSoup


def download_image():
    """Download the latest FMI Testbed image.

    Returns the image data on success and None otherwise.
    """
    timestamp = datetime.now().isoformat()
    try:
        resp = requests.get('https://testbed.fmi.fi/?imgtype=radar&t=5&n=1', timeout=10)
    except requests.ConnectionError as ce:
        print(f'{timestamp}: Failed to access Testbed page: {ce}',
              file=sys.stderr)
        return None

    if not resp.ok:
        return None

    bs = BeautifulSoup(resp.text, 'lxml')
    images = bs.find_all(id='anim_image_anim_anim')

    if len(images) != 1:
        return None
    img_url = images[0]['src']

    if not img_url:
        return None

    try:
        resp = requests.get(img_url, timeout=10)
    except requests.ConnectionError as ce:
        print(f'{timestamp}: Failed to download Testbed image: {ce}',
              file=sys.stderr)
        return None

    return resp.content


def main():  # noqa: RET503
    """Run the module code."""
    image = download_image()
    if not image:
        print('')
        return 1

    helsinki_tz = pytz.timezone('Europe/Helsinki')
    filename_dt = helsinki_tz.localize(datetime.now()).strftime('%Y-%m-%dT%H:%M%z')
    filename = f'testbed-{filename_dt}.png'

    with Path(filename).open('wb') as tb_image:
        tb_image.write(image)

    print(filename)


main()
