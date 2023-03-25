# Electricity price storage

This directory contains scripts to store the Nordpool spot price for Finland
into a PostgreSQL database. The price is fetched from the
[ENTSO-E](https://transparency.entsoe.eu/) transparency platform using their API
so an ENTSO-E API key is needed.

## Running

[Poetry](https://python-poetry.org/) is required so install it first unless it is
already installed.
Then run `poetry install` to install required dependencies. A sample configuration
file is available at `config.json.sample`, copy it to `config.json` and modify
accordingly. Finally run the script itself with `poetry run python store_price.py`.
The configuration file can also be provided by using the `--config` flag.

By default the script fetches the price for next day. However the price for the
current day can be fetched with the `--current-day` flag.
