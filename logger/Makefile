
CC=gcc
CFLAGS=-Wall -Wextra

# Dependencies on Debian:
# sudo apt install build-essential libbluetooth-dev

all: scan

scan: ble_beacon_scan.c
	$(CC) $(CFLAGS) -lbluetooth -o ble_beacon_scan ble_beacon_scan.c

clean:
	rm ble_beacon_scan
