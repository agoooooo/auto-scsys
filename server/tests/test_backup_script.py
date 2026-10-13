from __future__ import annotations

import hashlib
import os
import stat
import subprocess
import sys
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
BACKUP_SCRIPT = REPOSITORY / "scripts" / "backup-postgres.sh"


def fake_docker(path: Path) -> Path:
    executable = path / "docker"
    executable.write_text(
        f"""#!{sys.executable}
import os
import sys

args = sys.argv[1:]
if "pg_restore" in args:
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
