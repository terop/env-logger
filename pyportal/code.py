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
# Sleep time, in seconds, between data refreshes
SLEEP_TIME = 90
# Interval, in seconds, after which the next weather data update is done
WEATHER_UPDATE_INTERVAL = 540


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
    max_row = 20

    for i in range(max_row):
        display[i].text = ''


def get_weather_data(api_key, latitude, longitude):
    """Get weather data for a location defined by the given latitude and longitude values."""
    resp = requests.get(f'https://api.openweathermap.org/data/2.5/onecall?lat={latitude}'
                        f'&lon={longitude}&exclude=minutely,daily,alerts&units=metric'
                        f'&appid={api_key}')
    if resp.status_code == 401:
        print('Error: unauthorised request')
        return ()
    if resp.status_code != 200:
        print(f'Error: got HTTP status code {resp.status_code}')
        return ()

    data = resp.json()
    # First element in the 'hourly' array is for the current hour,
    # second is for the next starting hour
    return (data['current']['weather'][0], data['hourly'][1])


def update_screen(display, logger_data, weather_data):
    """Update screen contents."""
    w_recorded = logger_data['data']['recorded']

    if logger_data['rt-data']:
        rt_recorded = max([tag['recorded'] for tag in logger_data['rt-data']])
    else:
        rt_recorded = w_recorded

    clear_display(display)

    display[0].text = w_recorded
    display[1].text = f'Weather: temperature {logger_data["data"]["fmi_temperature"]}, ' \
        f'cloudiness {logger_data["data"]["cloudiness"]}'
    if weather_data:
        display[1].text += ','
        display[2].text = f'desc \"{weather_data[0]["description"]}\"'
        display[3].text = f'Forecast: temperature {weather_data[1]["feels_like"]}, ' \
            f'cloudiness {weather_data[1]["clouds"]} %,'
        display[4].text = f'wind speed {weather_data[1]["wind_speed"]} m/s, ' \
            f'desc \"{weather_data[1]["weather"][0]["description"]}\"'
        row = 5
    else:
        row = 2

    display[row].text = ''
    row += 1

    display[row].text = rt_recorded
    row += 1
    display[row].text = f'Inside temperature {logger_data["data"]["temperature"]}, ' \
        f'brightness {logger_data["data"]["brightness"]},'
    row += 1
    display[row].text = f'outside temperature {logger_data["data"]["o_temperature"]}'
    row += 1
    if logger_data['data']['rssi']:
        display[row].text = f'Beacon "{logger_data["data"]["name"]}" ' \
            f'RSSI {logger_data["data"]["rssi"]}'
        row += 1

    if logger_data['rt-data']:
        rt_data = logger_data['rt-data']
        seen_locations = []

        for tag in rt_data:
            location = tag['location']

            if location in seen_locations:
                continue

            display[row].text = f'RuuviTag \"{location}\": temperature ' \
                f'{tag["temperature"]},'
            display[row + 1].text = f'humidity {tag["humidity"]}'
            row += 2
            seen_locations.append(location)

    display.show()


def main():
    """Module main function."""
    connect_to_wlan()

    display = SimpleTextDisplay(title=' ', colors=[SimpleTextDisplay.WHITE], font=FONT)
    token = None
    weather_data = None
    last_weather_update = 0

    while True:
        if not token:
            token = fetch_token()
            if not token:
                time.sleep(5)
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

        if last_weather_update >= WEATHER_UPDATE_INTERVAL or not weather_data:
            weather_data = get_weather_data(secrets['owm_api_key'], secrets['location_lat'],
                                            secrets['location_lon'])
            last_weather_update = 0

        update_screen(display, data, weather_data)

        last_weather_update += SLEEP_TIME
        time.sleep(SLEEP_TIME)


main()
