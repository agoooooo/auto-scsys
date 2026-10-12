from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest


@pytest.mark.skipif(shutil.which("docker") is None, reason="Docker Compose CLI is not installed")
def test_production_compose_configuration_is_valid():
    repository = Path(__file__).resolve().parents[2]
    environment = os.environ.copy()
    environment.update(
        {
            "FLOWPILOT_API_DOMAIN": "api.example.test",
            "FLOWPILOT_WEB_DOMAIN": "console.example.test",
            "FLOWPILOT_S3_DOMAIN": "objects.example.test",
            "FLOWPILOT_POSTGRES_DB": "flowpilot",
            "FLOWPILOT_POSTGRES_USER": "flowpilot",
            "FLOWPILOT_POSTGRES_PASSWORD": "compose-validation-password",
            "FLOWPILOT_DATABASE_URL": "postgresql://flowpilot:compose-validation-password@db:5432/flowpilot",
            "FLOWPILOT_TOKEN_SECRET": "compose-validation-secret-0123456789",
            "FLOWPILOT_S3_ACCESS_KEY": "compose-validation-access",
            "FLOWPILOT_S3_SECRET_KEY": "compose-validation-secret",
        }
    )
    result = subprocess.run(
        ["docker", "compose", "config", "-q"],
        cwd=repository,
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr
