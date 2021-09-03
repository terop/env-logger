/*
 * Intel Edison Playground
 *  Copyright (c) 2015 Damian Kołakowski. All rights reserved.
 *  Copyright (c) 2017 Tero Paloheimo
 */

/* Known issue:
 * If there are no beacons within range, the program will not terminate even
 * if the -t option is specified.
 */

#include <stdlib.h>
#include <errno.h>
#include <signal.h>
#include <unistd.h>
#include <time.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <bluetooth/bluetooth.h>
#include <bluetooth/hci.h>
#include <bluetooth/hci_lib.h>

static volatile int signal_received = 0;

static void sigint_handler(int sig)
{
    signal_received = sig;
}

struct hci_request ble_hci_request(uint16_t ocf, int clen, void *status, void *cparam)
{
    struct hci_request rq;
    memset(&rq, 0, sizeof(rq));
    rq.ogf = OGF_LE_CTL;
    rq.ocf = ocf;
    rq.cparam = cparam;
    rq.clen = clen;
    rq.rparam = status;
    rq.rlen = 1;
    return rq;
}

int le_scan_enable(int device, uint8_t enable)
{
    le_set_scan_enable_cp scan_cp;
    memset(&scan_cp, 0, sizeof(scan_cp));
    scan_cp.enable = enable;
    scan_cp.filter_dup = 0x00; /* Filtering disabled */
    uint8_t status;
    int ret;

    struct hci_request request = ble_hci_request(OCF_LE_SET_SCAN_ENABLE, LE_SET_SCAN_ENABLE_CP_SIZE, &status, &scan_cp);

    ret = hci_send_req(device, &request, 1000);
    if (ret < 0) {
        hci_close_dev(device);
        if (enable == 0x01) {
            perror("Failed to enable scan");
        } else if (enable == 0x00) {
            perror("Failed to disable scan");
        }
        return -1;
    }
    return 0;
}

int le_set_scan_parameters(int device)
{
    uint8_t status;
    int ret;

    le_set_scan_parameters_cp scan_params_cp;
    memset(&scan_params_cp, 0, sizeof(scan_params_cp));
    scan_params_cp.type = 0x00;
    scan_params_cp.interval = htobs(0x0010);
    scan_params_cp.window = htobs(0x0010);
    scan_params_cp.own_bdaddr_type = 0x00; /* Public device address (default) */
    scan_params_cp.filter = 0x00; /* Accept all */

    struct hci_request scan_params_rq = ble_hci_request(OCF_LE_SET_SCAN_PARAMETERS, LE_SET_SCAN_PARAMETERS_CP_SIZE, &status, &scan_params_cp);

    ret = hci_send_req(device, &scan_params_rq, 1000);
    if (ret < 0) {
        hci_close_dev(device);
        perror("Failed to set scan parameters data");
        return ret;
    }
    return 0;
}

int le_set_reports_mask(int device)
{
    uint8_t status;
    int ret;

    le_set_event_mask_cp event_mask_cp;
    memset(&event_mask_cp, 0, sizeof(le_set_event_mask_cp));
    int i = 0;
    for (i = 0; i < 8; i++) {
        event_mask_cp.mask[i] = 0xFF;
    }

    struct hci_request set_mask_rq = ble_hci_request(OCF_LE_SET_EVENT_MASK, LE_SET_EVENT_MASK_CP_SIZE, &status, &event_mask_cp);
    ret = hci_send_req(device, &set_mask_rq, 1000);
    if (ret < 0) {
        hci_close_dev(device);
        perror("Failed to set event mask");
        return ret;
    }
    return 0;
}

void print_help(const char *program_name)
{
    fprintf(stderr, "Usage: %s [-h] [-d device] [-t seconds] [-f output file]\n" \
            "-h: print this help\n" \
            "-d: Bluetooth device name (hciX) to use\n" \
            "-t: how many seconds to run this program\n" \
            "-f: write scan results to file with the provided name\n",
            program_name);
}

/* This function checks whether the program should stop.
 * Return values: 1 stop, 0 continue
 */
int check_stop_time(time_t end_time)
{
    time_t current_time = 0;
    if ((current_time = time(NULL)) == -1) {
        perror("Could not get current UNIX time");
        return 0;
    }
    if (current_time >= end_time) {
        return 1;
    }
    return 0;
}

