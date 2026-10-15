from __future__ import annotations

import json
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
            "FLOWPILOT_SITE_DOMAIN": "www.example.test",
            "FLOWPILOT_SITE_CONSOLE_URL": "https://console.example.test",
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


@pytest.mark.skipif(shutil.which("docker") is None, reason="Docker Compose CLI is not installed")
def test_private_staging_compose_is_valid_and_binds_ports_to_loopback_by_default():
    repository = Path(__file__).resolve().parents[2]
    environment = os.environ.copy()
    environment.update(
        {
            "FLOWPILOT_STAGING_WEB_ORIGIN": "http://127.0.0.1:18000",
            "FLOWPILOT_STAGING_S3_PUBLIC_ENDPOINT": "http://127.0.0.1:19000",
            "FLOWPILOT_POSTGRES_DB": "flowpilot_staging",
            "FLOWPILOT_POSTGRES_USER": "flowpilot_staging",
            "FLOWPILOT_POSTGRES_PASSWORD": "staging-validation-password",
            "FLOWPILOT_DATABASE_URL": "postgresql://flowpilot_staging:staging-validation-password@db:5432/flowpilot_staging",
            "FLOWPILOT_TOKEN_SECRET": "staging-validation-secret-0123456789",
            "FLOWPILOT_S3_ACCESS_KEY": "staging-validation-access",
            "FLOWPILOT_S3_SECRET_KEY": "staging-validation-secret",
        }
    )
    result = subprocess.run(
        ["docker", "compose", "-f", "compose.staging.yaml", "config", "--format", "json"],
        cwd=repository,
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr
    configuration = json.loads(result.stdout)
    assert "proxy" not in configuration["services"]
    for service_name in ("api", "web", "public-site", "object-store"):
        ports = configuration["services"][service_name]["ports"]
        assert ports[0]["host_ip"] == "127.0.0.1"
    assert configuration["services"]["public-site"]["ports"][0]["published"] == "18002"
    assert configuration["services"]["object-store"]["image"] == "rustfs/rustfs:1.0.1"
