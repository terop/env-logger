"""Code for showing various data on a PyPortal Titano display."""

import time
from secrets import secrets

# pylint: disable=import-error
import adafruit_esp32spi.adafruit_esp32spi_socket as socket
import adafruit_requests as requests
import board
import busio
import rtc
import supervisor
from adafruit_bitmap_font import bitmap_font
from adafruit_esp32spi import adafruit_esp32spi
from adafruit_simple_text_display import SimpleTextDisplay
from digitalio import DigitalInOut

# URL for the backend
BACKEND_URL = secrets['backend-url']
# Path to the bitmap font to use, must include the degree symbol (U+00B0)
FONT = bitmap_font.load_font("fonts/DejaVuSansMono-17.pcf")
# Sleep time (in seconds) between data refreshes
SLEEP_TIME = 80

# Default backlight value
BACKLIGHT_DEFAULT_VALUE = 0.7
# Enable or disable backlight dimming
BACKLIGHT_DIMMING_ENABLED = True
# Start time (hour) of backlight dimming
BACKLIGHT_DIMMING_START = 20
# End time (hour) of backlight dimming
BACKLIGHT_DIMMING_END = 7
# Backlight value during dimming
BACKLIGHT_DIMMING_VALUE = 0.5
# Network failure threshold after which the board is rebooted
NW_FAILURE_THRESHOLD = 5


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
        except RuntimeError as rte:
            print(f'Could not connect to AP, retrying: {rte}')
            continue
        print('Connected to', str(esp.ssid, 'utf-8'), '\tRSSI:', esp.rssi)
        print('My IP address is', esp.pretty_ip(esp.ip_address))


def fetch_token():
    """Fetch token for getting data from env-logger backend."""
    fail_count = 0

    while True:
        try:
            resp = requests.post(f'{BACKEND_URL}/token-login',
                                 data={'username': secrets['data-read-user']['username'],
                                       'password': secrets['data-read-user']['password']})
            if resp.status_code != 200:
                print('Error: token acquisition failed')
                return ''

            break
        except RuntimeError as rte:
            fail_count += 1
            print(f'Error: token fetch failed: "{rte}", failure count {fail_count}')
            time.sleep(5)

            if fail_count > NW_FAILURE_THRESHOLD:
                print(f'Error: token fetch failed {fail_count} times, reloading board')
                time.sleep(5)
                supervisor.reload()

    return resp.text


def clear_display(display):
    """Clears, i.e. removes all rows, from the given display."""
    max_row = 20

    for i in range(max_row):
        display[i].text = ''


def get_backend_endpoint_content(endpoint, token):
    """Fetches the JSON content of the given backend endpoint.
    Returns a (token, JSON value) tuple."""
    fail_count = 0

    while fail_count < NW_FAILURE_THRESHOLD:
        try:
            resp = requests.get(f'{BACKEND_URL}/{endpoint}',
                                headers={'Authorization': f'Token {token}'})
            if resp.status_code != 200:
                if resp.status_code == 401:
                    print('Error: request was unauthorized, getting new token')
                    token = fetch_token()
                else:
                    print('Error: failed to fetch content from "{endpoint}"')
                continue
            break
        except RuntimeError as rte:
            fail_count += 1
            print(f'Error: got exception "{rte}", failure count {fail_count}')
            time.sleep(5)

            if fail_count >= NW_FAILURE_THRESHOLD:
                print(f'Error: endpoint "{endpoint}" fetch failed {fail_count} times, ',
                      'reloading board')
                time.sleep(5)
                supervisor.reload()

    return (token, resp.json())


def set_time(timezone):
    """Get and set local time for the board. Returns the offset to UTC in hours."""
    while True:
        try:
            with requests.get('http://worldtimeapi.org/api/timezone/' + timezone) as resp:
                time_info = resp.json()
                rtc.RTC().datetime = time.localtime(time_info['unixtime'] + time_info['raw_offset']
                                                    + time_info['dst_offset'])
                utc_offset_hour = int((time_info['raw_offset'] + time_info['dst_offset']) / 3600)
                return utc_offset_hour
        except RuntimeError as ex:
            print(f'Error: an exception occurred in set_time: {ex}')
            time.sleep(5)


def adjust_backlight(display):
    """Adjust backlight value based on the current time."""
    current_time = time.localtime()
    if current_time.tm_hour >= BACKLIGHT_DIMMING_START or \
       current_time.tm_hour < BACKLIGHT_DIMMING_END:
        display.brightness = BACKLIGHT_DIMMING_VALUE
    else:
        display.brightness = BACKLIGHT_DEFAULT_VALUE