int main(int argc, char* argv[])
{
    const size_t DEVICE_NAME_MAX_LENGTH = 5;
    char *outfile_name = NULL;
    char device_name[DEVICE_NAME_MAX_LENGTH];
    uint8_t stdout_print = 1;
    uint8_t running_time = 0;
    int opt;

    memset(device_name, 0, DEVICE_NAME_MAX_LENGTH);

    while ((opt = getopt(argc, argv, "hd:f:t:")) != -1) {
        switch (opt) {
        case 'h':
            print_help(argv[0]);
            return -1;
        case 'd':
            strcpy(device_name, optarg);
            break;
        case 'f':
            /* Ignoring malloc errors on purpose */
            outfile_name = malloc(strlen(optarg) + 1);
            strncpy(outfile_name, optarg, strlen(optarg) + 1);
            stdout_print = 0;
            break;
        case 't':
            if ((running_time = atoi(optarg)) <= 0) {
                fprintf(stderr, "Program running time may not be zero or negative\n");
                return 1;
            }
            break;
        default: /* '?' */
            print_help(argv[0]);
            return -1;
        }
    }

    /* Initialize starting and ending time */
    time_t end_time = 0;
    if (running_time) {
        if ((end_time = time(NULL)) == -1) {
            perror("Could not get program stop time");
            return -1;
        }
        end_time += running_time;
    }

    /* Get HCI device */
    const int device = hci_open_dev(strcmp(device_name, "") ? hci_devid(device_name) : hci_get_route(NULL));
    if (device < 0) {
        perror("Failed to open HCI device");
        return 0;
    }

    if (le_set_scan_parameters(device)) {
        return -1;
    }

    /* Set BLE events report mask */
    if (le_set_reports_mask(device)) {
        return -1;
    }

    if (le_scan_enable(device, 0x001)) {
        return -1;
    }

    /* Get results */
    struct hci_filter nf;
    hci_filter_clear(&nf);
    hci_filter_set_ptype(HCI_EVENT_PKT, &nf);
    hci_filter_set_event(EVT_LE_META_EVENT, &nf);
    if (setsockopt(device, SOL_HCI, HCI_FILTER, &nf, sizeof(nf)) < 0) {
        hci_close_dev(device);
        perror("Could not set socket options");
        return 0;
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = sigint_handler;
    sigaction(SIGINT, &sa, NULL);

    uint8_t buf[HCI_MAX_EVENT_SIZE];
    evt_le_meta_event *meta_event;
    le_advertising_info *info;
    int len;

    FILE *output_file = NULL;
    if (!stdout_print) {
        if (!(output_file = fopen(outfile_name, "w"))) {
            perror("Could not open output file for writing");
            goto done;
        }
    }

    while (1) {
        if ((len = read(device, buf, sizeof(buf))) < 0) {
            if (errno == EINTR && signal_received == SIGINT) {
                goto done;
            }

            if (errno == EAGAIN || errno == EINTR) {
                continue;
            }
            goto done;
        }
        if (len >= HCI_EVENT_HDR_SIZE) {
            meta_event = (evt_le_meta_event*)(buf + HCI_EVENT_HDR_SIZE + 1);
            if (meta_event->subevent == EVT_LE_ADVERTISING_REPORT) {
                uint8_t reports_count = meta_event->data[0];
                void *offset = meta_event->data + 1;
                while (reports_count--) {
                    info = (le_advertising_info *)offset;
                    char addr[18];
                    ba2str(&(info->bdaddr), addr);
                    if (stdout_print) {
                        fprintf(stdout, "%s %d\n", addr, (int8_t)info->data[info->length]);
                    } else {
                        fprintf(output_file, "%s %d\n", addr, (int8_t)info->data[info->length]);
                    }
                    offset = info->data + info->length + 2;

                    if (running_time && check_stop_time(end_time)) {
                        break;
                    }
                }
            }
        }
        if (running_time && check_stop_time(end_time)) {
            break;
        }
    }

 done:
    /* Disable scanning */
    if (le_scan_enable(device, 0x00)) {
        return -1;
    }
    if (output_file) {
        if (fclose(output_file) == EOF) {
            perror("Could not close output file");
        }
    }
    free(outfile_name);

    return 0;
}
