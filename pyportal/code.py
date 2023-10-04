"""Code for showing various data on a PyPortal Titano display."""

import time
from collections import OrderedDict
from secrets import secrets

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

HTTP_STATUS_CODE_OK = 200
HTTP_STATUS_CODE_UNAUTHORIZED = 401

# URL for the backend
BACKEND_URL = secrets['backend-url']
# Path to the bitmap font to use, must include the degree symbol (U+00B0)
FONT = bitmap_font.load_font("fonts/DejaVuSansMono-16.pcf")
# Sleep time (in seconds) between data refreshes
SLEEP_TIME = 85
# Sleep time (in seconds) between clock setting
TIME_SET_SLEEP_TIME = 360

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
NW_FAILURE_THRESHOLD = 3


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
        except (RuntimeError, ConnectionError) as ex:
            print(f'Error: could not connect to AP, retrying: {ex}')

            failure_count += 1
            if failure_count >= NW_FAILURE_THRESHOLD:
                print(f'Error: AP connection failed {failure_count} times, '
                      'resetting ESP')
                failure_count = 0
                esp.reset()

            time.sleep(5)
            continue
        print(f'Connected to {str(esp.ssid, "utf-8")}    RSSI: {esp.rssi}')
        print(f'My IP address is {esp.pretty_ip(esp.ip_address)}')


def fetch_token():
    """Fetch token for getting data from env-logger backend."""
    failure_count = 0
    backend_failure_count = 0

    while True:
        try:
            resp = requests.post(f'{BACKEND_URL}/token-login',
                                 data={'username': \
                                       secrets['data-read-user']['username'],
                                       'password': \
                                       secrets['data-read-user']['password']})
            if resp.status_code != HTTP_STATUS_CODE_OK:
                backend_failure_count += 1
                print('Error: token acquisition failed, backend failure '
                      f'count {backend_failure_count}')

                if backend_failure_count >= NW_FAILURE_THRESHOLD:
                    print('Error: token fetch failed: backend problem, '
                          f'failure count {backend_failure_count}')
                    return None

                time.sleep(20)
                continue

            break
        except (RuntimeError, BrokenPipeError) as ex:
            failure_count += 1
            print(f'Error: token fetch failed: "{ex}", failure count {failure_count}')
            time.sleep(5)

            if failure_count >= NW_FAILURE_THRESHOLD:
                print(f'Error: token fetch failed {failure_count} times, '
                      'reloading board')
                time.sleep(5)
                supervisor.reload()

    return resp.text


def clear_display(display):
    """Clear, i.e. removes all rows, from the given display."""
    max_row = 25

    for i in range(max_row):
        display[i].text = ''


def get_backend_endpoint_content(endpoint, token):
    """Fetch the JSON content of the given backend endpoint.

    Returns a (token, JSON value) tuple.
    """
    sleep_time = 5
    failure_count = 0

    try:
        while failure_count <= NW_FAILURE_THRESHOLD:
            try:
                resp = requests.get(f'{BACKEND_URL}/{endpoint}',
                                    headers={'Authorization': f'Token {token}'})
                if resp.status_code != HTTP_STATUS_CODE_OK:
                    if resp.status_code == HTTP_STATUS_CODE_UNAUTHORIZED:
                        print('Error: request was unauthorized, getting new token')
                        token = fetch_token()
                    else:
                        print(f'Error: failed to fetch content from "{endpoint}"')
                    continue

                break
            except (RuntimeError, BrokenPipeError) as ex:
                failure_count += 1
                print(f'Error: got exception "{ex}", failure count {failure_count}')
                time.sleep(sleep_time)

                if failure_count >= NW_FAILURE_THRESHOLD:
                    print(f'Error: endpoint "{endpoint}" fetch failed {failure_count} '
                          'times, reloading board')
                    time.sleep(sleep_time)
                    supervisor.reload()

        return (token, resp.json())
    except (TimeoutError, requests.OutOfRetries) as ex:
        print(f'Error: endpoint "{endpoint}" fetch failed: {ex}, reloading board')
        time.sleep(sleep_time)
        supervisor.reload()


def set_time(timezone):
    """Get and set local time for the board. Returns the offset to UTC in hours."""
    while True:
        try:
            with requests.get(f'{BACKEND_URL}/misc/time',
                              data={'timezone': timezone}) as resp:
                time_info = resp.json()
                if 'error' in time_info:
                    print(f'Error: time fetching failed: "{time_info["error"]}", '
                          'retrying')
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
        except TimeoutError as ex:
            print(f'Error: an exception occurred in set_time: {ex}')
            supervisor.reload()


