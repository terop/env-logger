# Electricity consumption storage

A script for storing electricity consumption data fetched from Caruna's API
into a PostgreSQL database.

## Running

[uv](https://github.com/astral-sh/uv) is required so install it first unless it is
already installed.
Then run `uv sync --frozen` to install required dependencies. A sample configuration
file is available at `config.json.sample`, copy it to `config.json` and modify
accordingly. Finally run the script itself located in the `electricity_consumption`
directory with `uv run python store_consumption.py`.
The configuration file can also be provided by using the `--config` flag.
