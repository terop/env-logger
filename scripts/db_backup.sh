#!/bin/sh

# This scripts backs up a PostgreSQL database and uploads the backup to a user
# specified remote host.
# SSH key authentication without a password MUST be in place before running
# this script.

# For a database server running on the host see below.
# This script assumes that ~/.pgpass file with valid content and 0600 permission
# is in place. If not then this script will not work. Format for the ~/.pgpass
# file is shown below:
# server:port:database:username:password

set -e

usage() {
    cat >&2 <<EOF
This scripts backs up a PostgreSQL database and uploads the backup to a remote
host. The database server can be running on the host itself or in a Kubernetes
pod, flags must be used specify which one to use.

Backup upload settings are read from a file named 'backup_conf.sh' which must
exist in the same directory as this file.

For Kubernetes the following environment variables must be specified:
DB_PASSWD:  password to the database
DB_USER:    username of the database user
POD_NAME:   name of the pod running PostgreSQL, the pod must have the pg_dump command

Usage: $0 [-h] [-k] [-l] [-n] <database name>
Flags:
-h    Print this message and exit
-k    Backup from PostgreSQL running in a Kubernetes pod
-l    Backup from PostgreSQL running on the host
-n    Do a local backup (do not upload backup file)
EOF
}

local_backup=0

while getopts 'hkln' OPTION; do
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
        n)
            local_backup=1
            ;;
        *)
            echo "Invalid option: ${OPTION}"
            exit 1
    esac
done
shift "$((OPTIND - 1))"

if [ ${local_db} ] && [ ${kubernetes_db} ]; then
    echo "Error: both -k and -l flags cannot be specified simultaneously" >&2
    exit 1
fi
if [ -z ${local_db} ] && [ -z ${kubernetes_db} ]; then
    echo "Error: either -k or -l flag must be specified" >&2
    exit 1
fi

if [ ${kubernetes_db} ]; then
    if [ -z "${DB_PASSWD}" ]; then
        echo "Error: DB_PASSWD env variable must be specified" >&2
        exit 1
    fi
    if [ -z "${DB_USER}" ]; then
        echo "Error: DB_USER env variable must be specified" >&2
        exit 1
    fi
    if [ -z "${POD_NAME}" ]; then
        echo "Error: POD_NAME env variable must be specified" >&2
        exit 1
    fi
fi

db_name=$1
if [ -z "${db_name}" ]; then
    echo 'Error: database name must be provided' >&2
    exit 1
fi

conf_file=backup_conf.sh
if [ ! -e ${conf_file} ]; then
    echo "Error: configuration file '${conf_file}' not found" >&2
    exit 1
fi
. ./${conf_file}

echo "Dumping database ${db_name} to file ${backup_file_name}"

if [ ${local_db} ]; then
    backup_file_name="${db_name}_$(date -Iseconds).sql.xz"

    if [ $(pg_dump -c -w "${db_name}" | xz -z -T 0 > ${backup_file_name}) ]; then
        echo "Error: pg_dump failed, deleting file" >&2
        rm "${backup_file_name}"
        return 1
    fi
else
    backup_file_name_no_comp="${db_name}_$(date -Iseconds).sql"

    if [ $(kubectl exec ${POD_NAME} -- /bin/sh -c \
                   "PGPASSWORD='${DB_PASSWD}' pg_dump -c -w -U ${DB_USER} ${db_name}" \
                   > ${backup_file_name_no_comp}) ]; then
        echo "Error: Kubernetes pg_dump failed, deleting file" >&2
        rm "${backup_file_name}"
        return 1
    fi
    if [ $(xz -z -T 0 ${backup_file_name_no_comp}) ]; then
        echo "Error: Kubernetes pg_dump compression failed, deleting file" >&2
        rm "${backup_file_name_no_comp}"
        return 1
    fi
    backup_file_name="${backup_file_name_no_comp}.xz"
fi

if [ $local_backup -eq 1 ]; then
    echo "Backup ready, backup file name: ${backup_file_name}"
    exit 0
fi

target_directory="${target_base_directory}/${db_name}"

echo "Uploading ${backup_file_name} to ${target_host}:${target_directory}"
scp "./${backup_file_name}" "${target_user}@${target_host}:${target_directory}" >/dev/null
if [ $? -eq 0 ]; then
    echo "File upload succeeded"
else
    echo "File upload failed"
fi
rm ./"${backup_file_name}"
