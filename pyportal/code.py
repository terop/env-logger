"""Code for showing various data on a PyPortal Titano display."""

import gc
import time
from os import getenv

import adafruit_requests
import board
import busio
import neopixel
import rtc
import supervisor
from adafruit_bitmap_font import bitmap_font
from adafruit_esp32spi import adafruit_esp32spi
from adafruit_esp32spi.adafruit_esp32spi_wifimanager import WiFiManager
from adafruit_simple_text_display import SimpleTextDisplay
from digitalio import DigitalInOut

HTTP_STATUS_CODE_OK = 200
HTTP_STATUS_CODE_UNAUTHORIZED = 401

# URL for the backend
BACKEND_URL = getenv("BACKEND_URL")
# Path to the bitmap font to use, must include the degree symbol (U+00B0)
FONT_PATH = "fonts/DejaVuSansMono-16.pcf"
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
# Maximum SimpleTextDisplay rows to manage
DISPLAY_MAX_ROWS = 25
# Seconds between electricity price metadata refreshes
ELEC_PRICE_FETCH_THRESHOLD = 1800
# Second-of-minute when periodic observation refresh runs
DATA_UPDATE_SECOND = 40

def connect_to_wlan():
    """Connect to WLAN."""
    try:
        esp32_cs = DigitalInOut(board.ESP_CS)
    except ValueError as ve:
        print(f"Error: ESP32 error: {ve}")
        time.sleep(5)
        supervisor.reload()

    esp32_ready = DigitalInOut(board.ESP_BUSY)
    esp32_reset = DigitalInOut(board.ESP_RESET)

    spi = busio.SPI(board.SCK, board.MOSI, board.MISO)
    esp = adafruit_esp32spi.ESP_SPIcontrol(spi, esp32_cs, esp32_ready, esp32_reset)
    status_pixel = neopixel.NeoPixel(board.NEOPIXEL, 1, brightness=0.2)
    wifi = WiFiManager(
        esp, getenv("WIFI_SSID"), getenv("WIFI_PASSWORD"), status_pixel=status_pixel
    )

    print("Connecting to AP")
    wifi.connect()
    return wifi


def fetch_token(wifi):
    """Fetch JWT token for getting data from env-logger backend."""
    failure_count = 0
    backend_failure_count = 0

    while True:
        resp = None
        try:
            try:
                resp = wifi.post(
                    getenv("OID_TOKEN_ENDPOINT"),
                    data={
                        "grant_type": "client_credentials",
                        "client_id": getenv("OID_CLIENT_ID"),
                        "client_secret": getenv("OID_CLIENT_SECRET"),
                    },
                )
                if resp.status_code != HTTP_STATUS_CODE_OK:
                    backend_failure_count += 1
                    print(
                        "Error: token acquisition failed, failure count "
                        f"{backend_failure_count}"
                    )

                    if backend_failure_count >= NW_FAILURE_THRESHOLD:
                        print(
                            "Error: token fetch failed: authentication service "
                            f"problem, failure count {backend_failure_count}"
                        )
                        return None, None

                    time.sleep(20)
                    continue

                gc.collect()
                token_resp = resp.json()
                access_token_expiry_time = time.time() + token_resp["expires_in"]
                return token_resp["access_token"], access_token_expiry_time
            except (RuntimeError, BrokenPipeError) as ex:
                failure_count += 1
                print(
                    f'Error: token fetch failed: "{ex}", failure count {failure_count}'
                )
                time.sleep(5)

                if failure_count >= NW_FAILURE_THRESHOLD:
                    print(
                        f"Error: token fetch failed {failure_count} times, "
                        "reloading board"
                    )
                    time.sleep(5)
                    supervisor.reload()
        finally:
            if resp is not None:
                resp.close()


def blank_display_rows(display, start_row):
    """Blank display rows from start_row onward."""
    for i in range(start_row, DISPLAY_MAX_ROWS):
        display[i].text = ""


