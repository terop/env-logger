#!/bin/sh

# This a script for restoring the local env-logger database from a backup snapshot.

set -e

if [ $# -ne 2 ]; then
    cat <<EOF
This a script for restoring the local env-logger database from a backup snapshot.

Usage: $0 <DB name> <DB snapshot name>
Example: $0 env_logger db_snapshot.sql
EOF
    exit 1
fi

db_name=$1
snapshot_name=$2

echo "Truncating tables"
psql "${db_name}" <<EOF
TRUNCATE TABLE users CASCADE;
TRUNCATE TABLE observations CASCADE;
TRUNCATE TABLE yardcam_images;
EOF

echo "Adding new values"
# Pressure data has not been collected from the beginning and thus contains
# NULL values causing restore to fail
psql "${db_name}" -c 'ALTER TABLE weather_data ALTER pressure DROP NOT NULL;'
psql "${db_name}" < "${snapshot_name}"
