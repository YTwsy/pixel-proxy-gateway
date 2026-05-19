#!/usr/bin/env sh
set -eu

PKG=${PKG:-com.wsy.pixelproxygateway}
ADB=${ADB:-adb}
HTTP_PORT=${HTTP_PORT:-8080}
SOCKS_PORT=${SOCKS_PORT:-1080}
BIND_ADDRESS=${BIND_ADDRESS:-0.0.0.0}
ENABLE_HTTP=${ENABLE_HTTP:-true}
ENABLE_SOCKS=${ENABLE_SOCKS:-true}
AUTH_ENABLED=${AUTH_ENABLED:-false}
USERNAME=${USERNAME:-}
PASSWORD=${PASSWORD:-}
HEALTH_URL=${HEALTH_URL:-https://connectivitycheck.gstatic.com/generate_204}
EXPECTED_STATUS=${EXPECTED_STATUS:-204}
INTERVAL_SECONDS=${INTERVAL_SECONDS:-300}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-15}
FAILURE_THRESHOLD=${FAILURE_THRESHOLD:-2}
START_ON_BOOT=${START_ON_BOOT:-true}

if [ "$ENABLE_HTTP" != "true" ] && [ "$ENABLE_SOCKS" != "true" ]; then
  echo "At least one listener must be enabled. Set ENABLE_HTTP=true or ENABLE_SOCKS=true." >&2
  exit 2
fi
if [ "$ENABLE_HTTP" = "true" ] && [ "$ENABLE_SOCKS" = "true" ] && [ "$HTTP_PORT" = "$SOCKS_PORT" ]; then
  echo "HTTP_PORT and SOCKS_PORT cannot be the same when both listeners are enabled: $HTTP_PORT" >&2
  exit 2
fi

set -- shell am start-foreground-service \
  -n "$PKG/.ProxyForegroundService" \
  -a "$PKG.action.START" \
  --es bind_address "$BIND_ADDRESS" \
  --ei http_port "$HTTP_PORT" \
  --ei socks_port "$SOCKS_PORT" \
  --ez enable_http "$ENABLE_HTTP" \
  --ez enable_socks "$ENABLE_SOCKS" \
  --ez auth_enabled "$AUTH_ENABLED"

if [ -n "$USERNAME" ]; then
  set -- "$@" --es username "$USERNAME"
fi
if [ -n "$PASSWORD" ]; then
  set -- "$@" --es password "$PASSWORD"
fi

set -- "$@" \
  --es health_url "$HEALTH_URL" \
  --es expected_status "$EXPECTED_STATUS" \
  --el interval_seconds "$INTERVAL_SECONDS" \
  --ei timeout_seconds "$TIMEOUT_SECONDS" \
  --ei failure_threshold "$FAILURE_THRESHOLD" \
  --ez start_on_boot "$START_ON_BOOT"

"$ADB" "$@"