def wifi_get_endpoint(wifi, endpoint, token, params=None):
    """GET a backend endpoint and return the response object."""
    url = f"{BACKEND_URL}/{endpoint}"
    headers = {"Bearer": token}
    if params:
        return wifi.get(url, data=params, headers=headers)
    return wifi.get(url, headers=headers)


def handle_endpoint_http_error(wifi, endpoint, resp, token, token_expiry):
    """Handle a non-OK HTTP status. Returns updated (token, token_expiry)."""
    if resp.status_code == HTTP_STATUS_CODE_UNAUTHORIZED:
        print("Error: request was unauthorised, getting new token")
        return fetch_token(wifi)
    print(f'Error: failed to fetch content from "{endpoint}"')
    return token, token_expiry


def reload_after_endpoint_failures(endpoint, failure_count, sleep_time):
    """Reload the board after repeated endpoint fetch failures."""
    print(
        f'Error: endpoint "{endpoint}" fetch failed '
        f"{failure_count} times, reloading board"
    )
    time.sleep(sleep_time)
    supervisor.reload()


def get_backend_endpoint_content(wifi, endpoint, token, token_expiry, params=None):
    """Fetch the JSON content of the given backend endpoint.

    Returns a (token, token_expiry, JSON value) tuple.
    """
    sleep_time = 5
    failure_count = 0

    try:
        while failure_count <= NW_FAILURE_THRESHOLD:
            resp = None
            try:
                try:
                    resp = wifi_get_endpoint(wifi, endpoint, token, params)
                    if resp.status_code != HTTP_STATUS_CODE_OK:
                        token, token_expiry = handle_endpoint_http_error(
                            wifi, endpoint, resp, token, token_expiry
                        )
                        continue

                    gc.collect()
                    payload = resp.json()
                except (RuntimeError, BrokenPipeError) as ex:
                    failure_count += 1
                    print(f'Error: got exception "{ex}", failure count {failure_count}')
                    time.sleep(sleep_time)
                    if failure_count >= NW_FAILURE_THRESHOLD:
                        reload_after_endpoint_failures(
                            endpoint, failure_count, sleep_time
                        )
                else:
                    return (token, token_expiry, payload)
            finally:
                if resp is not None:
                    resp.close()
    except (ConnectionError, TimeoutError, adafruit_requests.OutOfRetries) as ex:
        print(f'Error: endpoint "{endpoint}" fetch failed: {ex}, reloading board')
        time.sleep(sleep_time)
        supervisor.reload()


def set_time(wifi, timezone):
    """Get and set local time for the board. Returns the offset to UTC in hours."""
    while True:
        try:
            with wifi.get(
                f"{BACKEND_URL}/misc/time", data={"timezone": timezone}
            ) as resp:
                time_info = resp.json()
                if "error" in time_info:
                    print(
                        f'Error: time fetching failed: "{time_info["error"]}", retrying'
                    )
                    time.sleep(10)
                    continue
                utc_offset_hour = time_info["offset-hour"]

                rtc.RTC().datetime = time.localtime(
                    time_info["timestamp"] + utc_offset_hour * 3600
                )

                return utc_offset_hour
        except (RuntimeError, TimeoutError) as ex:
            print(f"Error: an exception occurred in set_time: {ex}")
            time.sleep(5)
            supervisor.reload()


def adjust_backlight(display):
    """Adjust backlight value based on the current time."""
    current_time = time.localtime()
    if (
        current_time.tm_hour >= BACKLIGHT_DIMMING_START
        or current_time.tm_hour < BACKLIGHT_DIMMING_END
    ):
        display.brightness = BACKLIGHT_DIMMING_VALUE
    else:
        display.brightness = BACKLIGHT_DEFAULT_VALUE


