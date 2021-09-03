
CC=gcc
CFLAGS=-O2 -Wall -Wextra

# Dependencies on Debian:
# sudo apt install build-essential libbluetooth-dev

all: scan

scan: ble_beacon_scan.c
	$(CC) $(CFLAGS) -lbluetooth -o ble_beacon_scan ble_beacon_scan.c

clean:
	rm -f ble_beacon_scan
