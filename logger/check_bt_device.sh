#!/bin/sh

# A script for check the existence of one more Bluetooth devices and
# rebooting the device if none are found.
# Passwordless 'reboot' command execution right is needed.

device_count=$(hcitool dev|grep -c 'hci')

if [ ${device_count} -eq 0 ]; then
    echo 'Device(s) gone, reboot needed'
    sudo reboot
fi
