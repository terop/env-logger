#!/bin/sh

# This a script for restoring a local database from a backup snapshot.

set -e

if [ $# -ne 2 ]; then
    cat <<EOF
This a script for restoring a local database from a backup snapshot.

Usage: $0 <DB name> <DB snapshot name>
Example: $0 env_logger db_snapshot.sql
EOF
    exit 1
fi

db_name=$1
snapshot_name=$2

file_out=$(file -ib ${snapshot_name})
if [ $(echo "${file_out}"|grep -c xz) -eq 1 ]; then
    echo "Compressed snapshot, decompressing before restore"
    unxz ${snapshot_name}
    snapshot_name=$(echo ${snapshot_name}|sed 's/.xz//')
fi

# The environment logger database requires special steps before a backup
# can be restored
if [ ${db_name} = "env_logger" ]; then
    echo "Truncating tables"
    psql "${db_name}" <<EOF
TRUNCATE TABLE users CASCADE;
TRUNCATE TABLE observations CASCADE;
EOF
fi

echo "Adding new values"
psql "${db_name}" < "${snapshot_name}"
