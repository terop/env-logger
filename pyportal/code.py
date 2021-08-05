import time

import adafruit_esp32spi.adafruit_esp32spi_socket as socket
import adafruit_requests as requests
import board
import busio
from adafruit_bitmap_font import bitmap_font
from adafruit_esp32spi import adafruit_esp32spi
from adafruit_simple_text_display import SimpleTextDisplay
from digitalio import DigitalInOut
from secrets import secrets

# URL for the backend
BACKEND_URL = secrets['backend-url']
# Path to the bitmap font to use
FONT = bitmap_font.load_font("fonts/DejaVuSansMono-17.pcf")
# Sleep time in seconds between data refreshes
SLEEP_TIME = 60


def connect_to_wlan():
    """Connect to WLAN."""
    esp32_cs = DigitalInOut(board.ESP_CS)
    esp32_ready = DigitalInOut(board.ESP_BUSY)
    esp32_reset = DigitalInOut(board.ESP_RESET)

    spi = busio.SPI(board.SCK, board.MOSI, board.MISO)
    esp = adafruit_esp32spi.ESP_SPIcontrol(spi, esp32_cs, esp32_ready, esp32_reset)

    requests.set_socket(socket, esp)

    if esp.status == adafruit_esp32spi.WL_IDLE_STATUS:
        print('ESP32 found and in idle mode')
        print('Firmware version:', esp.firmware_version)
        print('MAC addr:', [hex(i) for i in esp.MAC_address])

    print('Connecting to AP')
    while not esp.is_connected:
        try:
            esp.connect_AP(secrets['ssid'], secrets['password'])
        except RuntimeError as re:
            print('Could not connect to AP, retrying:', re)
            continue
        print('Connected to', str(esp.ssid, 'utf-8'), '\tRSSI:', esp.rssi)
        print('My IP address is', esp.pretty_ip(esp.ip_address))


def fetch_token():
    """Fetch token for getting data from env-logger backend."""
    resp = requests.post(f'{BACKEND_URL}/token-login',
                         data={'username': secrets['data-read-user']['username'],
                               'password': secrets['data-read-user']['password']})
    if resp.status_code != 200:
        print('Error: token acquisition failed')
        return ''

    return resp.text


def clear_display(display):
    """Clears, i.e. removes all rows, from the given display."""
    max_row = 15

    for i in range(max_row):
        display[i].text = ''


def main():
    """Module main function."""
    connect_to_wlan()

    display = SimpleTextDisplay(title=' ', colors=[SimpleTextDisplay.WHITE], font=FONT)
    token = None

    while True:
        if not token:
            token = fetch_token()
            if not token:
                continue

        resp = requests.get(f'{BACKEND_URL}/get-last-obs',
                            headers={'Authorization': f'Token {token}'})
        if resp.status_code != 200:
            if resp.status_code == 401:
                print('Error: request was unauthorized, getting new token')
                token = fetch_token()
            else:
                print('Error: failed to get latest observation')
            continue

        data = resp.json()

        w_recorded = data['data']['recorded']

        if data['rt-data']:
            rt_recorded = max([tag['recorded'] for tag in data['rt-data']])
        else:
            rt_recorded = w_recorded

        clear_display(display)

        display[0].text = w_recorded
        display[1].text = f'Weather: temperature {data["data"]["fmi_temperature"]}, ' \
            f'cloudiness {data["data"]["cloudiness"]}'
        display[2].text = ''

        display[3].text = rt_recorded
        display[4].text = f'Inside temperature {data["data"]["temperature"]}, '
        display[5].text = f'Outside temperature {data["data"]["o_temperature"]}, ' \
            f'brightness {data["data"]["brightness"]}'
        if data['data']['rssi']:
            display[6].text = f'Beacon "{data["data"]["name"]}" RSSI {data["data"]["rssi"]}'
            row = 7
        else:
            row = 6

        if data['rt-data']:
            rt_data = data['rt-data']

            display[row].text = ''
            row += 1
            for tag in rt_data:
                display[row].text = f'RuuviTag \"{tag["location"]}\": temperature ' \
                    f'{tag["temperature"]},'
                display[row + 1].text = f'humidity {tag["humidity"]}'
                row += 2

        display.show()

        time.sleep(SLEEP_TIME)


main()