def adjust_backlight(display):
    """Adjust backlight value based on the current time."""
    current_time = time.localtime()
    if current_time.tm_hour >= BACKLIGHT_DIMMING_START or \
       current_time.tm_hour < BACKLIGHT_DIMMING_END:
        display.brightness = BACKLIGHT_DIMMING_VALUE
    else:
        display.brightness = BACKLIGHT_DEFAULT_VALUE


def prepare_elec_price_data(elec_price_data, utc_offset_hours):
    """Fetch and prepare electricity price data for display."""
    if not elec_price_data:
        return None

    if 'error' in elec_price_data and elec_price_data['error'] != 'not-enabled':
        print(f'Electricity price data fetch failed: {elec_price_data["error"]}')
        return None

    prices = OrderedDict()
    tz_delta = timedelta(hours=utc_offset_hours)
    for item in elec_price_data['data-hour']:
        prices[datetime.fromisoformat(item['start-time'].replace('Z', '')) + \
               tz_delta] = item['price']

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

    # Special case handling for the situation when the next hour is closer than
    # the current is
    if now.hour < smallest.hour:
        smallest -= timedelta(hours=1)

    values = {'current': [smallest, prices[smallest]]}

    next_hour = smallest + timedelta(hours=1)
    if next_hour in prices:
        values['next'] = [next_hour, prices[next_hour]]

    return values


def update_screen(display, observation, weather_data, elec_price_data, utc_offset_hour, # noqa: PLR0912, PLR0913, PLR0915
                  time_update_only=False):
    """Update screen contents."""
    c_time = time.localtime()
    time_str = f'{c_time.tm_mday}.{c_time.tm_mon}.{c_time.tm_year} ' + \
        f'{c_time.tm_hour:02}:{c_time.tm_min:02}:{c_time.tm_sec:02}'

    if time_update_only:
        if 'sr' in display[0].text:
            sr_text = display[0].text[display[0].text.index('sr'):]

            display[0].text = f'{time_str}           {sr_text}'
        else:
            display[0].text = time_str
        return

    rt_recorded = max(tag['recorded'] for tag in observation['rt-data']) \
        if observation['rt-data'] else None

    clear_display(display)

    display[0].text = time_str

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
                    f'{forecast["wind-speed"]} m/s, precipitation ' + \
                    f'{forecast["precipitation"]} mm,'
                display[5].text = \
                    f'desc \"{weather_data["owm"]["forecast"]["weather"][0]["description"]}\"' # noqa: E501
                row = 6
    else:
        row = 2

    if elec_price_data:
        if 'current' in elec_price_data:
            current_val = elec_price_data['current']
            display[row].text = 'Elec price: ' + \
                f'{current_val[0].hour}:{current_val[0].minute:02}: {current_val[1]} c'
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
    """Run the main module loop."""
    connect_to_wlan()

    print('Getting current time from backend')
    utc_offset_hour = set_time(secrets['timezone'])
    print('Current time set')

    display = SimpleTextDisplay(title=' ', colors=[SimpleTextDisplay.WHITE], font=FONT)
    seconds_slept = -1
    time_set_seconds_slept = 0
    token = None
    weather_data = None
    elec_price_metadata = {'raw_data': None,
                           'fetched': None}
    elec_price_fetch_threshold = 1800

    board.DISPLAY.brightness = BACKLIGHT_DEFAULT_VALUE

    while True:
        if not token:
            token = fetch_token()
            if not token:
                continue

        if BACKLIGHT_DIMMING_ENABLED:
            adjust_backlight(board.DISPLAY)

        if not elec_price_metadata['fetched'] or \
           (datetime.now() - elec_price_metadata['fetched']).total_seconds() > \
           elec_price_fetch_threshold:
            token, elec_price_metadata['raw_data'] = get_backend_endpoint_content(
                'data/elec-data', token)
            elec_price_metadata['fetched'] = datetime.now()

        if seconds_slept in [-1, 1]:
            elec_price_data = prepare_elec_price_data(elec_price_metadata['raw_data'],
                                                      utc_offset_hour)
            token, observation = get_backend_endpoint_content('data/latest-obs', token)
            token, weather_data = get_backend_endpoint_content('data/weather', token)

        update_screen(display, observation, weather_data, elec_price_data,
                      utc_offset_hour, 0 < seconds_slept < SLEEP_TIME)

        if seconds_slept == -1 or seconds_slept >= SLEEP_TIME:
            seconds_slept = 0

        if time_set_seconds_slept >= TIME_SET_SLEEP_TIME:
            set_time(secrets['timezone'])
            time_set_seconds_slept = 0

        seconds_slept += 1
        time_set_seconds_slept += 1
        time.sleep(1)


main()