def validate_elec_data(elec_data):
    """Return False when electricity data is missing or has a fetch error."""
    if not elec_data:
        return False

    if "error" in elec_data and elec_data["error"] != "not-enabled":
        print(f"Electricity price data fetch failed: {elec_data['error']}")
        return False

    return True


def _parse_iso_to_epoch(iso_str):
    """Parse an ISO 8601 string (with or without trailing Z) to epoch seconds."""
    s = iso_str.replace("Z", "")
    return time.mktime((
        int(s[0:4]), int(s[5:7]), int(s[8:10]),
        int(s[11:13]), int(s[14:16]), int(s[17:19]),
        -1, -1, -1,
    ))


def compress_elec_hours(elec_data, utc_offset_hours):
    """Compress hourly electricity JSON into a compact hour-keyed map."""
    tz_offset_sec = utc_offset_hours * 3600
    hours = {}
    latest_hour_key = None
    for item in elec_data["data-hour"]:
        local = time.localtime(_parse_iso_to_epoch(item["start-time"]) + tz_offset_sec)
        hour_key = (
            ((local.tm_year * 100 + local.tm_mon) * 100 + local.tm_mday) * 100
            + local.tm_hour
        )
        hours[hour_key] = item["price"]
        if latest_hour_key is None or hour_key > latest_hour_key:
            latest_hour_key = hour_key
    return hours, latest_hour_key


def store_elec_metadata(elec_price_metadata, elec_data, utc_offset_hours):
    """Store a compact electricity payload and drop the bulky JSON."""
    if not validate_elec_data(elec_data):
        elec_price_metadata["hours"] = None
        elec_price_metadata["month-price-avg"] = None
        elec_price_metadata["month-consumption"] = None
        elec_price_metadata["month-cost"] = None
        elec_price_metadata["latest-hour-key"] = None
        elec_price_metadata["fetched"] = time.time()
        return

    hours = None
    latest_hour_key = None
    if elec_data.get("data-hour"):
        hours, latest_hour_key = compress_elec_hours(elec_data, utc_offset_hours)

    elec_price_metadata["hours"] = hours
    elec_price_metadata["latest-hour-key"] = latest_hour_key
    elec_price_metadata["month-price-avg"] = elec_data.get("month-price-avg")
    elec_price_metadata["month-consumption"] = elec_data.get("month-consumption")
    elec_price_metadata["month-cost"] = elec_data.get("month-cost")
    elec_price_metadata["fetched"] = time.time()


def hour_key_for_struct_time(c_time):
    """Return comparable YYYYMMDDHH integer for a struct_time."""
    return (((c_time.tm_year * 100 + c_time.tm_mon) * 100 + c_time.tm_mday) * 100) + (
        c_time.tm_hour
    )


def prepare_elec_data(elec_price_metadata):
    """Prepare current/next electricity display values from compact hour data."""
    hours = elec_price_metadata.get("hours")
    if not hours:
        return None

    now_seconds = time.time()
    now_local = time.localtime(now_seconds)
    current_hour_key = hour_key_for_struct_time(now_local)
    latest_hour_key = elec_price_metadata.get("latest-hour-key")
    if latest_hour_key is not None and current_hour_key > latest_hour_key:
        return None

    current_price = hours.get(current_hour_key)
    if current_price is None:
        return None

    values = {"current": [now_local.tm_hour, 0, current_price]}
    next_local = time.localtime(now_seconds + 3600)
    next_hour_key = hour_key_for_struct_time(next_local)
    next_price = hours.get(next_hour_key)
    if next_price is not None:
        values["next"] = [next_local.tm_hour, 0, next_price]

    if elec_price_metadata["month-price-avg"] is not None:
        values["average"] = elec_price_metadata["month-price-avg"]
    if elec_price_metadata["month-consumption"] is not None:
        values["month-consumption"] = elec_price_metadata["month-consumption"]
    if elec_price_metadata["month-cost"] is not None:
        values["month-cost"] = elec_price_metadata["month-cost"]

    return values


