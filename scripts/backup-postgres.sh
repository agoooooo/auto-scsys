#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /absolute/path/to/encrypted-backup-directory" >&2
    exit 2
fi

case "$1" in
    /*) ;;
    *)
        echo "Backup directory must be an absolute path on protected off-host storage" >&2
        exit 2
        ;;
esac

if [ "$1" = "/" ] || [ ! -d "$1" ] || [ ! -w "$1" ]; then
    echo "Backup directory must already exist, be writable, and not be filesystem root" >&2
    exit 2
fi

if ! command -v docker >/dev/null 2>&1; then
    echo "Docker Compose CLI is required to run the PostgreSQL backup" >&2
    exit 127
fi

backup_dir=$(CDPATH= cd -- "$1" && pwd -P)
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_name="flowpilot-postgres-${timestamp}.dump"
backup_path="${backup_dir}/${backup_name}"
checksum_path="${backup_path}.sha256"

if [ -e "$backup_path" ] || [ -e "$checksum_path" ]; then
    echo "A backup already exists for timestamp ${timestamp}; retry in the next second" >&2
    exit 1
fi

umask 077
temporary_dump=$(mktemp "${backup_dir}/.flowpilot-postgres.XXXXXX")
temporary_checksum=""
cleanup() {
    [ ! -e "$temporary_dump" ] || rm -f -- "$temporary_dump"
    [ -z "$temporary_checksum" ] || [ ! -e "$temporary_checksum" ] || rm -f -- "$temporary_checksum"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

docker compose exec -T db sh -c \
    'pg_dump --no-owner --no-acl --format=custom -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    > "$temporary_dump"
if [ ! -s "$temporary_dump" ]; then
    echo "PostgreSQL produced an empty backup" >&2
    exit 1
fi

if ! docker compose exec -T db pg_restore --list < "$temporary_dump" >/dev/null; then
    echo "PostgreSQL backup archive validation failed" >&2
    exit 1
fi

chmod 600 "$temporary_dump"
temporary_checksum=$(mktemp "${backup_dir}/.flowpilot-checksum.XXXXXX")
sha256sum "$temporary_dump" | awk -v name="$backup_name" '{ print $1 "  " name }' > "$temporary_checksum"
chmod 600 "$temporary_checksum"
mv -- "$temporary_dump" "$backup_path"
temporary_dump=""
mv -- "$temporary_checksum" "$checksum_path"
temporary_checksum=""

printf 'Created and validated %s\n' "$backup_path"
