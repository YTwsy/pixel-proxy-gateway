#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ADB=${ADB:-adb}
PKG=${PKG:-com.wsy.pixelproxygateway}
APK=${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}

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

WAIT_SECONDS=${WAIT_SECONDS:-120}
REQUIRE_REQUEST_OK=${REQUIRE_REQUEST_OK:-false}
STATUS_MAX_AGE_SECONDS=${STATUS_MAX_AGE_SECONDS:-120}
. "$ROOT/scripts/lib_status.sh"

read_status() {
  "$ADB" shell dumpsys activity service "$PKG/.ProxyForegroundService" 2>/dev/null || true
}

wait_healthy() {
  deadline=$(( $(date +%s) + WAIT_SECONDS ))
  last_status=""
  while [ "$(date +%s)" -le "$deadline" ]; do
    last_status=$(read_status)
    service_running=$(printf '%s\n' "$last_status" | status_value serviceRunning)
    desired_running=$(printf '%s\n' "$last_status" | status_value desiredRunning)
    proxy_running=$(printf '%s\n' "$last_status" | status_value proxyRunning)
    port_ok=$(printf '%s\n' "$last_status" | status_value portOk)
    request_ok=$(printf '%s\n' "$last_status" | status_value requestOk)
    reason=$(printf '%s\n' "$last_status" | status_value lastRestartReason)
    status_updated_at_epoch_ms=$(printf '%s\n' "$last_status" | status_value statusUpdatedAtEpochMillis)
    status_age=$(status_age_seconds "$status_updated_at_epoch_ms" || true)
    echo "service=$service_running desired=$desired_running proxy=$proxy_running port=$port_ok request=$request_ok status_age=${status_age:-unknown}s reason=$reason" >&2
    if [ "$service_running" = "true" ] && [ "$desired_running" = "true" ] && [ "$proxy_running" = "true" ] && [ "$port_ok" = "true" ]; then
      if status_is_fresh "$status_age" && { [ "$REQUIRE_REQUEST_OK" != "true" ] || [ "$request_ok" = "true" ]; }; then
        printf '%s\n' "$last_status"
        return 0
      fi
    fi
    sleep 3
  done
  printf '%s\n' "$last_status" >&2
  return 1
}

device_count=$("$ADB" devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')
if [ "$device_count" -eq 0 ]; then
  echo "No authorized Android device found. Connect the Pixel and allow USB debugging." >&2
  exit 2
fi
if [ "$device_count" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
  echo "Multiple devices found. Set ANDROID_SERIAL before running this script." >&2
  "$ADB" devices >&2
  exit 2
fi
if [ "$START_ON_BOOT" != "true" ]; then
  echo "START_ON_BOOT must be true for APK replacement restore validation. Set RUN_RESTORE_CHECK=false to skip this acceptance step." >&2
  exit 2
fi

if [ ! -f "$APK" ]; then
  echo "APK not found: $APK" >&2
  exit 1
fi

echo "==> Ensuring proxy is running with persisted auto-start config"
ADB="$ADB" \
PKG="$PKG" \
HTTP_PORT="$HTTP_PORT" \
SOCKS_PORT="$SOCKS_PORT" \
BIND_ADDRESS="$BIND_ADDRESS" \
ENABLE_HTTP="$ENABLE_HTTP" \
ENABLE_SOCKS="$ENABLE_SOCKS" \
AUTH_ENABLED="$AUTH_ENABLED" \
USERNAME="$USERNAME" \
PASSWORD="$PASSWORD" \
HEALTH_URL="$HEALTH_URL" \
EXPECTED_STATUS="$EXPECTED_STATUS" \
INTERVAL_SECONDS="$INTERVAL_SECONDS" \
TIMEOUT_SECONDS="$TIMEOUT_SECONDS" \
FAILURE_THRESHOLD="$FAILURE_THRESHOLD" \
START_ON_BOOT="$START_ON_BOOT" \
"$ROOT/scripts/adb_start.sh"

echo "==> Waiting for pre-replace health"
before=$(wait_healthy) || {
  echo "Proxy did not become healthy before restore check." >&2
  exit 1
}
before_start=$(printf '%s\n' "$before" | status_value lastStartAt)
before_restarts=$(printf '%s\n' "$before" | status_value restartCount)
before_auto_start=$(printf '%s\n' "$before" | status_value autoStart)
before_start_on_boot=$(printf '%s\n' "$before" | status_value startOnBoot)
before_restarts_num=${before_restarts:-0}
if ! is_unsigned_int "$before_restarts_num"; then
  before_restarts_num=0
fi
echo "Before replace: lastStartAt=$before_start restartCount=$before_restarts"
if [ "$before_auto_start" != "true" ] || [ "$before_start_on_boot" != "true" ]; then
  echo "Restore config was not armed before package replace: autoStart=$before_auto_start startOnBoot=$before_start_on_boot" >&2
  exit 1
fi

echo "==> Reinstalling APK to trigger MY_PACKAGE_REPLACED restore"
"$ADB" install -r "$APK"

echo "==> Waiting for post-replace restore"
after=$(wait_healthy) || {
  echo "Proxy did not restore after package replace within ${WAIT_SECONDS}s." >&2
  exit 1
}

after_start=$(printf '%s\n' "$after" | status_value lastStartAt)
after_restarts=$(printf '%s\n' "$after" | status_value restartCount)
after_auto_start=$(printf '%s\n' "$after" | status_value autoStart)
after_start_on_boot=$(printf '%s\n' "$after" | status_value startOnBoot)
after_restarts_num=${after_restarts:-0}
if ! is_unsigned_int "$after_restarts_num"; then
  after_restarts_num=0
fi
reason=$(printf '%s\n' "$after" | status_value lastRestartReason)

echo
echo "After replace: lastStartAt=$after_start restartCount=$after_restarts reason=$reason"
if [ "$after_auto_start" != "true" ] || [ "$after_start_on_boot" != "true" ]; then
  echo "Restore config was not preserved after package replace: autoStart=$after_auto_start startOnBoot=$after_start_on_boot" >&2
  exit 1
fi
if [ "$after_start" = "$before_start" ] && [ "$after_restarts_num" -le "$before_restarts_num" ]; then
  echo "Restore status did not show a fresh start after package replace." >&2
  exit 1
fi

echo "Restore check passed."
