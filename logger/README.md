# logger

## BLE beacon scanning

Compile `ble_beacon_scan` program with `make`. Compilation dependencies on Debian are `build-essential libbluetooth-dev`.

Extra capabilities for `ble_beacon_scan` must be set to run the program as a non-root user:
`sudo setcap 'cap_net_raw,cap_net_admin+eip' ble_beacon_scan`

## RuuviTag scanning

[ruuvitag-sensor](https://github.com/ttu/ruuvitag-sensor) is used for RuuviTag scanning.
Passwordless `sudo` access is needed for the `hciconfig`, `hcidump` and `hcitool` commands.
