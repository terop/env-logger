#!/bin/sh
# 2016-2017 Tero Paloheimo

# This scripts backs up a PostgreSQL database and uploads the backup to a user
# specified remote host.
# SSH key authentication without a password MUST be in place before running
# this script.

if [ $# -ne 1 ]; then
    echo "Usage: $0 <database name>"
    exit 1
fi
database_name=$1

# Change these values as needed BEFORE running!
target_host=lakka.kapsi.fi
target_directory=/home/users/tpalohei/siilo/"$database_name"_backup
target_user=tpalohei
backup_file_name="$database_name"_$(date -Iseconds).sql

echo "Dumping database $database_name to file $backup_file_name"

if [ $(pg_dump -a -f "$backup_file_name" "$database_name") ]; then
    echo "pg_dump failed, deleting file. Exiting."
    rm "$backup_file_name"
    return 1
fi

xz -z "./$backup_file_name"
backup_file_name="$backup_file_name.xz"

echo "Uploading file to $target_host:$target_directory"
if [ $(scp "./$backup_file_name" "$target_user@$target_host:$target_directory/") ]; then
    echo "File upload failed."
else
    echo "File upload succeeded!"
fi
rm ./"$backup_file_name"
