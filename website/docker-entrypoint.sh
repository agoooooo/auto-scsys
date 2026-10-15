#!/bin/sh
set -eu

console_url="${FLOWPILOT_SITE_CONSOLE_URL:-}"
if [ -n "$console_url" ] && ! printf '%s' "$console_url" | grep -Eq '^https?://[A-Za-z0-9.-]+(:[0-9]+)?/?$'; then
    echo "FLOWPILOT_SITE_CONSOLE_URL must be an HTTP(S) origin without a path or query." >&2
    exit 1
fi

sed "s|__CONSOLE_URL__|$console_url|g" \
    /etc/flowpilot-site-config.js.template \
    > /usr/share/nginx/html/site-config.js
