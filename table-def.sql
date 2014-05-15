
-- Tables definitions for PostgreSQL
CREATE TABLE observations (
	id SERIAL PRIMARY KEY,
	recorded TIMESTAMP WITH TIME ZONE NOT NULL,
	temperature REAL NOT NULL,
	brightness SMALLINT NOT NULL
);