def slim_observation(raw, hidden_ruuvitags):
    """Keep only observation fields needed for display."""
    if not raw:
        return {
            "weather-data": None,
            "data": {},
            "rt-data": [],
        }

    weather = raw.get("weather-data")
    if weather:
        wind = weather.get("wind-direction-str") or {}
        weather_data = {
            "time": weather.get("time"),
            "temperature": weather.get("temperature"),
            "feels-like": weather.get("feels-like"),
            "cloudiness": weather.get("cloudiness"),
            "wind-speed": weather.get("wind-speed"),
            "humidity": weather.get("humidity"),
            "wind-direction-str": {"short": wind.get("short")},
        }
    else:
        weather_data = weather

    o_data = raw.get("data") or {}
    data = {
        "outside-temperature": o_data.get("outside-temperature"),
        "beacon-rssi": o_data.get("beacon-rssi"),
        "iaqs": o_data.get("iaqs"),
        "ruuvi-co2": o_data.get("ruuvi-co2"),
        "pm-25": o_data.get("pm-25"),
    }

    seen_names = set()
    rt_data = []
    for tag in raw.get("rt-data") or []:
        name = tag.get("name")
        if (name in seen_names) or (name in hidden_ruuvitags):
            continue
        rt_data.append(
            {
                "name": name,
                "temperature": tag.get("temperature"),
                "humidity": tag.get("humidity"),
                "recorded": tag.get("recorded"),
            }
        )
        seen_names.add(name)

    return {
        "weather-data": weather_data,
        "data": data,
        "rt-data": rt_data,
    }


def slim_weather(raw):
    """Keep only weather/astronomy fields needed for display."""
    if not raw:
        return {
            "ast": {"sunrise": None, "sunset": None},
            "fmi": {"forecast": None},
        }

    ast = raw.get("ast") or {}
    fmi = raw.get("fmi") or {}
    forecast = fmi.get("forecast")
    if forecast:
        wind = forecast.get("wind-direction-str") or {}
        forecast = {
            "time": forecast.get("time"),
            "temperature": forecast.get("temperature"),
            "feels-like": forecast.get("feels-like"),
            "cloudiness": forecast.get("cloudiness"),
            "wind-speed": forecast.get("wind-speed"),
            "precipitation": forecast.get("precipitation"),
            "wind-direction-str": {"short": wind.get("short")},
        }

    return {
        "ast": {
            "sunrise": ast.get("sunrise"),
            "sunset": ast.get("sunset"),
        },
        "fmi": {"forecast": forecast},
    }


def format_local_time():
    """Format the current local time for display (minute resolution)."""
    c_time = time.localtime()
    return (
        f"{c_time.tm_mday}.{c_time.tm_mon}.{c_time.tm_year} "
        f"{c_time.tm_hour:02}:{c_time.tm_min:02}"
    )


def update_time_row(display, time_str, clock_suffix):
    """Update only the clock row, using a cached sunrise / sunset suffix."""
    if clock_suffix:
        display[0].text = f"{time_str}           {clock_suffix}"
    else:
        display[0].text = time_str


def render_weather_rows(display, observation, weather_data, utc_offset_hour, time_str):
    """Render the header clock row and current weather rows.

    Returns the clock row sunrise / sunset suffix for later time-only updates.
    """
    clock_suffix = ""
    if observation["weather-data"]:
        clock_suffix = (
            f"sr {weather_data['ast']['sunrise']} ss {weather_data['ast']['sunset']}"
        )
        display[0].text = f"{time_str}           {clock_suffix}"
    else:
        display[0].text = time_str
        return clock_suffix

    weather = observation["weather-data"]
    w_epoch = _parse_iso_to_epoch(weather["time"]) + utc_offset_hour * 3600
    w_local = time.localtime(w_epoch)
    weather_time_str = f"{w_local.tm_hour:02}:{w_local.tm_min:02}"

    display[1].text = (
        f"Weather ({weather_time_str}): temp {weather['temperature']} \u00b0C, "
        f"feel {weather['feels-like']} \u00b0C,"
    )
    display[2].text = (
        f"clouds {weather['cloudiness']}, wind "
        f"{weather['wind-direction-str']['short']} {weather['wind-speed']} m/s, "
        f"humidity {int(weather['humidity'])} %H"
    )

    return clock_suffix


