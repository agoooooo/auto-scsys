#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /absolute/path/to/flowpilot-postgres-*.dump" >&2
    exit 2
fi

case "$1" in
    /*) ;;
    *)
        echo "Backup file path must be absolute" >&2
        exit 2
        ;;
esac

backup_name=$(basename -- "$1")
backup_dir=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd -P) || {
    echo "Backup directory does not exist" >&2
    exit 2
}
backup_path="${backup_dir}/${backup_name}"
checksum_name="${backup_name}.sha256"
checksum_path="${backup_dir}/${checksum_name}"

if [ ! -f "$backup_path" ] || [ ! -r "$backup_path" ] || [ ! -s "$backup_path" ]; then
    echo "Backup file must exist, be readable, and not be empty" >&2
    exit 2
fi

if [ ! -f "$checksum_path" ] || [ ! -r "$checksum_path" ] || [ ! -s "$checksum_path" ]; then
    echo "Backup checksum file is missing, unreadable, or empty" >&2
    exit 2
fi

if ! awk -v name="$backup_name" '
    NR != 1 || NF != 2 || length($1) != 64 || $1 !~ /^[[:xdigit:]]+$/ || $2 != name {
        invalid = 1
    }
    END { exit invalid || NR != 1 }
' "$checksum_path"; then
    echo "Backup checksum file has an invalid format or filename" >&2
    exit 1
fi

if ! (CDPATH= cd -- "$backup_dir" && sha256sum --check --strict --status -- "$checksum_name"); then
    echo "Backup SHA-256 verification failed" >&2
    exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
    echo "Docker Compose CLI is required to validate the PostgreSQL archive" >&2
    exit 127
fi

if ! docker compose exec -T db pg_restore --list < "$backup_path" >/dev/null; then
    echo "PostgreSQL backup archive validation failed" >&2
    exit 1
fi

printf 'Backup checksum and PostgreSQL archive are valid: %s\n' "$backup_path"
