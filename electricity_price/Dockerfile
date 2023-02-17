FROM python:3.10-slim
LABEL maintainer="tero.paloheimo@iki.fi"

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade \
    && apt-get clean

WORKDIR /usr/src/app
COPY config.json store_price.py Pipfile Pipfile.lock .

RUN pip install --upgrade pip && \
    pip install pipenv && pipenv install --system --deploy
ENTRYPOINT [ "python", "./store_price.py" ]