def render_forecast_rows(display, weather_data, utc_offset_hour):
    """Render forecast rows and return the next row index for following sections."""
    forecast = weather_data["fmi"]["forecast"]
    if not forecast:
        return 1

    try:
        f_epoch = _parse_iso_to_epoch(forecast["time"]) + utc_offset_hour * 3600
        f_local = time.localtime(f_epoch)
    except (ValueError, TypeError):
        return None

    display[3].text = (
        f"Forecast ({f_local.tm_hour:02}:"
        f"{f_local.tm_min:02}): temp {forecast['temperature']} \u00b0C, "
        f"feel {forecast['feels-like']} \u00b0C, "
    )
    display[4].text = (
        f"clouds {forecast['cloudiness']} %, "
        f"wind {forecast['wind-direction-str']['short']} "
        f"{forecast['wind-speed']} m/s, precip "
        f"{forecast['precipitation']} mm"
    )
    return 5


def render_electricity_month_rows(display, elec_data, row):
    """Render electricity monthly stats rows and return the next row index."""
    if "average" not in elec_data and "month-consumption" not in elec_data:
        return row

    month_consumption = elec_data.get("month-consumption")
    month_average = elec_data.get("average")
    month_cost = elec_data.get("month-cost")

    line = "Current month:"
    if month_consumption is not None:
        line += f" consumption {month_consumption} kWh"

    if month_average is not None:
        if month_consumption is not None:
            display[row].text = f"{line},"
            row += 1
            line = f"average price {month_average} c / kWh"
        else:
            line += f" average price {month_average} c / kWh"

    if month_cost is not None:
        if month_consumption is None and month_average is not None:
            # Keep cost on a separate row to avoid very long row strings.
            display[row].text = line
            row += 1
            display[row].text = f"cost {month_cost} €"
            return row + 1

        if month_consumption is not None or month_average is not None:
            line += f", cost {month_cost} €"
        else:
            line += f" cost {month_cost} €"

    display[row].text = line

    return row + 1


def render_electricity_rows(display, elec_data, row):
    """Render electricity pricing rows and return the next row index."""
    if not elec_data:
        return row

    row_text = ""
    if "current" in elec_data:
        current_val = elec_data["current"]
        row_text = (
            f"Elec price: {current_val[0]}:"
            f"{current_val[1]:02} {current_val[2]} c"
        )
    if "next" in elec_data:
        next_val = elec_data["next"]
        next_text = f"{next_val[0]}:{next_val[1]:02} {next_val[2]} c"
        if row_text:
            row_text = f"{row_text}, {next_text}"
        else:
            row_text = f"Elec price: {next_text}"

    display[row].text = row_text
    row += 1
    return render_electricity_month_rows(display, elec_data, row)


def render_observation_rows(display, observation, rt_recorded, row):
    """Render observation summary rows and return the next row index."""
    display[row].text = ""
    row += 1

    display[row].text = rt_recorded or ""
    row += 1
    o_data = observation["data"]

    outside = o_data["outside-temperature"]
    rssi = o_data["beacon-rssi"]
    summary_parts = []
    if outside is not None:
        summary_parts.append(f"Outside temp {outside} \u00b0C")
    if rssi is not None:
        summary_parts.append(f"beacon RSSI {rssi} dBm")
    display[row].text = ", ".join(summary_parts) + ("," if summary_parts else "")

    if o_data["iaqs"] is not None:
        row += 1
        iaqs = o_data["iaqs"]
        co2 = o_data["ruuvi-co2"]
        pm25 = o_data["pm-25"]
        display[row].text = (
            f"air: IAQS {iaqs}, CO\u2082 {co2} ppm, "
            f"PM 2.5 {pm25} \u00b5g/m\u00b3"
        )
    row += 1

    return row


