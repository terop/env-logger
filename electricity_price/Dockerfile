LABEL maintainer="tero.paloheimo@iki.fi"

FROM python:3.10-slim as python
ENV PYTHONUNBUFFERED=true
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get -y upgrade && \
    apt-get clean

WORKDIR /usr/src/app
COPY config.json store_price.py pyproject.toml poetry.lock .

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

ENTRYPOINT [ "python", "./store_price.py" ]
