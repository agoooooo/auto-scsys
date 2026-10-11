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
LAN_IP="$("$PYTHON" -c 'import socket; s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.connect(("192.0.2.1",80)); print(s.getsockname()[0]); s.close()' 2>/dev/null || printf '127.0.0.1')"

export FLOWPILOT_DB_PATH="${FLOWPILOT_DB_PATH:-$ROOT_DIR/server/flowpilot.sqlite3}"
if [[ -z "${FLOWPILOT_TOKEN_SECRET:-}" ]]; then
  FLOWPILOT_TOKEN_SECRET="$("$PYTHON" -c 'import secrets; print(secrets.token_hex(32))')"
  export FLOWPILOT_TOKEN_SECRET
fi
if [[ -z "${FLOWPILOT_CORS_ORIGINS:-}" ]]; then
  FLOWPILOT_CORS_ORIGINS="http://localhost:$WEB_PORT,http://127.0.0.1:$WEB_PORT"
  if [[ "$LAN_IP" != "127.0.0.1" ]]; then
    FLOWPILOT_CORS_ORIGINS="$FLOWPILOT_CORS_ORIGINS,http://$LAN_IP:$WEB_PORT"
  fi
  export FLOWPILOT_CORS_ORIGINS
fi

cd "$ROOT_DIR"
"$PYTHON" -m uvicorn server.app:app --host "$API_HOST" --port "$API_PORT" &
API_PID=$!
"$PYTHON" -m http.server "$WEB_PORT" --bind "$WEB_HOST" --directory "$ROOT_DIR" &
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
printf 'FlowPilot demo: http://127.0.0.1:%s/demo/\n' "$WEB_PORT"
if [[ "$LAN_IP" != "127.0.0.1" ]]; then
  printf 'LAN API:        http://%s:%s/docs\n' "$LAN_IP" "$API_PORT"
  printf 'LAN demo:       http://%s:%s/demo/\n' "$LAN_IP" "$WEB_PORT"
else
  printf 'LAN address could not be detected; use `ip addr` to find this machine address.\n'
fi
printf 'Database:       %s\n' "$FLOWPILOT_DB_PATH"
printf 'Press Ctrl-C to stop both local servers.\n'

wait -n "$API_PID" "$WEB_PID"
