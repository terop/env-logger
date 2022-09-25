# Electricity price storing

This directory contains scripts to store the Nordpool spot price for Finland
into a PostgreSQL database.

## Running

`pipenv` is required so install it first unless it is already installed.
Then run `pipenv install` to install required dependencies. A sample configuration
file is available at `config.json.sample`, copy it to `config.json` and modify
accordingly. Finally run the script itself with `pipenv run python store_price.py`.
