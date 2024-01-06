FROM python:3.12-alpine as python
ENV PYTHONUNBUFFERED=true
RUN apk update && apk upgrade && python3 -m pip install --upgrade pip

WORKDIR /usr/src/app
COPY monitor.cfg monitor.py poetry.lock ./

LABEL org.opencontainers.image.authors="tero.paloheimo@iki.fi"

FROM python as poetry
ENV POETRY_VIRTUALENVS_IN_PROJECT=true
ENV PATH="/root/.local/bin:${PATH}"
RUN apk add --no-cache curl

RUN curl -sSL https://install.python-poetry.org | python3 -
COPY pyproject.toml poetry.lock ./
RUN poetry install --no-interaction --no-ansi -vv

FROM python as runtime
ENV PATH="/usr/src/app/.venv/bin:${PATH}"
COPY --from=poetry /usr/src/app /usr/src/app

ENTRYPOINT [ "python", "./monitor.py" ]
