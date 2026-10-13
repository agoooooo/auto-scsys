# FlowPilot

FlowPilot is an Android automation prototype with a FastAPI service, browser-based admin console, and a native Android client. The Android app can run user-reviewed Python task versions and use explicitly authorized OCR/accessibility capabilities. Local development defaults to SQLite; production deployments can use PostgreSQL, S3-compatible script storage, and the Docker Compose stack.

## Requirements

- Python 3.10 or newer
- GNU Make
- Node.js 20 or newer (for the frontend tests)
- Python's `venv`/`pip` support (`python3-venv` on minimal Ubuntu installs)

## Start locally

```bash
make dev
```

The first run creates `.venv` and installs the server dependencies. It then starts both services on all network interfaces so another device on the same LAN can connect:

- Browser prototype: <http://127.0.0.1:8000/demo/>
- API documentation: <http://127.0.0.1:8001/docs>

The launcher prints the detected LAN URLs. Open the demo URL from another device on the same network; the login form uses that host for the API address by default. Use the demo accounts shown on the login page. Press `Ctrl-C` to stop both servers. The SQLite database is stored at `server/flowpilot.sqlite3` and persists between starts. To use a different database path or a stable token secret, set `FLOWPILOT_DB_PATH` or `FLOWPILOT_TOKEN_SECRET` before running `make dev`; the token secret must be at least 32 bytes.

If `make setup` reports missing `venv`/`pip` support on Ubuntu, install the OS package first (for example, `sudo apt install python3.12-venv`).

Set `FLOWPILOT_API_HOST`, `FLOWPILOT_WEB_HOST`, `FLOWPILOT_API_PORT`, or `FLOWPILOT_WEB_PORT` to override bind addresses and ports. If you change the API port, update the service address in the login form as well (it defaults to port 8001). The launcher detects private IPv4 addresses assigned to non-virtual network interfaces and allows each exact demo origin automatically. `FLOWPILOT_CORS_ORIGINS` accepts a comma-separated list of exact browser origins for custom hostnames or ports; when set, it replaces the automatically generated origins.

**Network safety:** LAN binding does not make this suitable for the public internet. The local database has known demo accounts, the service has no TLS, and this is not a production deployment. Only use it on a trusted network; do not configure router port forwarding or expose it to the internet. Allow inbound TCP ports 8000 and 8001 in the machine's firewall only for the trusted LAN if necessary. The app issues a fresh random token secret on each start unless one is configured, so existing tokens expire after a restart.

Production rollout is phased: validate PostgreSQL, object storage, and application workflows first in an isolated development/staging environment; public domains and HTTPS ingress are intentionally deferred to the final rollout stage. Until HTTPS and production controls are enabled, keep every environment private and do not use real production credentials or expose HTTP services to the internet. The repository provides a loopback-only-by-default staging Compose file; see [`server/README.md`](server/README.md#private-staging-http-loopback-only-by-default). Configure backup/restore, account recovery, edge rate limits, monitoring, and security review according to your production requirements; Docker support alone does not supply those operational controls.

## Tests

```bash
make test
```

This runs the FastAPI tests and all Node.js tests without contacting Android devices. For API setup, endpoints, and current limitations, see [`server/README.md`](server/README.md).

GitHub Actions runs these tests and both Android apps' JVM unit tests/Debug builds on pushes and pull requests. Instrumentation tests still require a connected Android device and are run separately using the instructions in [`android/README.md`](android/README.md).

The native Android client setup, runtime bridge, permissions, and current limitations are documented in [`android/README.md`](android/README.md).

For a concise handoff covering implemented features, end-to-end verification, known limitations, and blocked production work, see [`docs/开发交接.md`](docs/开发交接.md).