def render_ruuvi_tag_rows(display, observation, row):
    """Render RuuviTag rows and return the next row index.

    Expects observation['rt-data'] already filtered (hidden names removed, deduped).
    """
    if not observation["rt-data"]:
        return row

    for tag in observation["rt-data"]:
        if row + 1 >= DISPLAY_MAX_ROWS:
            display[DISPLAY_MAX_ROWS - 1].text = "RuuviTag rows truncated"
            return DISPLAY_MAX_ROWS
        display[
            row
        ].text = f'RuuviTag "{tag["name"]}": temperature {tag["temperature"]} \u00b0C,'
        display[row + 1].text = f"humidity {tag['humidity']} %H"
        row += 2

    return row


def update_screen(display, data, *, time_update_only=False, clock_suffix=""):
    """Update screen contents.

    data keys: observation, weather_data, elec_data, utc_offset_hour

    Returns the clock row sunrise / sunset suffix for later time-only updates.
    """
    time_str = format_local_time()

    if time_update_only:
        update_time_row(display, time_str, clock_suffix)
        gc.collect()
        return clock_suffix

    observation = data["observation"]
    weather_data = data["weather_data"]
    elec_data = data["elec_data"]
    utc_offset_hour = data["utc_offset_hour"]

    rt_recorded = (
        max(tag["recorded"] for tag in observation["rt-data"])
        if observation["rt-data"]
        else None
    )

    gc.collect()
    clock_suffix = render_weather_rows(
        display, observation, weather_data, utc_offset_hour, time_str
    )
    row = render_forecast_rows(display, weather_data, utc_offset_hour)
    row = render_electricity_rows(display, elec_data, row)
    row = render_observation_rows(display, observation, rt_recorded, row)
    row = render_ruuvi_tag_rows(display, observation, row)
    blank_display_rows(display, row)
    display.show()
    gc.collect()
    return clock_suffix


def fetch_and_slim_display_data(
    wifi,
    token,
    access_token_expiry_time,
    elec_price_metadata,
    hidden_ruuvitags,
):
    """Fetch obs/weather, slim them, and prepare elec display values."""
    elec_data = prepare_elec_data(elec_price_metadata)
    gc.collect()

    token, access_token_expiry_time, obs_raw = get_backend_endpoint_content(
        wifi, "data/latest-obs", token, access_token_expiry_time
    )
    observation = slim_observation(obs_raw, hidden_ruuvitags)
    obs_raw = None
    gc.collect()

    token, access_token_expiry_time, weather_raw = get_backend_endpoint_content(
        wifi, "data/weather", token, access_token_expiry_time
    )
    weather_data = slim_weather(weather_raw)
    weather_raw = None
    gc.collect()

    return (token, access_token_expiry_time, observation, weather_data, elec_data)


def maybe_refresh_elec_prices(
    wifi, token, access_token_expiry_time, elec_price_metadata, utc_offset_hour
):
    """Refresh compact electricity metadata when the cache is stale."""
    fetched = elec_price_metadata["fetched"]
    if fetched and (time.time() - fetched) <= ELEC_PRICE_FETCH_THRESHOLD:
        return token, access_token_expiry_time

    token, access_token_expiry_time, elec_raw = get_backend_endpoint_content(
        wifi,
        "data/elec-data",
        token,
        access_token_expiry_time,
        {"addFees": "true"},
    )
    store_elec_metadata(elec_price_metadata, elec_raw, utc_offset_hour)
    gc.collect()
    return token, access_token_expiry_time


