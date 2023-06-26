FROM python:3.11-alpine as python
ENV PYTHONUNBUFFERED=true
RUN apk update && apk upgrade

WORKDIR /usr/src/app
COPY config.json store_price.py poetry.lock ./

LABEL org.opencontainers.image.authors="tero.paloheimo@iki.fi"

FROM python as poetry
ENV POETRY_VIRTUALENVS_IN_PROJECT=true
ENV PATH="/root/.local/bin:${PATH}"
RUN apk add --no-cache curl

RUN curl -sSL https://install.python-poetry.org | python3 -
COPY . ./
RUN poetry install --no-interaction --no-ansi -vv

FROM python as runtime
ENV PATH="/usr/src/app/.venv/bin:${PATH}"
COPY --from=poetry /usr/src/app /usr/src/app

ENTRYPOINT [ "python", "./store_price.py" ]
