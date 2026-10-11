from __future__ import annotations

import argparse
import os
import sqlite3
import sys
import tempfile
from pathlib import Path


def _integrity_check(database: sqlite3.Connection) -> None:
    result = database.execute("PRAGMA integrity_check").fetchone()
    if not result or result[0] != "ok":
        detail = result[0] if result else "no integrity result"
        raise ValueError(f"Database integrity check failed: {detail}")


def _copy_database(source_path: Path, target_path: Path) -> None:
    with sqlite3.connect(source_path, timeout=30) as source:
        _integrity_check(source)
        with sqlite3.connect(target_path, timeout=30) as target:
            source.backup(target)
            _integrity_check(target)


def _publish_new_file(temporary_path: Path, destination: Path) -> None:
    os.chmod(temporary_path, 0o600)
    file_fd = os.open(temporary_path, os.O_RDONLY)
    try:
        os.fsync(file_fd)
    finally:
        os.close(file_fd)
    os.link(temporary_path, destination)
    temporary_path.unlink()
    directory_fd = os.open(destination.parent, os.O_RDONLY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)


def backup_database(database_path: str | Path, output_path: str | Path) -> Path:
    source = Path(database_path).resolve()
    destination = Path(output_path).resolve()
    if not source.is_file():
        raise ValueError(f"Database file does not exist: {source}")
    if source == destination:
        raise ValueError("Backup destination must be different from the database file")
    if not destination.parent.is_dir():
        raise ValueError(f"Backup directory does not exist: {destination.parent}")
    if destination.exists():
        raise FileExistsError(f"Backup destination already exists: {destination}")

    descriptor, temporary_name = tempfile.mkstemp(
        prefix=".flowpilot-backup-",
        suffix=".sqlite3",
        dir=destination.parent,
    )
    os.close(descriptor)
    temporary_path = Path(temporary_name)
    try:
        _copy_database(source, temporary_path)
        _publish_new_file(temporary_path, destination)
    finally:
        temporary_path.unlink(missing_ok=True)
    return destination


def verify_backup(backup_path: str | Path) -> Path:
    backup = Path(backup_path).resolve()
    if not backup.is_file():
        raise ValueError(f"Backup file does not exist: {backup}")
    with sqlite3.connect(backup, timeout=30) as database:
        _integrity_check(database)
    return backup


def restore_database(backup_path: str | Path, database_path: str | Path) -> Path:
    backup = verify_backup(backup_path)
    destination = Path(database_path).resolve()
    if backup == destination:
        raise ValueError("Restore destination must be different from the backup file")
    if not destination.parent.is_dir():
        raise ValueError(f"Restore directory does not exist: {destination.parent}")
    if destination.exists():
        raise FileExistsError(f"Restore destination already exists: {destination}")

    descriptor, temporary_name = tempfile.mkstemp(
        prefix=".flowpilot-restore-",
        suffix=".sqlite3",
        dir=destination.parent,
    )
    os.close(descriptor)
    temporary_path = Path(temporary_name)
    try:
        _copy_database(backup, temporary_path)
        _publish_new_file(temporary_path, destination)
    finally:
        temporary_path.unlink(missing_ok=True)
    return destination


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Create, verify, or safely restore a FlowPilot SQLite database.")
    commands = parser.add_subparsers(dest="command", required=True)

    backup_parser = commands.add_parser("backup", help="Create a consistent, private SQLite backup.")
    backup_parser.add_argument("--database", default=os.environ.get("FLOWPILOT_DB_PATH"))
    backup_parser.add_argument("--output", required=True)

    verify_parser = commands.add_parser("verify", help="Run SQLite integrity_check on a backup.")
    verify_parser.add_argument("--backup", required=True)

    restore_parser = commands.add_parser("restore", help="Restore a verified backup to a new database path.")
    restore_parser.add_argument("--backup", required=True)
    restore_parser.add_argument("--database", required=True)

    args = parser.parse_args(argv)
    try:
        if args.command == "backup":
            if not args.database:
                parser.error("backup requires --database or FLOWPILOT_DB_PATH")
            result = backup_database(args.database, args.output)
        elif args.command == "verify":
            result = verify_backup(args.backup)
        else:
            result = restore_database(args.backup, args.database)
    except (OSError, sqlite3.Error, ValueError) as error:
        print(f"Database {args.command} failed: {error}", file=sys.stderr)
        return 1
    print(f"Database {args.command} complete: {result}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
