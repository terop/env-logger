#!/bin/sh
# 2016-2018 Tero Paloheimo

# This scripts backs up a PostgreSQL database and uploads the backup to a user
# specified remote host.
# SSH key authentication without a password MUST be in place before running
# this script.

set -e

usage() {
    cat >&2 <<EOF
This scripts backs up a PostgreSQL database and uploads the backup to a remote
host.
Settings are read from a file named 'backup_conf.sh' which must exist in the
same directory as this file.

Usage: $0 [-h] [-l] <database name>
Flags:
-h    Print this message and exit
-l    Do a local backup (do not upload backup file)
EOF
}

local_backup=0
db_name=

while getopts 'lh' OPTION; do
  case "${OPTION}" in
    l)
        local_backup=1
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

conf_file=backup_conf.sh
if [ ! -e ${conf_file} ]; then
    echo "Error: configuration file '${conf_file}' not found, exiting." >&2
    exit 1
fi
. ./${conf_file}

backup_file_name="${db_name}_$(date -Iseconds).sql"

echo "Dumping database ${db_name} to file ${backup_file_name}"

if [ $(pg_dump -a -f "${backup_file_name}" "${db_name}") ]; then
    echo "pg_dump failed, deleting file. Exiting."
    rm "${backup_file_name}"
    return 1
fi

xz -z -T 0 "./${backup_file_name}"
backup_file_name="${backup_file_name}.xz"

if [ $local_backup -eq 1 ]; then
    echo "Backup file: ${backup_file_name}"
    exit 0
fi

echo "Uploading ${backup_file_name} to ${target_host}:${target_directory}"
scp "./${backup_file_name}" "${target_user}@${target_host}:${target_directory}" >/dev/null
if [ $? -eq 0 ]; then
    echo "File upload succeeded!"
else
    echo "File upload failed."
fi
rm ./"${backup_file_name}"
