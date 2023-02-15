"""Code for showing various data on a PyPortal Titano display."""

import time
from collections import OrderedDict

# pylint: disable=no-name-in-module
from secrets import secrets

# pylint: disable=import-error
import adafruit_esp32spi.adafruit_esp32spi_socket as socket
import adafruit_requests as requests
import board
import busio
import rtc
import supervisor
from adafruit_bitmap_font import bitmap_font
from adafruit_datetime import datetime, timedelta
from adafruit_esp32spi import adafruit_esp32spi
from adafruit_simple_text_display import SimpleTextDisplay
from digitalio import DigitalInOut

# URL for the backend
BACKEND_URL = secrets['backend-url']
# Path to the bitmap font to use, must include the degree symbol (U+00B0)
FONT = bitmap_font.load_font("fonts/DejaVuSansMono-16.pcf")
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
NW_FAILURE_THRESHOLD = 4


def connect_to_wlan():
    """Connect to WLAN."""
    esp32_cs = DigitalInOut(board.ESP_CS)
    esp32_ready = DigitalInOut(board.ESP_BUSY)
    esp32_reset = DigitalInOut(board.ESP_RESET)

    spi = busio.SPI(board.SCK, board.MOSI, board.MISO)
    esp = adafruit_esp32spi.ESP_SPIcontrol(spi, esp32_cs, esp32_ready, esp32_reset)

    failure_count = 0

    requests.set_socket(socket, esp)

    if esp.status == adafruit_esp32spi.WL_IDLE_STATUS:
        print('ESP32 found and in idle mode')
        print(f'Firmware version: {esp.firmware_version}')
        print(f'MAC addr: {[hex(i) for i in esp.MAC_address]}')

    print('Connecting to AP')
    esp.reset()
    time.sleep(2)

    while not esp.is_connected:
        try:
            esp.connect_AP(secrets['ssid'], secrets['password'])
        except RuntimeError as rte:
            print(f'Error: could not connect to AP, retrying: {rte}')

            failure_count += 1
            if failure_count >= NW_FAILURE_THRESHOLD:
                print(f'Error: AP connection failed {failure_count} times, resetting ESP')
                failure_count = 0
                esp.reset()

            time.sleep(5)
            continue
        print(f'Connected to {str(esp.ssid, "utf-8")}\tRSSI: {esp.rssi}')
        print(f'My IP address is {esp.pretty_ip(esp.ip_address)}')


def fetch_token():
    """Fetch token for getting data from env-logger backend."""
    failure_count = 0
    backend_failure_count = 0

    while True:
        try:
            resp = requests.post(f'{BACKEND_URL}/token-login',
                                 data={'username': secrets['data-read-user']['username'],
                                       'password': secrets['data-read-user']['password']})
            if resp.status_code != 200:
                backend_failure_count += 1
                print('Error: token acquisition failed, backend failure '
                      f'count {backend_failure_count}')

                if backend_failure_count > NW_FAILURE_THRESHOLD:
                    print('Error: token fetch failed: backend problem, '
                          f'failure count {backend_failure_count}')
                    return None

                time.sleep(20)
                continue

            break
        except RuntimeError as rte:
            failure_count += 1
            print(f'Error: token fetch failed: "{rte}", failure count {failure_count}')
            time.sleep(5)

            if failure_count > NW_FAILURE_THRESHOLD:
                print(f'Error: token fetch failed {failure_count} times, reloading board')
                time.sleep(5)
                supervisor.reload()

    return resp.text


def clear_display(display):
    """Clears, i.e. removes all rows, from the given display."""
    max_row = 20

    for i in range(max_row):
        display[i].text = ''


