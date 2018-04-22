
CC=gcc
CFLAGS=-Wall -Wextra

all: scan

scan: ble_beacon_scan.c
	$(CC) $(CFLAGS) -lbluetooth -o ble_beacon_scan ble_beacon_scan.c

clean:
	rm ble_beacon_scan
