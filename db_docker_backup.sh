#!/bin/sh
# Tero Paloheimo 2016

# This scripts backs up a database from a Docker container running PostgreSQL
# and uploads it to a user specified remote host.
# SSH key authentication without a password MUST be in place before running
# this script.

if [ $# -ne 2 ]; then
    echo "Usage: $0 <database container name> <database name>"
    exit 1
fi
container_name=$1
database_name=$2

# Change these values as needed BEFORE running!
# Also the target directory must exist on the target host
target_host=lakka.kapsi.fi
target_directory=/home/users/tpalohei/siilo/"$database_name"_backup
target_user=tpalohei
backup_file_name="$database_name"_$(date -Iseconds).sql

echo "Dumping database to file $backup_file_name inside $container_name"
docker exec -i "$container_name" pg_dump -U $target_user -a -f "$backup_file_name" "$database_name"
if [ $? -ne 0 ]; then
    echo "pg_dump failed, deleting file."
    docker exec -i "$container_name" ls "$backup_file_name"
    if [ $? -eq 0 ]; then
        docker exec -i "$container_name" rm "$backup_file_name"
    fi
    return 1
fi

echo "Copying $backup_file_name to host"
docker cp "$container_name:$backup_file_name" .
if [ $? -ne 0 ]; then
    echo "File copy to the host failed."
    docker exec -i "$container_name" rm "$backup_file_name"
    exit 1
fi
docker exec -i "$container_name" rm "$backup_file_name"

echo "Starting compression of file $backup_file_name at $(date)"
xz -z "./$backup_file_name"
backup_file_name="$backup_file_name.xz"
echo "Compression completed at $(date)"

echo "Uploading file to $target_host:$target_directory"
scp "./$backup_file_name" "$target_user@$target_host:$target_directory/"
if [ $? -ne 0 ]; then
    echo "File upload failed."
else
    echo "File upload succeeded!"
fi
rm "./$backup_file_name"