def get_backend_endpoint_content(endpoint, token, no_token=False):
    """Fetches the JSON content of the given backend endpoint.
    Returns a (token, JSON value) tuple."""
    failure_count = 0

    while failure_count < NW_FAILURE_THRESHOLD:
        try:
            if no_token:
                resp = requests.get(f'{BACKEND_URL}/{endpoint}')
            else:
                resp = requests.get(f'{BACKEND_URL}/{endpoint}',
                                    headers={'Authorization': f'Token {token}'})
            if resp.status_code != 200:
                if resp.status_code == 401:
                    print('Error: request was unauthorized, getting new token')
                    token = fetch_token()
                else:
                    print(f'Error: failed to fetch content from "{endpoint}"')
                continue
            break
        except RuntimeError as rte:
            failure_count += 1
            print(f'Error: got exception "{rte}", failure count {failure_count}')
            time.sleep(5)

            if failure_count >= NW_FAILURE_THRESHOLD:
                print(f'Error: endpoint "{endpoint}" fetch failed {failure_count} times, ',
                      'reloading board')
                time.sleep(5)
                supervisor.reload()
        except requests.OutOfRetries as oor:
            print(f'Too many retries exception: {oor}\nReloading board')
            time.sleep(5)
            supervisor.reload()

    return resp.json() if no_token else (token, resp.json())


def set_time(timezone):
    """Get and set local time for the board. Returns the offset to UTC in hours."""
    while True:
        try:
            with requests.get(f'{BACKEND_URL}/misc/time', data={'timezone': timezone}) as resp:
                time_info = resp.json()
                if 'error' in time_info:
                    print(f'Error: time fetching failed: "{time_info["error"]}", retrying')
                    time.sleep(10)
                    continue
                utc_offset_hour = time_info['offset-hour']

                rtc.RTC().datetime = time.localtime(time_info['timestamp'] +
                                                    utc_offset_hour * 3600)

                return utc_offset_hour
        except RuntimeError as ex:
            print(f'Error: an exception occurred in set_time: {ex}')
            if 'ESP32' in str(ex):
                connect_to_wlan()
            time.sleep(5)


def adjust_backlight(display):
    """Adjust backlight value based on the current time."""
    current_time = time.localtime()
    if current_time.tm_hour >= BACKLIGHT_DIMMING_START or \
       current_time.tm_hour < BACKLIGHT_DIMMING_END:
        display.brightness = BACKLIGHT_DIMMING_VALUE
    else:
        display.brightness = BACKLIGHT_DEFAULT_VALUE


def prepare_elec_price_data(elec_price_data, utc_offset_hours):
    """Fetches and prepares electricity price data for display."""
    if not elec_price_data:
        return None

    if 'error' in elec_price_data:
        if elec_price_data['error'] != 'not-enabled':
            print(f'Electricity price data fetch failed: {elec_price_data["error"]}')
            return None

    prices = OrderedDict()
    tz_delta = timedelta(hours=utc_offset_hours)
    for item in elec_price_data:
        prices[datetime.fromisoformat(item['start-time'].replace('Z', '')) + tz_delta] = \
            item['price']

    if datetime.now() > max(prices):
        # No suitable values to show
        return None

    now = datetime.now()
    smallest_diff = 1000000000
    smallest = None

    for start_time in prices:
        diff = abs((start_time - now).total_seconds())
        if diff < smallest_diff:
            smallest_diff = diff
            smallest = start_time

    # Special case handling for the situation when the next hour is closer than the current is
    if now.hour < smallest.hour:
        smallest -= timedelta(hours=1)

    values = {'current': [smallest, prices[smallest]]}

    next_hour = smallest + timedelta(hours=1)
    if prices[next_hour]:
        values['next'] = [next_hour, prices[next_hour]]

    return values


