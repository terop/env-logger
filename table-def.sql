
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
