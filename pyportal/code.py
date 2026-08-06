"""Code for showing various data on a PyPortal Titano display."""

import time
from collections import OrderedDict
from os import getenv

import adafruit_requests
import board
import busio
import neopixel
import rtc
import supervisor
from adafruit_bitmap_font import bitmap_font
from adafruit_datetime import datetime, timedelta
from adafruit_esp32spi import adafruit_esp32spi
from adafruit_esp32spi.adafruit_esp32spi_wifimanager import WiFiManager
from adafruit_simple_text_display import SimpleTextDisplay
from digitalio import DigitalInOut

HTTP_STATUS_CODE_OK = 200
HTTP_STATUS_CODE_UNAUTHORIZED = 401

# URL for the backend
BACKEND_URL = getenv('BACKEND_URL')
# Path to the bitmap font to use, must include the degree symbol (U+00B0)
FONT = bitmap_font.load_font("fonts/DejaVuSansMono-16.pcf")
# Sleep time (in seconds) between data refreshes
SLEEP_TIME = 85
# Sleep time (in seconds) between clock setting
TIME_SET_SLEEP_TIME = 360
# Interval (in minutes) between data storage events
DATA_STORAGE_INTERVAL = 4

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
    try:
        esp32_cs = DigitalInOut(board.ESP_CS)
    except ValueError as ve:
        print(f'Error: ESP32 error: {ve}')
        time.sleep(5)
        supervisor.reload()

    esp32_ready = DigitalInOut(board.ESP_BUSY)
    esp32_reset = DigitalInOut(board.ESP_RESET)

    spi = busio.SPI(board.SCK, board.MOSI, board.MISO)
    esp = adafruit_esp32spi.ESP_SPIcontrol(spi, esp32_cs, esp32_ready, esp32_reset)
    status_pixel = neopixel.NeoPixel(board.NEOPIXEL, 1, brightness=0.2)
    wifi = WiFiManager(esp, getenv('WIFI_SSID'), getenv('WIFI_PASSWORD'),
                       status_pixel=status_pixel)

    print('Connecting to AP')
    wifi.connect()
    return wifi


def fetch_token(wifi):
    """Fetch JWT token for getting data from env-logger backend."""
    failure_count = 0
    backend_failure_count = 0

    while True:
        try:
            resp = wifi.post(getenv('OID_TOKEN_ENDPOINT'),
                             data={'grant_type': 'client_credentials',
                                   'client_id': getenv('OID_CLIENT_ID'),
                                   'client_secret': getenv('OID_CLIENT_SECRET')})
            if resp.status_code != HTTP_STATUS_CODE_OK:
                backend_failure_count += 1
                print('Error: token acquisition failed, failure count '
                      f'{backend_failure_count}')

                if backend_failure_count >= NW_FAILURE_THRESHOLD:
                    print('Error: token fetch failed: authentication service problem, '
                          f'failure count {backend_failure_count}')
                    return None, None

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

    token_resp = resp.json()
    access_token_expiry_time = datetime.now() + timedelta(
        seconds=token_resp['expires_in'])

    return token_resp['access_token'], access_token_expiry_time


def clear_display(display):
    """Clear, i.e. removes all rows, from the given display."""
    max_row = 25

    for i in range(max_row):
        display[i].text = ''


def get_backend_endpoint_content(wifi, endpoint, token, token_expiry, params=None):
    """Fetch the JSON content of the given backend endpoint.

    Returns a (token, token_expiry, JSON value) tuple.
    """
    sleep_time = 5
    failure_count = 0

    try:
        while failure_count <= NW_FAILURE_THRESHOLD:
            try:
                if params:
                    resp = wifi.get(f'{BACKEND_URL}/{endpoint}',
                                    data=params,
                                    headers={'Bearer': token})
                else:
                    resp = wifi.get(f'{BACKEND_URL}/{endpoint}',
                                    headers={'Bearer': token})
                if resp.status_code != HTTP_STATUS_CODE_OK:
                    if resp.status_code == HTTP_STATUS_CODE_UNAUTHORIZED:
                        print('Error: request was unauthorised, getting new token')
                        token, token_expiry = fetch_token(wifi)
                        if not token:
                            continue
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

        return (token, token_expiry, resp.json())
    except (ConnectionError, TimeoutError, adafruit_requests.OutOfRetries) as ex:
        print(f'Error: endpoint "{endpoint}" fetch failed: {ex}, reloading board')
        time.sleep(sleep_time)
        supervisor.reload()


