
-- Tables definitions for PostgreSQL

-- Observations table
CREATE TABLE observations (
       id SERIAL PRIMARY KEY,
       recorded TIMESTAMP WITH TIME ZONE NOT NULL,
       temperature REAL NOT NULL,
       brightness SMALLINT NOT NULL
);

-- Beacons table
CREATE TABLE beacons (
       id SERIAL PRIMARY KEY,
       obs_id INTEGER NOT NULL REFERENCES observations (id) ON DELETE CASCADE,
       mac_address CHAR(17) NOT NULL,
       rssi SMALLINT NOT NULL
);

-- FMI weather data table
CREATE TABLE weather_data (
       id SERIAL PRIMARY KEY,
       obs_id INTEGER NOT NULL REFERENCES observations (id) ON DELETE CASCADE,
       time TIMESTAMP WITH TIME ZONE NOT NULL,
       temperature REAL NOT NULL,
       cloudiness SMALLINT NOT NULL
);

-- User table
CREATE TABLE users (
    user_id SERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    pw_hash VARCHAR(250) NOT NULL,
    saved_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
 );

-- Yubikey ID table
CREATE TABLE yubikeys (
    key_id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users (user_id) ON DELETE CASCADE,
    yubikey_id VARCHAR(32) NOT NULL UNIQUE
);