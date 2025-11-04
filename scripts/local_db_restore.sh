#!/bin/sh

# This a script for restoring database from a backup snapshot.

set -e

usage() {
    cat <<EOF
This a script for restoring a local database from a backup snapshot.
It supports both local as well as Kubernetes pod based installations, flags
must be used specify which one to use.

For Kubernetes the following environment variables must be specified:
DB_PASSWD:  password to the database
DB_USER:    username of the database user
POD_NAME:   name of the pod running PostgreSQL, the pod must have the psql command

Usage: $0 [-h] [-k] [-l] <database name> <database snapshot name>
Flags:
-h    Print this message and exit
-k    Restore backup to PostgreSQL running in a Kubernetes pod
-l    Restore backup to PostgreSQL running on the host

Example: $0 env_logger db_snapshot.sql
EOF
}

while getopts 'hkl' OPTION; do
    case "${OPTION}" in
        h)
            usage
            exit 1
            ;;
        k)
            kubernetes_db=1
            ;;
        l)
            local_db=1
            ;;
        *)
            echo "Invalid option: ${OPTION}"
            exit 1
    esac
done
shift "$((OPTIND - 1))"

if [ "${local_db}" ] && [ "${kubernetes_db}" ]; then
    echo "Error: both -k and -l flags cannot be specified simultaneously" >&2
    exit 1
fi
if [ -z "${local_db}" ] && [ -z "${kubernetes_db}" ]; then
    echo "Error: either -k or -l flag must be specified" >&2
    exit 1
fi

if [ "${kubernetes_db}" ]; then
    if [ -z "${POD_NAME}" ]; then
        echo "Error: POD_NAME env variable must be specified" >&2
        exit 1
    fi
fi

db_name=$1
snapshot_name=$2

if [ -z "${db_name}" ]; then
    echo "Error: database name must be specified" >&2
    exit 1
fi
if [ -z "${snapshot_name}" ]; then
    echo "Error: snapshot name must be specified" >&2
    exit 1
fi

file_out=$(file -ib "${snapshot_name}")
# shellcheck disable=SC2046
if [ $(echo "${file_out}"|grep -c xz) -eq 1 ]; then
    echo "Compressed snapshot, decompressing before restore"
    unxz "${snapshot_name}"
    snapshot_name=$(echo "${snapshot_name}"|sed 's/.xz//')
fi

echo "Adding new values"
if [ "${local_db}" ]; then
   pg_restore -c -d "${db_name}" < "${snapshot_name}"
else
    kubectl exec -i "${POD_NAME}" -c postgres -- pg_restore -c -d "${db_name}" < "${snapshot_name}"
fi
