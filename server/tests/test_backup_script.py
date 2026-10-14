from __future__ import annotations

import hashlib
import os
import stat
import subprocess
import sys
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
BACKUP_SCRIPT = REPOSITORY / "scripts" / "backup-postgres.sh"
VERIFY_SCRIPT = REPOSITORY / "scripts" / "verify-postgres-backup.sh"
ARCHIVE = b"validated PostgreSQL archive"


def fake_docker(path: Path) -> Path:
    executable = path / "docker"
    executable.write_text(
        f"""#!{sys.executable}
import os
import sys

args = sys.argv[1:]
marker = os.environ.get("FAKE_DOCKER_MARKER")
if marker:
    with open(marker, "w", encoding="utf-8") as output:
        output.write("called")
if "pg_restore" in args:
    if os.environ.get("FAKE_DOCKER_RESTORE_FAIL") == "1":
        raise SystemExit(43)
    if sys.stdin.buffer.read() != b"validated PostgreSQL archive":
        raise SystemExit(1)
elif "pg_dump" in " ".join(args):
    if os.environ.get("FAKE_DOCKER_FAIL") == "1":
        raise SystemExit(42)
    sys.stdout.buffer.write(b"validated PostgreSQL archive")
else:
    raise SystemExit(2)
""",
        encoding="utf-8",
    )
    executable.chmod(0o700)
    return executable


def run_backup(directory: Path, docker_directory: Path, *, fail: bool = False) -> subprocess.CompletedProcess[str]:
    environment = os.environ.copy()
    environment["PATH"] = f"{docker_directory}:{environment['PATH']}"
    if fail:
        environment["FAKE_DOCKER_FAIL"] = "1"
    else:
        environment.pop("FAKE_DOCKER_FAIL", None)
    return subprocess.run(
        [str(BACKUP_SCRIPT), str(directory)],
        cwd=REPOSITORY,
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )


def run_verify(
    backup_path: Path,
    docker_directory: Path,
    *,
    restore_fail: bool = False,
    marker: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    environment = os.environ.copy()
    environment["PATH"] = f"{docker_directory}:{environment['PATH']}"
    if restore_fail:
        environment["FAKE_DOCKER_RESTORE_FAIL"] = "1"
    else:
        environment.pop("FAKE_DOCKER_RESTORE_FAIL", None)
    if marker:
        environment["FAKE_DOCKER_MARKER"] = str(marker)
    return subprocess.run(
        [str(VERIFY_SCRIPT), str(backup_path)],
        cwd=REPOSITORY,
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )


def create_backup_pair(directory: Path, *, content: bytes = ARCHIVE) -> Path:
    backup_path = directory / "flowpilot-postgres-20261014T000000Z.dump"
    backup_path.write_bytes(content)
    digest = hashlib.sha256(content).hexdigest()
    Path(f"{backup_path}.sha256").write_text(
        f"{digest}  {backup_path.name}\n",
        encoding="utf-8",
    )
    return backup_path


def test_backup_script_validates_archive_and_restricts_file_permissions(tmp_path):
    docker_directory = tmp_path / "bin"
    docker_directory.mkdir()
    fake_docker(docker_directory)
    backup_directory = tmp_path / "protected"
    backup_directory.mkdir(mode=0o700)

    result = run_backup(backup_directory, docker_directory)

    assert result.returncode == 0, result.stderr
    dump_path, = backup_directory.glob("flowpilot-postgres-*.dump")
    checksum_path = Path(f"{dump_path}.sha256")
    assert dump_path.read_bytes() == b"validated PostgreSQL archive"
    assert stat.S_IMODE(dump_path.stat().st_mode) == 0o600
    assert stat.S_IMODE(checksum_path.stat().st_mode) == 0o600
    expected_digest = hashlib.sha256(dump_path.read_bytes()).hexdigest()
    assert checksum_path.read_text(encoding="utf-8") == f"{expected_digest}  {dump_path.name}\n"


def test_backup_script_removes_partial_dump_when_database_dump_fails(tmp_path):
    docker_directory = tmp_path / "bin"
    docker_directory.mkdir()
    fake_docker(docker_directory)
    backup_directory = tmp_path / "protected"
    backup_directory.mkdir(mode=0o700)

    result = run_backup(backup_directory, docker_directory, fail=True)

    assert result.returncode == 42
    assert list(backup_directory.iterdir()) == []


def test_backup_script_rejects_relative_destination(tmp_path):
    result = subprocess.run(
        [str(BACKUP_SCRIPT), "relative-backup-dir"],
        cwd=tmp_path,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode == 2
    assert "absolute path" in result.stderr


def test_verify_script_checks_checksum_and_archive(tmp_path):
    docker_directory = tmp_path / "bin"
    docker_directory.mkdir()
    fake_docker(docker_directory)
    backup_path = create_backup_pair(tmp_path)
    marker = tmp_path / "docker-called"

    result = run_verify(backup_path, docker_directory, marker=marker)

    assert result.returncode == 0, result.stderr
    assert "checksum and PostgreSQL archive are valid" in result.stdout
    assert marker.read_text(encoding="utf-8") == "called"


def test_verify_script_rejects_tampered_backup_before_archive_validation(tmp_path):
    docker_directory = tmp_path / "bin"
    docker_directory.mkdir()
    fake_docker(docker_directory)
    backup_path = create_backup_pair(tmp_path)
    backup_path.write_bytes(b"tampered archive")
    marker = tmp_path / "docker-called"

    result = run_verify(backup_path, docker_directory, marker=marker)

    assert result.returncode == 1
    assert "SHA-256 verification failed" in result.stderr
    assert not marker.exists()


def test_verify_script_rejects_missing_or_malformed_checksum(tmp_path):
    docker_directory = tmp_path / "bin"
    docker_directory.mkdir()
    fake_docker(docker_directory)
    backup_path = create_backup_pair(tmp_path)
    checksum_path = Path(f"{backup_path}.sha256")

    checksum_path.unlink()
    missing = run_verify(backup_path, docker_directory)
    assert missing.returncode == 2
    assert "checksum file is missing" in missing.stderr

    checksum_path.write_text(f"{'0' * 64}  another.dump\n", encoding="utf-8")
    malformed = run_verify(backup_path, docker_directory)
    assert malformed.returncode == 1
    assert "invalid format or filename" in malformed.stderr


def test_verify_script_reports_invalid_postgres_archive(tmp_path):
    docker_directory = tmp_path / "bin"
    docker_directory.mkdir()
    fake_docker(docker_directory)
    backup_path = create_backup_pair(tmp_path, content=b"not a PostgreSQL archive")

    result = run_verify(backup_path, docker_directory, restore_fail=True)

    assert result.returncode == 1
    assert "PostgreSQL backup archive validation failed" in result.stderr


def test_verify_script_rejects_relative_backup_path(tmp_path):
    result = subprocess.run(
        [str(VERIFY_SCRIPT), "relative.dump"],
        cwd=tmp_path,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode == 2
    assert "absolute" in result.stderr
