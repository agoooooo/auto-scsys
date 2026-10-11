import os
import sqlite3
import stat
import subprocess
import sys

import pytest

from server.database_backup import backup_database, restore_database, verify_backup


def create_database(path):
    with sqlite3.connect(path) as database:
        database.execute("CREATE TABLE records (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
        database.executemany(
            "INSERT INTO records(value) VALUES(?)",
            [("one",), ("two",)],
        )


def read_records(path):
    with sqlite3.connect(path) as database:
        return database.execute("SELECT value FROM records ORDER BY id").fetchall()


def test_backup_and_restore_keep_data_and_private_permissions(tmp_path):
    source = tmp_path / "flowpilot.sqlite3"
    backup = tmp_path / "backup.sqlite3"
    restored = tmp_path / "restored.sqlite3"
    create_database(source)

    assert backup_database(source, backup) == backup
    assert verify_backup(backup) == backup
    assert stat.S_IMODE(os.stat(backup).st_mode) == 0o600
    assert read_records(backup) == [("one",), ("two",)]

    assert restore_database(backup, restored) == restored
    assert stat.S_IMODE(os.stat(restored).st_mode) == 0o600
    assert read_records(restored) == [("one",), ("two",)]


def test_backup_includes_committed_wal_data_while_database_is_open(tmp_path):
    source = tmp_path / "wal.sqlite3"
    backup = tmp_path / "wal-backup.sqlite3"
    with sqlite3.connect(source) as database:
        database.execute("PRAGMA journal_mode=WAL")
        database.execute("PRAGMA wal_autocheckpoint=0")
        database.execute("CREATE TABLE records (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
        database.execute("INSERT INTO records(value) VALUES('committed in WAL')")
        database.commit()

        backup_database(source, backup)
        assert read_records(backup) == [("committed in WAL",)]


def test_backup_refuses_overwrite_and_source_as_destination(tmp_path):
    source = tmp_path / "flowpilot.sqlite3"
    backup = tmp_path / "backup.sqlite3"
    create_database(source)
    backup.write_text("preserve existing destination")

    with pytest.raises(FileExistsError):
        backup_database(source, backup)
    with pytest.raises(ValueError, match="different"):
        backup_database(source, source)
    assert backup.read_text() == "preserve existing destination"


def test_restore_refuses_overwrite_and_invalid_backup(tmp_path):
    source = tmp_path / "flowpilot.sqlite3"
    backup = tmp_path / "backup.sqlite3"
    destination = tmp_path / "existing.sqlite3"
    create_database(source)
    backup_database(source, backup)
    destination.write_text("preserve existing destination")

    with pytest.raises(FileExistsError):
        restore_database(backup, destination)
    assert destination.read_text() == "preserve existing destination"

    corrupt = tmp_path / "corrupt.sqlite3"
    corrupt.write_text("not a sqlite database")
    with pytest.raises(sqlite3.DatabaseError):
        verify_backup(corrupt)


def test_database_backup_cli_creates_verifies_and_restores(tmp_path):
    source = tmp_path / "source.sqlite3"
    backup = tmp_path / "backup.sqlite3"
    restored = tmp_path / "restored.sqlite3"
    create_database(source)
    module_command = [sys.executable, "-m", "server.database_backup"]

    created = subprocess.run(
        [*module_command, "backup", "--database", str(source), "--output", str(backup)],
        capture_output=True,
        text=True,
        check=False,
    )
    assert created.returncode == 0, created.stderr

    verified = subprocess.run(
        [*module_command, "verify", "--backup", str(backup)],
        capture_output=True,
        text=True,
        check=False,
    )
    assert verified.returncode == 0, verified.stderr

    restored_result = subprocess.run(
        [*module_command, "restore", "--backup", str(backup), "--database", str(restored)],
        capture_output=True,
        text=True,
        check=False,
    )
    assert restored_result.returncode == 0, restored_result.stderr
    assert read_records(restored) == [("one",), ("two",)]
