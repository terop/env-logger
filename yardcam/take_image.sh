#!/bin/sh
# 2020 (C) Tero Paloheimo

# Script for taking an image and uploading it to a remote host.
# Requirements
# The following packages need to be installed: uvcdynctrl fswebcam

set -e

usage() {
    cat >&2 <<EOF
This script takes an image from a webcam with fswebcam and uploads it to a remote host.
NOTE! The image is removed after uploading.

Settings are read from a file named 'take_image_conf.sh' which must exist in the
same directory as this file.

Usage: $0 [-h] [-f <font file name>] <camera device>
Flags:
-h    Print this message and exit
EOF
}

while getopts 'f:h' OPTION; do
    case "${OPTION}" in
    h)
        usage
        exit 1
        ;;
    f)
        font_file=${OPTARG}
        ;;
    *)
        echo "Invalid option: ${OPTION}"
        exit 1
  esac
done
shift "$((OPTIND - 1))"

camera_device=$1
if [ ! -e "${camera_device}" ]; then
    echo 'Camera device must exist, exiting.' >&2
    exit 1
fi

# Check configuration file existence
conf_file='take_image_conf.sh'
if [ ! -e ${conf_file} ]; then
    echo "Configuration file '${conf_file}' not found, exiting." >&2
    exit 1
fi
. ./${conf_file}

disable_led() {
    # Disable the LED of the camera
    echo "Disabling LED of camera ${camera_device}"
    cam=$(echo "${camera_device}"|sed 's|/dev/||')
    uvcdynctrl -d ${cam} -s 'LED1 Mode' 0 2>/dev/null
}

take_image() {
    font_arg=
    if [ "${font_file}" ]; then
        font_arg="--font ${font_file}"
    fi

    # Remove old fswebcam log file before next run
    rm -f fswebcam.log

    image_filename="yc-$(date -Iminutes).jpg"
    echo "Taking image and saving it to file ${image_filename}"
    fswebcam -d "${camera_device}" --log file:fswebcam.log -r 1280x720 -S 4 ${font_arg} \
             --jpeg 95 --timestamp "%d-%m-%Y %H:%M (%Z)" --top-banner --save ${image_filename}
    if [ $? -ne 0 ]; then
        echo "fswebcam command failed with rc $?"
        exit 1
    fi
}

upload_image() {
    day_directory="${target_directory}/$(date +%Y-%m-%d)"
    conn_string="${target_user}@${target_host}"

    ssh ${conn_string} "mkdir -p ${day_directory}"

    echo "Uploading image ${image_filename}"
    scp ./"${image_filename}" "${conn_string}:${day_directory}" 1>/dev/null
    if [ $? -ne 0 ]; then
        echo 'Image upload failed, exiting.' >&2
        rm "${image_filename}"
        exit 1
    fi
    rm "${image_filename}"

    # Add image name to env-logger database
    if [ ${env_logger_integration} -ne 0 ]; then
        curl -s -S --data-urlencode "image-name=${image_filename}" --data-urlencode "code=${auth_code}" \
             "${env_logger_url}/yc-image"
    fi
}

disable_led
take_image
upload_image
