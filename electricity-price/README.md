# Electricity price storage

This directory contains a script to store the Nord Pool spot prices for supported
price areas into a PostgreSQL database. The prices is fetched from the Nord Pool
API.

## Running

[uv](https://github.com/astral-sh/uv) is required so install it first unless it is
already installed.
Then run `uv sync --frozen` to install required dependencies. A sample configuration
file is available at `config.json.sample`, copy it to `config.json` and modify
accordingly. Finally run the script itself with `uv run python store_price.py`.
The configuration file can also be provided by using the `--config` flag.

By default the script fetches the prices for next day. However prices for an
arbitrary day can be fetched with the `--date` flag.
