FROM python:3.10-alpine
LABEL maintainer="tero.paloheimo@iki.fi"

WORKDIR /usr/src/app
COPY monitor.cfg monitor.py Pipfile Pipfile.lock .

RUN pip install --upgrade pip && \
    pip install pipenv && pipenv install --system --deploy
CMD [ "python", "./monitor.py" ]
