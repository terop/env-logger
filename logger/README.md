# logger

## BLE beacon scanning

Compile `ble_beacon_scan` program with `make`. Compilation dependencies on Debian are `build-essential libbluetooth-dev`.

Extra capabilities for `ble_beacon_scan` must be set to run the program as a non-root user:
`sudo setcap 'cap_net_raw,cap_net_admin+eip' ble_beacon_scan`

## RuuviTag scanning

[Bluewalker](https://gitlab.com/jtaimisto/bluewalker) is needed for RuuviTag scanning.
Install it with `go get gitlab.com/jtaimisto/bluewalker`. The binary must be placed in
`PATH`. Passwordless sudo access is needed for the `bluewalker` and `hciconfig` commands.
