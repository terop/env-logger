#!/bin/sh

# This a script for updating the local env-logger database from a backup snapshot.

if [ $# -ne 2 ]; then
    echo "Usage: $0 <DB name> <DB snapshot name>"
    echo "Example: $0 env_logger db_snapshot.sql"
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
psql "${db_name}" < "${snapshot_name}"