# pylint: disable=too-many-branches,too-many-locals,too-many-statements
def update_screen(display, observation, weather_data, elec_price_data, utc_offset_hour):
    """Update screen contents."""
    w_recorded = observation['data']['recorded']

    if observation['rt-data']:
        rt_recorded = max(tag['recorded'] for tag in observation['rt-data'])
    else:
        rt_recorded = w_recorded

    clear_display(display)

    display[0].text = w_recorded
    if observation['weather-data']:
        sunrise = time.localtime(weather_data['owm']['current']['sunrise'])
        sunrise = f'{sunrise.tm_hour + utc_offset_hour:02}:{sunrise.tm_min:02}'
        sunset = time.localtime(weather_data['owm']['current']['sunset'])
        sunset = f'{sunset.tm_hour + utc_offset_hour:02}:{sunset.tm_min:02}'
        display[0].text += f'           sr {sunrise} ss {sunset}'

        weather = observation['weather-data']
        dt_time = datetime.fromisoformat(weather['time'].replace('Z', ''))
        time_str = f'{dt_time.hour + utc_offset_hour:02}:{dt_time.minute:02}'

        display[1].text = f'Weather ({time_str}): temp {weather["temperature"]} ' + \
            f'\u00b0C, cloudiness {weather["cloudiness"]},'
        display[2].text = 'wind ' + \
            f'{weather["wind-direction"]["short"]} {weather["wind-speed"]} m/s'

    if weather_data['fmi']['forecast']:
        current = weather_data['owm']['current']
        forecast = weather_data['fmi']['forecast']
        forecast_dt = time.localtime(weather_data['owm']['forecast']['dt'])

        display[2].text += f', desc \"{current["weather"][0]["description"]}\"'
        if forecast:
            display[3].text = 'Forecast'
            if forecast_dt and forecast_dt.tm_hour is not None \
               and forecast_dt.tm_min is not None:
                display[3].text += f' ({forecast_dt.tm_hour + utc_offset_hour:02}:' + \
                    f'{forecast_dt.tm_min:02})'
                display[3].text += f': temp {forecast["temperature"]} \u00b0C, ' + \
                    f'clouds {forecast["cloudiness"]} %,'
                display[4].text = f'wind {forecast["wind-direction"]["short"]} ' + \
                    f'{forecast["wind-speed"]} m/s, precipitation {forecast["precipitation"]} mm,'
                display[5].text = \
                    f'desc \"{weather_data["owm"]["forecast"]["weather"][0]["description"]}\"'
                row = 6
    else:
        row = 2

    if elec_price_data:
        current_val = elec_price_data['current']
        display[row].text = f'Elec price: {current_val[0].hour}:{current_val[0].minute:02}:' + \
            f' {current_val[1]} c'
        if 'next' in elec_price_data:
            next_val = elec_price_data['next']
            display[row].text += f', {next_val[0].hour}:{next_val[0].minute:02}: ' + \
                f'{next_val[1]} c'
        row += 1

    display[row].text = ''
    row += 1

    display[row].text = rt_recorded
    row += 1
    display[row].text = f'Brightness {observation["data"]["brightness"]}, '
    display[row].text += f'outside temp {observation["data"]["o-temperature"]} \u00b0C'
    if observation['data']['rssi']:
        display[row].text += ','
        row += 1
        display[row].text = f'beacon "{observation["data"]["name"]}" ' + \
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

    print('Getting current time from backend')
    utc_offset_hour = set_time(secrets['timezone'])
    print('Current time set')

    display = SimpleTextDisplay(title=' ', colors=[SimpleTextDisplay.WHITE], font=FONT)
    token = None
    weather_data = None
    elec_price_metadata = {'raw_data': None,
                           'fetched': None}

    board.DISPLAY.brightness = BACKLIGHT_DEFAULT_VALUE

    while True:
        if not token:
            token = fetch_token()
            if not token:
                continue

        if BACKLIGHT_DIMMING_ENABLED:
            adjust_backlight(board.DISPLAY)

        if not elec_price_metadata['fetched'] or \
           (datetime.now() - elec_price_metadata['fetched']).total_seconds() > 1800:
            token, elec_price_metadata['raw_data'] = get_backend_endpoint_content(
                'data/elec-data', token)
            elec_price_metadata['fetched'] = datetime.now()

        elec_price_data = prepare_elec_price_data(elec_price_metadata['raw_data'],
                                                  utc_offset_hour)
        token, observation = get_backend_endpoint_content('data/latest-obs', token)
        token, weather_data = get_backend_endpoint_content('data/weather', token)

        update_screen(display, observation, weather_data, elec_price_data, utc_offset_hour)

        time.sleep(SLEEP_TIME)


main()