# pylint: disable=too-many-locals
def update_screen(display, observation, weather_data, utc_offset_hour):
    """Update screen contents."""
    w_recorded = observation['data']['recorded']

    if observation['rt-data']:
        rt_recorded = max([tag['recorded'] for tag in observation['rt-data']])
    else:
        rt_recorded = w_recorded

    clear_display(display)

    display[0].text = w_recorded
    if weather_data:
        sunrise = time.localtime(weather_data['owm']['current']['sunrise'])
        sunrise = f'{sunrise.tm_hour + utc_offset_hour:02}:{sunrise.tm_min:02}'
        sunset = time.localtime(weather_data['owm']['current']['sunset'])
        sunset = f'{sunset.tm_hour + utc_offset_hour:02}:{sunset.tm_min:02}'
        display[0].text += f'           sr {sunrise} ss {sunset}'
    display[1].text = f'Weather: temperature {observation["data"]["fmi-temperature"]} \u00b0C, ' + \
        f'cloudiness {observation["data"]["cloudiness"]},'
    if weather_data:
        current = weather_data['owm']['current']
        forecast = weather_data['fmi']['forecast']
        forecast_dt = time.localtime(weather_data['owm']['forecast']['dt'])

        if current:
            display[2].text = 'wind ' + \
                f'{weather_data["fmi"]["current"]["wind-direction"]["short"]} ' + \
                f'{observation["data"]["wind-speed"]} m/s, desc ' + \
                f'\"{current["weather"][0]["description"]}\"'
        if forecast:
            display[3].text = 'Forecast'
            if forecast_dt and forecast_dt.tm_hour is not None \
               and forecast_dt.tm_min is not None:
                display[3].text += f' ({forecast_dt.tm_hour + utc_offset_hour:02}:' + \
                    f'{forecast_dt.tm_min:02})'
            display[3].text += f': temp {forecast["temperature"]} \u00b0C, ' + \
                f'clouds {forecast["cloudiness"]} %,'
            display[4].text = f'wind {forecast["wind-direction"]["short"]} ' + \
                f'{forecast["wind-speed"]} m/s, ' + \
                f'desc \"{weather_data["owm"]["forecast"]["weather"][0]["description"]}\"'
        row = 5
    else:
        row = 2

    display[row].text = ''
    row += 1

    display[row].text = rt_recorded
    row += 1
    display[row].text = f'Brightness {observation["data"]["brightness"]}, '
    display[row].text += f'outside temperature {observation["data"]["o-temperature"]} \u00b0C'
    row += 1
    if observation['data']['rssi']:
        display[row].text = f'Beacon "{observation["data"]["name"]}" ' + \
            f'RSSI {observation["data"]["rssi"]}'
        row += 1

    if observation['rt-data']:
        rt_data = observation['rt-data']
        seen_locations = []

        for tag in rt_data:
            location = tag['location']

            if (location in seen_locations) or \
               (location in secrets['hidden_ruuvitag_locations']):
                continue

            display[row].text = f'RuuviTag \"{location}\": temperature ' + \
                f'{tag["temperature"]} \u00b0C,'
            display[row + 1].text = f'humidity {tag["humidity"]} %H'
            row += 2
            seen_locations.append(location)

    display.show()


def main():
    """Module main function."""
    connect_to_wlan()

    print('Getting current time from worldtimeapi.org')
    utc_offset_hour = set_time(secrets['timezone'])
    print('Set current time')

    display = SimpleTextDisplay(title=' ', colors=[SimpleTextDisplay.WHITE], font=FONT)
    token = None
    weather_data = None

    # Dim the backlight because the default backlight is very bright
    board.DISPLAY.auto_brightness = False
    board.DISPLAY.brightness = BACKLIGHT_DEFAULT_VALUE

    while True:
        if not token:
            token = fetch_token()
            if not token:
                time.sleep(5)
                continue

        if BACKLIGHT_DIMMING_ENABLED:
            adjust_backlight(board.DISPLAY)

        token, observation = get_backend_endpoint_content('get-last-obs', token)
        token, weather_data = get_backend_endpoint_content('get-weather-data', token)

        update_screen(display, observation, weather_data, utc_offset_hour)

        time.sleep(SLEEP_TIME)


main()