def set_time(wifi, timezone):
    """Get and set local time for the board. Returns the offset to UTC in hours."""
    while True:
        try:
            with wifi.get(f'{BACKEND_URL}/misc/time',
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
        except (RuntimeError, TimeoutError) as ex:
            print(f'Error: an exception occurred in set_time: {ex}')
            time.sleep(5)
            supervisor.reload()


def adjust_backlight(display):
    """Adjust backlight value based on the current time."""
    current_time = time.localtime()
    if current_time.tm_hour >= BACKLIGHT_DIMMING_START or \
       current_time.tm_hour < BACKLIGHT_DIMMING_END:
        display.brightness = BACKLIGHT_DIMMING_VALUE
    else:
        display.brightness = BACKLIGHT_DEFAULT_VALUE


def validate_elec_data(elec_data):
    """Return False when electricity data is missing or has a fetch error."""
    if not elec_data:
        return False

    if 'error' in elec_data and elec_data['error'] != 'not-enabled':
        print(f'Electricity price data fetch failed: {elec_data["error"]}')
        return False

    return True


def parse_hourly_prices(elec_data, utc_offset_hours):
    """Build a mapping of local start times to hourly electricity prices."""
    prices = OrderedDict()
    tz_delta = timedelta(hours=utc_offset_hours)
    for item in elec_data['data-hour']:
        prices[datetime.fromisoformat(item['start-time'].replace('Z', '')) +
               tz_delta] = item['price']

    return prices


def closest_price_start(prices, now):
    """Find the price slot closest to the given time."""
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

    return smallest


def build_elec_display_values(elec_data, prices, start_time):
    """Assemble electricity display values from prepared price data."""
    values = {'current': [start_time, prices[start_time]]}

    next_hour = start_time + timedelta(hours=1)
    if next_hour in prices:
        values['next'] = [next_hour, prices[next_hour]]

    if elec_data['month-price-avg'] is not None:
        values['average'] = elec_data['month-price-avg']

    if elec_data['month-consumption'] is not None:
        values['month-consumption'] = elec_data['month-consumption']

    if elec_data['month-cost'] is not None:
        values['month-cost'] = elec_data['month-cost']

    return values


def prepare_elec_data(elec_data, utc_offset_hours):
    """Fetch and prepare electricity data for display."""
    if not validate_elec_data(elec_data):
        return None

    prices = parse_hourly_prices(elec_data, utc_offset_hours)
    if datetime.now() > max(prices):
        # No suitable values to show
        return None

    start_time = closest_price_start(prices, datetime.now())
    return build_elec_display_values(elec_data, prices, start_time)


def format_local_time():
    """Format the current local time for display."""
    c_time = time.localtime()
    return (
        f'{c_time.tm_mday}.{c_time.tm_mon}.{c_time.tm_year} '
        f'{c_time.tm_hour:02}:{c_time.tm_min:02}:{c_time.tm_sec:02}'
    )


def update_time_row(display, time_str):
    """Update only the clock row, preserving sunrise / sunset suffix if present."""
    if 'sr' in display[0].text:
        sr_text = display[0].text[display[0].text.index('sr'):]
        display[0].text = f'{time_str}           {sr_text}'
    else:
        display[0].text = time_str


def render_weather_rows(display, observation, weather_data, utc_offset_hour, time_str):
    """Render the header clock row and current weather rows."""
    display[0].text = time_str

    if not observation['weather-data']:
        return

    display[0].text += (
        f'           sr {weather_data['ast']['sunrise']} '
        f'ss {weather_data['ast']['sunset']}'
    )

    weather = observation['weather-data']
    dt_time = datetime.fromisoformat(weather['time'].replace('Z', ''))
    weather_time_str = f'{dt_time.hour + utc_offset_hour:02}:{dt_time.minute:02}'

    display[1].text = (
        f'Weather ({weather_time_str}): temp {weather["temperature"]} \u00b0C, '
        f'feel {weather["feels-like"]} \u00b0C,'
    )
    display[2].text = (
        f'clouds {weather["cloudiness"]}, wind '
        f'{weather["wind-direction"]["short"]} {weather["wind-speed"]} m/s, '
        f'humidity {int(weather["humidity"])} %H'
    )


def render_forecast_rows(display, weather_data, utc_offset_hour):
    """Render forecast rows and return the next row index for following sections."""
    if weather_data['fmi']['forecast']:
        forecast = weather_data['fmi']['forecast']
        forecast_dt = datetime.fromisoformat(forecast['time'].replace('Z', ''))

        if forecast:
            display[3].text = 'Forecast'
            if forecast_dt and forecast_dt.hour is not None \
               and forecast_dt.minute is not None:
                display[3].text += (
                    f' ({forecast_dt.hour + utc_offset_hour:02}:'
                    f'{forecast_dt.minute:02})'
                )
                display[3].text += (
                    f': temp {forecast["temperature"]} \u00b0C, '
                    f'feel {forecast["feels-like"]} \u00b0C, '
                )
                display[4].text = (
                    f'clouds {forecast["cloudiness"]} %, '
                    f'wind {forecast["wind-direction"]["short"]} '
                    f'{forecast["wind-speed"]} m/s, precip '
                    f'{forecast["precipitation"]} mm'
                )
                return 5
    else:
        return 1

    return None


def render_electricity_month_rows(display, elec_data, row):
    """Render electricity monthly stats rows and return the next row index."""
    if 'average' not in elec_data and 'month-consumption' not in elec_data:
        return row

    display[row].text += 'Current month: '

    if 'month-consumption' in elec_data:
        display[row].text += \
            f'consumption {elec_data["month-consumption"]} kWh'
    if 'average' in elec_data:
        if display[row].text[-1] != ' ':
            display[row].text += ','
            row += 1

        display[row].text += \
            f'average price {elec_data["average"]} c / kWh'

    if 'month-cost' in elec_data:
        if 'average' in elec_data or 'month-consumption' in elec_data:
            display[row].text += ', '
            if 'month-consumption' not in elec_data and 'average' in elec_data:
                row += 1

        display[row].text += f'cost {elec_data["month-cost"]} €'

    return row + 1


def render_electricity_rows(display, elec_data, row):
    """Render electricity pricing rows and return the next row index."""
    if not elec_data:
        return row

    if 'current' in elec_data:
        current_val = elec_data['current']
        display[row].text = (
            f'Elec price: {current_val[0].hour}:'
            f'{current_val[0].minute:02}: {current_val[1]} c'
        )
    if 'next' in elec_data:
        next_val = elec_data['next']
        display[row].text += (
            f', {next_val[0].hour}:{next_val[0].minute:02}: {next_val[1]} c'
        )
    row += 1
    return render_electricity_month_rows(display, elec_data, row)


def render_observation_rows(display, observation, rt_recorded, row):
    """Render observation summary rows and return the next row index."""
    display[row].text = ''
    row += 1

    display[row].text = rt_recorded
    row += 1
    o_data = observation['data']
    if o_data['outside-temperature'] is not None:
        display[row].text = f'Outside temp {o_data["outside-temperature"]} \u00b0C'
    if o_data['beacon-rssi'] is not None:
        display[row].text += f', beacon RSSI {o_data["beacon-rssi"]} dBm,'

    if o_data['iaqs'] is not None:
        row += 1
        display[row].text = (
            f'air: IAQS {o_data["iaqs"]}, CO\u2082 {o_data["ruuvi-co2"]} ppm, '
            f'PM 2.5 {o_data["pm-25"]} \u00b5g/m\u00b3'
        )
    row += 1

    return row


def render_ruuvi_tag_rows(display, observation, row):
    """Render RuuviTag rows and return the next row index."""
    if not observation['rt-data']:
        return row

    rt_data = observation['rt-data']
    seen_names = []
    hidden_ruuvitags = getenv('HIDDEN_RUUVITAG_NAMES').split(',')

    for tag in rt_data:
        name = tag['name']

        if (name in seen_names) or (name in hidden_ruuvitags):
            continue

        display[row].text = (
            f'RuuviTag \"{name}\": temperature {tag["temperature"]} \u00b0C,'
        )
        display[row + 1].text = f'humidity {tag["humidity"]} %H'
        row += 2
        seen_names.append(name)

    return row


def update_screen(display, data, *, time_update_only=False):
    """Update screen contents.

    data keys: observation, weather_data, elec_data, utc_offset_hour
    """
    time_str = format_local_time()

    if time_update_only:
        update_time_row(display, time_str)
        return

    observation = data['observation']
    weather_data = data['weather_data']
    elec_data = data['elec_data']
    utc_offset_hour = data['utc_offset_hour']

    rt_recorded = max(tag['recorded'] for tag in observation['rt-data']) \
        if observation['rt-data'] else None

    clear_display(display)
    render_weather_rows(display, observation, weather_data, utc_offset_hour, time_str)
    row = render_forecast_rows(display, weather_data, utc_offset_hour)
    row = render_electricity_rows(display, elec_data, row)
    row = render_observation_rows(display, observation, rt_recorded, row)
    render_ruuvi_tag_rows(display, observation, row)
    display.show()


def main():
    """Run the main module loop."""
    wifi = connect_to_wlan()

    print('Getting current time from backend')
    utc_offset_hour = set_time(wifi, getenv('TIMEZONE'))
    print('Current time set')

    display = SimpleTextDisplay(colors=[SimpleTextDisplay.WHITE], font=FONT)
    init_fetch_done = False
    time_set_seconds_slept = 0
    token = None
    access_token_expiry_time = None
    weather_data = None
    elec_price_metadata = {'raw_data': None,
                           'fetched': None}
    elec_price_fetch_threshold = 1800
    data_update_second_threshold = 40

    board.DISPLAY.brightness = BACKLIGHT_DEFAULT_VALUE

    while True:
        try:
            if not token or datetime.now() >= access_token_expiry_time:
                token, access_token_expiry_time = fetch_token(wifi)
                if not token:
                    continue

            if BACKLIGHT_DIMMING_ENABLED:
                adjust_backlight(board.DISPLAY)

            if not elec_price_metadata['fetched'] or \
               (datetime.now() - elec_price_metadata['fetched']).total_seconds() > \
               elec_price_fetch_threshold:
                token, access_token_expiry_time, elec_price_metadata['raw_data'] = \
                    get_backend_endpoint_content(
                        wifi, 'data/elec-data', token, access_token_expiry_time,
                        {'addFees': 'true'})
                elec_price_metadata['fetched'] = datetime.now()

            now = datetime.now()
            update_data = now.minute % DATA_STORAGE_INTERVAL == 0 and \
                now.second == data_update_second_threshold

            if update_data or not init_fetch_done:
                elec_data = prepare_elec_data(
                    elec_price_metadata['raw_data'],
                    utc_offset_hour)
                token, access_token_expiry_time, observation = \
                    get_backend_endpoint_content(
                        wifi, 'data/latest-obs', token, access_token_expiry_time)
                token, access_token_expiry_time, weather_data = \
                    get_backend_endpoint_content(
                        wifi, 'data/weather', token, access_token_expiry_time)
                if not init_fetch_done:
                    init_fetch_done = True
                    update_data = True

            update_screen(
                display,
                {
                    'observation': observation,
                    'weather_data': weather_data,
                    'elec_data': elec_data,
                    'utc_offset_hour': utc_offset_hour,
                },
                time_update_only=not update_data,
            )

            if time_set_seconds_slept >= TIME_SET_SLEEP_TIME:
                set_time(wifi, getenv('TIMEZONE'))
                time_set_seconds_slept = 0

            time_set_seconds_slept += 1
            time.sleep(1)
        except MemoryError:
            # Reset board without prints as there may not be memory to print anything
            supervisor.reload()


main()
