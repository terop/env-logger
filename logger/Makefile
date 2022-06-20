
CC=gcc
CFLAGS=-std=c99 -O2 -Wall -Wextra -pedantic

# Dependencies on Debian:
# sudo apt install build-essential libbluetooth-dev

all: scan

scan: ble_beacon_scan.c
	$(CC) $(CFLAGS) -D_XOPEN_SOURCE -o ble_beacon_scan ble_beacon_scan.c -lbluetooth

clean:
	rm -f ble_beacon_scan
