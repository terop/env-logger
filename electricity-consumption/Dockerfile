LABEL maintainer="tero.paloheimo@iki.fi"

FROM python:3.11-alpine as python
ENV PYTHONUNBUFFERED=true
RUN apk update && apk upgrade

WORKDIR /usr/src/app
COPY poetry.lock .
COPY electricity_consumption/config.json electricity_consumption/store_consumption.py \
    ./electricity_consumption

FROM python as poetry
ENV POETRY_HOME=/opt/poetry
ENV POETRY_VIRTUALENVS_IN_PROJECT=true
ENV PATH="${POETRY_HOME}/bin:${PATH}"
RUN python -c 'from urllib.request import urlopen; print(urlopen("https://install.python-poetry.org").read().decode())' | python -
COPY . ./
RUN poetry install --no-interaction --no-ansi -vv

FROM python as runtime
ENV PATH="/usr/src/app/.venv/bin:${PATH}"
COPY --from=poetry /usr/src/app /usr/src/app

WORKDIR /usr/src/app/electricity_consumption
ENTRYPOINT [ "python", "./store_consumption.py" ]
