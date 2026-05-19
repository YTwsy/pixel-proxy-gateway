#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ADB=${ADB:-adb}
PKG=${PKG:-com.wsy.pixelproxygateway}

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

WAIT_BOOT_SECONDS=${WAIT_BOOT_SECONDS:-240}
WAIT_UNLOCK_SECONDS=${WAIT_UNLOCK_SECONDS:-600}
WAIT_RESTORE_SECONDS=${WAIT_RESTORE_SECONDS:-300}
REQUIRE_REQUEST_OK=${REQUIRE_REQUEST_OK:-false}
STATUS_MAX_AGE_SECONDS=${STATUS_MAX_AGE_SECONDS:-120}
. "$ROOT/scripts/lib_status.sh"

read_status() {
  "$ADB" shell dumpsys activity service "$PKG/.ProxyForegroundService" 2>/dev/null || true
}

connected_device_count() {
  "$ADB" devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }'
}

wait_healthy() {
  deadline=$(( $(date +%s) + WAIT_RESTORE_SECONDS ))
  last_status=""
  while [ "$(date +%s)" -le "$deadline" ]; do
    last_status=$(read_status)
    service_running=$(printf '%s\n' "$last_status" | status_value serviceRunning)
    desired_running=$(printf '%s\n' "$last_status" | status_value desiredRunning)
    proxy_running=$(printf '%s\n' "$last_status" | status_value proxyRunning)
    port_ok=$(printf '%s\n' "$last_status" | status_value portOk)
    request_ok=$(printf '%s\n' "$last_status" | status_value requestOk)
    reason=$(printf '%s\n' "$last_status" | status_value lastRestartReason)
    epoch_ms=$(printf '%s\n' "$last_status" | status_value statusUpdatedAtEpochMillis)
    status_age=$(status_age_seconds "$epoch_ms" || true)
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

wait_device_connected() {
  deadline=$(( $(date +%s) + WAIT_BOOT_SECONDS ))
  while [ "$(date +%s)" -le "$deadline" ]; do
    count=$(connected_device_count || echo 0)
    if [ "$count" -gt 0 ]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_boot_completed() {
  deadline=$(( $(date +%s) + WAIT_BOOT_SECONDS ))
  while [ "$(date +%s)" -le "$deadline" ]; do
    boot_completed=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    if [ "$boot_completed" = "1" ]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

is_user_unlocked() {
  "$ADB" shell dumpsys user 2>/dev/null | grep 'State: RUNNING_UNLOCKED' >/dev/null 2>&1
}

wait_user_unlocked() {
  deadline=$(( $(date +%s) + WAIT_UNLOCK_SECONDS ))
  while [ "$(date +%s)" -le "$deadline" ]; do
    if is_user_unlocked; then
      return 0
    fi
    echo "Waiting for user unlock; unlock the Pixel now to deliver post-unlock BOOT_COMPLETED." >&2
    sleep 3
  done
  return 1
}

device_count=$(connected_device_count)
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
  echo "START_ON_BOOT must be true for reboot restore validation." >&2
  exit 2
fi

echo "==> Ensuring proxy is running with persisted post-unlock boot config"
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

echo "==> Waiting for pre-reboot health"
before=$(wait_healthy) || {
  echo "Proxy did not become healthy before reboot restore check." >&2
  exit 1
}
before_start=$(printf '%s\n' "$before" | status_value lastStartAt)
before_restarts=$(printf '%s\n' "$before" | status_value restartCount)
before_auto_start=$(printf '%s\n' "$before" | status_value autoStart)
before_start_on_boot=$(printf '%s\n' "$before" | status_value startOnBoot)
echo "Before reboot: lastStartAt=$before_start restartCount=$before_restarts"
if [ "$before_auto_start" != "true" ] || [ "$before_start_on_boot" != "true" ]; then
  echo "Boot restore config was not armed before reboot: autoStart=$before_auto_start startOnBoot=$before_start_on_boot" >&2
  exit 1
fi

echo "==> Rebooting device"
"$ADB" reboot
if ! wait_device_connected; then
  echo "Device did not reconnect to ADB within ${WAIT_BOOT_SECONDS}s after reboot." >&2
  exit 1
fi

echo "==> Waiting for Android boot completion"
if ! wait_boot_completed; then
  echo "Device did not report sys.boot_completed=1 within ${WAIT_BOOT_SECONDS}s." >&2
  exit 1
fi

echo "==> Waiting for post-unlock BOOT_COMPLETED restore"
echo "If the Pixel is at the lock screen, unlock it now; this app restores after credential unlock, not Direct Boot." >&2
if ! wait_user_unlocked; then
  echo "Device user did not become RUNNING_UNLOCKED within ${WAIT_UNLOCK_SECONDS}s after boot completion." >&2
  exit 1
fi
echo "User is unlocked; waiting for boot restore health." >&2
after=$(wait_healthy) || {
  echo "Proxy did not restore after reboot within ${WAIT_RESTORE_SECONDS}s after boot completion." >&2
  exit 1
}

after_start=$(printf '%s\n' "$after" | status_value lastStartAt)
after_restarts=$(printf '%s\n' "$after" | status_value restartCount)
after_auto_start=$(printf '%s\n' "$after" | status_value autoStart)
after_start_on_boot=$(printf '%s\n' "$after" | status_value startOnBoot)
reason=$(printf '%s\n' "$after" | status_value lastRestartReason)

echo
echo "After reboot: lastStartAt=$after_start restartCount=$after_restarts reason=$reason"
if [ "$after_auto_start" != "true" ] || [ "$after_start_on_boot" != "true" ]; then
  echo "Boot restore config was not preserved after reboot: autoStart=$after_auto_start startOnBoot=$after_start_on_boot" >&2
  exit 1
fi
if [ "$after_start" = "$before_start" ]; then
  echo "Reboot restore status did not show a fresh start after boot." >&2
  exit 1
fi

echo "Reboot restore check passed."
