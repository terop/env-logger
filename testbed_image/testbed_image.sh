#!/bin/sh

# shellcheck disable=SC2154

# A script for uploading a FMI Testbed image to a remote server.
#
# Copyright (C) 2017-2019 Tero Paloheimo

# Check configuration file existence
conf_file='testbed_image_conf.sh'
if [ ! -e ${conf_file} ]; then
    echo "Configuration file '${conf_file}' not found, exiting" >&2
    exit 1
fi
# shellcheck disable=SC1090
. ./${conf_file}

ssh_con_string="${target_user}@${target_host}"

if [ -z "$(command -v uv)" ]; then
    echo "uv is not in PATH, exiting" >&2
    exit 1
fi

if [ -z "$(command -v jq)" ]; then
    echo "jq is not in PATH, exiting" >&2
    exit 1
fi

image_name=$(uv run python testbed_image.py)
if [ -z "${image_name}" ]; then
    echo "$(date -Iminutes): failed to download Testbed image" >&2
    exit 1
fi

day_directory="${target_directory}/$(date +%Y-%m-%d)"
# Make directory for each day
ssh "${ssh_con_string}" "ls ${day_directory}" >/dev/null
if [ $? -ne 0 ]; then
    ssh "${ssh_con_string}" "mkdir ${day_directory}"
fi

scp ./"${image_name}" "${ssh_con_string}:${day_directory}/${image_name}" 1>/dev/null
if [ $? -ne 0 ]; then
    echo "$(date -Iminutes): image upload failed, exiting" >&2
    rm "${image_name}"
    exit 1
fi
rm "${image_name}"

# Add image name to env-logger database
if [ "${env_logger_integration}" -ne 0 ]; then
    access_token=$(curl -s --location "${oid_token_endpoint}" \
                        --header 'Content-Type: application/x-www-form-urlencoded' \
                        --data-urlencode 'grant_type=client_credentials' \
                        --data-urlencode "client_id=${oid_client_id}" \
                        --data-urlencode "client_secret=${oid_client_secret}"|jq '.access_token'|tr -d '"')
    # shellcheck disable=SC2181
    if [ $? -ne 0 ]; then
        echo "$(date -Iminutes): error: access token fetch failed, RC ${?}, exiting" >&2
        exit 1
    fi

    if [ -z "${access_token}" ]; then
        echo "$(date -Iminutes): error: invalid access token, exiting" >&2
        exit 1
    fi

    curl -s -S -H "Bearer: ${access_token}" --data-urlencode "name=${image_name}" \
         "${env_logger_url}/obs/tb-image" 1>/dev/null
fi
