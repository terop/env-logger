#!/bin/sh
# 2017-2018 Tero Paloheimo

# This is a script for backing up an InfluxDB database and uploads the backup
# to a user specified remote host.
# SSH key authentication without a password MUST be in place before running
# this script.

# Change these values as needed BEFORE running!
target_host=example.com
target_user=myuser
target_directory=/home/mydir

usage() {
    cat <<EOF >&2
A script for backing up InfluxDB metastore and database data
and uploading the backup file to a remote machine.

Usage: $0 [-h] [-l] <database name>
Flags:
-h    Print this message and exit
-l    Local mode: do not upload backup file
EOF
}

local_mode=0
while getopts 'lh' OPTION; do
  case "${OPTION}" in
    l)
        local_mode=1
        ;;
    h)
        usage
        exit 1
        ;;
    *)
        echo "Invalid option: ${OPTION}"
        exit 1
  esac
done
shift "$((OPTIND - 1))"

db_name=$1
if [ -z "${db_name}" ]; then
    echo 'Database name must be provided, exiting.' >&2
    exit 1
fi

backup_dir="influx_backup_${db_name}"
backup_file_name="influxdb_${db_name}_$(date -Iminutes).tar"

cd /tmp || exit 1
mkdir "${backup_dir}"

echo "Backing up database ${db_name}"
influxd backup -portable -db ${db_name} ${backup_dir} 1>/dev/null
if [ $? -ne 0 ]; then
    echo "Error: database ${db_name} backup failed, stopping." >&2
    rm -rf "${backup_dir}"
    exit 1
fi

if [ $(tar -cf "./${backup_file_name}" ${backup_dir}) ]; then
    echo "Error: backup directory compression failed, stopping." >&2
    rm -rf "${backup_dir}"
    exit 1
fi
rm -rf "${backup_dir}"

xz -z -T 0 "./${backup_file_name}"
backup_file_name="${backup_file_name}.xz"

if [ ${local_mode} -eq 1 ]; then
    echo "Backup file: /tmp/${backup_file_name}"
    exit 0
fi

echo "Uploading file to ${target_host}:${target_directory}"
if [ $(scp "./${backup_file_name}" "${target_user}@${target_host}:${target_directory}/") ]; then
    echo "File upload failed."
else
    echo "File upload succeeded!"
fi
rm "./${backup_file_name}"
