-- Tables definitions for PostgreSQL

-- Observations table
CREATE TABLE observations (
       id SERIAL PRIMARY KEY,
       recorded TIMESTAMP WITH TIME ZONE NOT NULL,
       brightness SMALLINT NOT NULL,
       tb_image_name VARCHAR(40),
       outside_temperature REAL
);

-- Beacons table
CREATE TABLE beacons (
       id SERIAL PRIMARY KEY,
       obs_id INTEGER NOT NULL REFERENCES observations (id) ON DELETE CASCADE,
       mac_address CHAR(17) NOT NULL,
       rssi SMALLINT NOT NULL,
       -- allow NULL values as all beacons do not support battery level reporting
       battery_level SMALLINT
);

-- FMI weather data table
CREATE TABLE weather_data (
       id SERIAL PRIMARY KEY,
       obs_id INTEGER NOT NULL REFERENCES observations (id) ON DELETE CASCADE,
       time TIMESTAMP WITH TIME ZONE NOT NULL,
       temperature REAL,
       cloudiness SMALLINT,
       wind_speed REAL
);

-- RuuviTag beacon observation data
CREATE TABLE ruuvitag_observations (
       id SERIAL PRIMARY KEY,
       recorded TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
       location VARCHAR(15) NOT NULL,
       temperature REAL NOT NULL,
       -- allow NULL values as not all RuuviTags have a pressure sensor
       pressure REAL,
       humidity REAL NOT NULL,
       battery_voltage REAL NOT NULL,
       rssi INTEGER NOT NULL
);

-- User table
CREATE TABLE users (
       user_id SERIAL PRIMARY KEY,
       username VARCHAR(100) NOT NULL UNIQUE,
       pw_hash VARCHAR(250),
       saved_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- WebAuthn authenticator table
CREATE TABLE webauthn_authenticators (
       authn_id SERIAL PRIMARY KEY,
       user_id INTEGER REFERENCES users (user_id) ON DELETE CASCADE,
       name VARCHAR(40),
       counter INTEGER NOT NULL,
       attested_credential VARCHAR(500) NOT NULL,
       attestation_statement VARCHAR(2000) NOT NULL
);

-- Electricity price table
CREATE TABLE electricity_price (
       id SERIAL PRIMARY KEY,
       start_time TIMESTAMP WITH TIME ZONE NOT NULL,
       price REAL NOT NULL
);

-- Electricity consumption table
CREATE TABLE electricity_consumption (
       id SERIAL PRIMARY KEY,
       time TIMESTAMP WITH TIME ZONE NOT NULL,
       consumption REAL NOT NULL
);
