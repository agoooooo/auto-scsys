#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="$ROOT_DIR/.venv/bin/python"

if [[ ! -x "$PYTHON" ]]; then
  printf 'Python environment is missing. Run `make setup` first.\n' >&2
  exit 1
fi

API_HOST="${FLOWPILOT_API_HOST:-0.0.0.0}"
API_PORT="${FLOWPILOT_API_PORT:-8001}"
WEB_HOST="${FLOWPILOT_WEB_HOST:-0.0.0.0}"
WEB_PORT="${FLOWPILOT_WEB_PORT:-8000}"
LAN_IPS="$("$PYTHON" -c '
import ipaddress
import socket
import subprocess

networks = tuple(ipaddress.ip_network(value) for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
addresses = []
try:
    output = subprocess.check_output(
        ["ip", "-o", "-4", "addr", "show", "scope", "global"],
        text=True,
        stderr=subprocess.DEVNULL,
    )
except (FileNotFoundError, subprocess.CalledProcessError):
    output = ""
for line in output.splitlines():
    fields = line.split()
    if len(fields) < 4 or fields[1].split("@", 1)[0].startswith(("docker", "br-", "veth", "virbr", "cni", "podman")):
        continue
    address = fields[3].split("/", 1)[0]
    if any(ipaddress.ip_address(address) in network for network in networks):
        addresses.append(address)
if not addresses:
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("192.0.2.1", 80))
        address = probe.getsockname()[0]
    except OSError:
        address = ""
    finally:
        probe.close()
    if address and any(ipaddress.ip_address(address) in network for network in networks):
        addresses.append(address)
print("\\n".join(dict.fromkeys(addresses)))
' 2>/dev/null || true)"
mapfile -t LAN_IP_ADDRESSES <<< "$LAN_IPS"
LAN_IP="${LAN_IP_ADDRESSES[0]:-}"

export FLOWPILOT_DB_PATH="${FLOWPILOT_DB_PATH:-$ROOT_DIR/server/flowpilot.sqlite3}"
export FLOWPILOT_API_PROXY="http://127.0.0.1:$API_PORT"
if [[ -z "${FLOWPILOT_TOKEN_SECRET:-}" ]]; then
  FLOWPILOT_TOKEN_SECRET="$("$PYTHON" -c 'import secrets; print(secrets.token_hex(32))')"
  export FLOWPILOT_TOKEN_SECRET
fi
if [[ -z "${FLOWPILOT_CORS_ORIGINS:-}" ]]; then
  FLOWPILOT_CORS_ORIGINS="http://localhost:$WEB_PORT,http://127.0.0.1:$WEB_PORT"
  for address in "${LAN_IP_ADDRESSES[@]}"; do
    [[ -n "$address" ]] && FLOWPILOT_CORS_ORIGINS="$FLOWPILOT_CORS_ORIGINS,http://$address:$WEB_PORT"
  done
  export FLOWPILOT_CORS_ORIGINS
fi

cd "$ROOT_DIR"
"$PYTHON" -m uvicorn server.app:app --host "$API_HOST" --port "$API_PORT" &
API_PID=$!
npm --prefix "$ROOT_DIR/console" run dev -- --host "$WEB_HOST" --port "$WEB_PORT" &
WEB_PID=$!

cleanup() {
  local exit_code=$?
  trap - EXIT
  kill "$API_PID" "$WEB_PID" 2>/dev/null || true
  wait "$API_PID" "$WEB_PID" 2>/dev/null || true
  exit "$exit_code"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf 'FlowPilot API:  http://127.0.0.1:%s/docs\n' "$API_PORT"
printf 'FlowPilot console: http://127.0.0.1:%s/\n' "$WEB_PORT"
if [[ -n "$LAN_IP" ]]; then
  printf 'LAN API:        http://%s:%s/docs\n' "$LAN_IP" "$API_PORT"
  printf 'LAN console:    http://%s:%s/\n' "$LAN_IP" "$WEB_PORT"
else
  printf 'LAN address could not be detected; use `ip addr` to find this machine address.\n'
fi
printf 'Database:       %s\n' "$FLOWPILOT_DB_PATH"
printf 'Press Ctrl-C to stop both local servers.\n'

wait -n "$API_PID" "$WEB_PID"
