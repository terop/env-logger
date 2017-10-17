#!/bin/sh
# 2017 Tero Paloheimo

# This is a script for backing up an InfluxDB database and uploads the backup
# to a user specified remote host.
# SSH key authentication without a password MUST be in place before running
# this script.

# Change these values as needed BEFORE running!
target_host=example.com
target_user=myuser
target_directory=/home/mydir

usage() {
    echo "A script for backing up InfluxDB metastore and database data."
    echo "Example: $0 env_logger"
}

if [ $# -eq 0 ] || [ "$1" = '-h' ]; then
    usage
    exit 1
fi

db_name=$1
backup_dir="/tmp/influx_backup_${db_name}"
backup_file_name="influxdb_${db_name}_$(date -Iminutes).tar.xz"

mkdir ${backup_dir}
echo "Backing up metastore"
influxd backup ${backup_dir}
if [ $? -ne 0 ]; then
    echo "Error: metastore backup failed, stopping." >&2
    rm -rf ${backup_dir}
    exit 1
fi

echo "Backing up database ${db_name}"
influxd backup -database ${db_name} ${backup_dir}
if [ $? -ne 0 ]; then
    echo "Error: database ${db_name} backup failed, stopping." >&2
    rm -rf ${backup_dir}
    exit 1
fi

cd /tmp || exit 1
tar -cJf "./${backup_file_name}" ${backup_dir}
if [ $? -ne 0 ]; then
    echo "Error: backup directory compression failed, stopping." >&2
    rm -rf ${backup_dir}
    exit 1
fi
rm -rf ${backup_dir}

echo "Uploading file to ${target_host}:${target_directory}"
if [ $(scp "./${backup_file_name}" "${target_user}@${target_host}:${target_directory}/") ]; then
    echo "File upload failed."
else
    echo "File upload succeeded!"
fi
rm "./${backup_file_name}"
