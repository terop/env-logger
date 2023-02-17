FROM python:3.10-alpine
LABEL maintainer="tero.paloheimo@iki.fi"

RUN apk update && apk upgrade

WORKDIR /usr/src/app
COPY config.json store_price.py Pipfile Pipfile.lock .

RUN pip install --upgrade pip && \
    pip install pipenv && pipenv install --system --deploy
ENTRYPOINT [ "python", "./store_price.py" ]
