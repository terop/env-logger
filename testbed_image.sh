#!/bin/sh

# A script for uploading a FMI Testbed image to a remote server.
#
# Copyright (C) 2017 Tero Paloheimo

# IMPORTANT! Change these values before running!
ssh_con_settings="user@host"
image_dir_basename="/home/user/some/location"

# Ensure correct base directory
# ssh $ssh_con_settings 'mkdir $image_dir_basename'

image_name=$(python3 testbed_image.py)
if [ -z "$image_name" ]; then
    echo "$(date -Iminutes): Failed to download Testbed image." >&2
    exit 1
fi

day_directory="$image_dir_basename/$(date +%Y-%m-%d)"
# Make directory for each day
ssh $ssh_con_settings "ls $day_directory" > /dev/null
if [ $? -ne 0 ]; then
    ssh $ssh_con_settings "mkdir $day_directory"
fi

scp ./"$image_name" "$ssh_con_settings:$day_directory/$image_name"
if [ $? -ne 0 ]; then
    echo "$(date -Iminutes): Image upload failed, exiting." >&2
fi
rm "$image_name"

# Add image name to env-logger database
# auth_code=somevalue
# curl --data-urlencode "image-name=$(basename $filename)" --data-urlencode "code=$auth_code" https://example.com/env-logger/tb-image