def maybe_update_display(display, screen_data, update_data, clock_minute, clock):
    """Update the full screen or clock row when needed.

    clock is (clock_suffix, last_clock_minute).
    """
    clock_suffix, last_clock_minute = clock
    if update_data:
        clock_suffix = update_screen(
            display, screen_data, time_update_only=False, clock_suffix=clock_suffix
        )
        return clock_suffix, clock_minute
    if last_clock_minute != clock_minute:
        clock_suffix = update_screen(
            display, screen_data, time_update_only=True, clock_suffix=clock_suffix
        )
        return clock_suffix, clock_minute
    return clock_suffix, last_clock_minute


def main():  # noqa: PLR0915
    """Run the main module loop."""
    gc.collect()
    font = bitmap_font.load_font(FONT_PATH)
    gc.collect()

    wifi = connect_to_wlan()
    gc.collect()

    print("Getting current time from backend")
    utc_offset_hour = set_time(wifi, getenv("TIMEZONE"))
    print("Current time set")
    gc.collect()

    display = SimpleTextDisplay(colors=[SimpleTextDisplay.WHITE], font=font)
    del font
    gc.collect()
    init_fetch_done = False
    time_set_seconds_slept = 0
    token = None
    access_token_expiry_time = None
    observation = None
    weather_data = None
    elec_data = None
    elec_price_metadata = {
        "hours": None,
        "latest-hour-key": None,
        "month-price-avg": None,
        "month-consumption": None,
        "month-cost": None,
        "fetched": None,
    }
    hidden_ruuvitags = {
        name for name in (getenv("HIDDEN_RUUVITAG_NAMES") or "").split(",") if name
    }
    screen_data = {
        "observation": None,
        "weather_data": None,
        "elec_data": None,
        "utc_offset_hour": utc_offset_hour,
    }
    clock_suffix = ""
    last_clock_minute = None

    board.DISPLAY.brightness = BACKLIGHT_DEFAULT_VALUE

    while True:
        try:
            if not token or time.time() >= access_token_expiry_time:
                token, access_token_expiry_time = fetch_token(wifi)
                if not token:
                    continue

            if BACKLIGHT_DIMMING_ENABLED:
                adjust_backlight(board.DISPLAY)

            now = time.localtime()
            update_data = (
                now.tm_min % DATA_STORAGE_INTERVAL == 0
                and now.tm_sec == DATA_UPDATE_SECOND
            )
            clock_minute = (now.tm_hour, now.tm_min)

            # Defer elec JSON parse so it never shares a loop turn with the
            # 4 minute observation / weather fetch and full redraw
            if not update_data:
                token, access_token_expiry_time = maybe_refresh_elec_prices(
                    wifi,
                    token,
                    access_token_expiry_time,
                    elec_price_metadata,
                    utc_offset_hour,
                )

            if update_data or not init_fetch_done:
                observation = None
                weather_data = None
                elec_data = None
                gc.collect()
                (
                    token,
                    access_token_expiry_time,
                    observation,
                    weather_data,
                    elec_data,
                ) = fetch_and_slim_display_data(
                    wifi,
                    token,
                    access_token_expiry_time,
                    elec_price_metadata,
                    hidden_ruuvitags,
                )
                screen_data["observation"] = observation
                screen_data["weather_data"] = weather_data
                screen_data["elec_data"] = elec_data
                if not init_fetch_done:
                    init_fetch_done = True
                    update_data = True

            clock_suffix, last_clock_minute = maybe_update_display(
                display,
                screen_data,
                update_data,
                clock_minute,
                (clock_suffix, last_clock_minute),
            )

            if time_set_seconds_slept >= TIME_SET_SLEEP_TIME:
                set_time(wifi, getenv("TIMEZONE"))
                time_set_seconds_slept = 0

            time_set_seconds_slept += 1
            time.sleep(1)
        except MemoryError:
            print("MemoryError: reloading")
            supervisor.reload()


main()
