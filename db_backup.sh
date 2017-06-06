#!/bin/sh
# 2016-2017 Tero Paloheimo

# This scripts backs up a PostgreSQL database and uploads the backup to a user
# specified remote host.
# SSH key authentication without a password MUST be in place before running
# this script.

# Change these values as needed BEFORE running!
target_host=lakka.kapsi.fi
target_user=tpalohei
target_directory=/home/users/tpalohei/siilo

usage() {
    echo "Usage: $(basename $0) [-l] <database name>"
    echo "Flags:"
    echo "-l    Do a local backup (do not upload backup file)"
    exit 1
}

local_backup=0
database_name=$1

if [ $# -eq 2 ]; then
    local_backup=1
    database_name=$2
elif [ $# -eq 1 ]; then
    database_name=$1
else
    usage
fi
target_directory="$target_directory/${database_name}_backup"
backup_file_name="${database_name}_$(date -Iseconds).sql"

echo "Dumping database $database_name to file $backup_file_name"

if [ $(pg_dump -a -f "$backup_file_name" "$database_name") ]; then
    echo "pg_dump failed, deleting file. Exiting."
    rm "$backup_file_name"
    return 1
fi

xz -z "./$backup_file_name"
backup_file_name="$backup_file_name.xz"

if [ $local_backup -eq 1 ]; then
    echo "Backup file: $backup_file_name"
    exit 0
fi

echo "Uploading file to $target_host:$target_directory"
if [ $(scp "./$backup_file_name" "$target_user@$target_host:$target_directory/") ]; then
    echo "File upload failed."
else
    echo "File upload succeeded!"
fi
rm ./"$backup_file_name"
