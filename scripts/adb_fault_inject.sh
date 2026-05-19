#!/usr/bin/env sh
set -eu

ADB=${ADB:-adb}
PKG=${PKG:-com.wsy.pixelproxygateway}
WAIT_SECONDS=${WAIT_SECONDS:-90}
STATUS_MAX_AGE_SECONDS=${STATUS_MAX_AGE_SECONDS:-120}
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT/scripts/lib_status.sh"

read_status() {
  "$ADB" shell dumpsys activity service "$PKG/.ProxyForegroundService" 2>/dev/null || true
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

before=$(read_status)
before_running=$(printf '%s\n' "$before" | status_value proxyRunning)
before_restarts=$(printf '%s\n' "$before" | status_value restartCount)
before_restarts_num=${before_restarts:-0}
if ! is_unsigned_int "$before_restarts_num"; then
  before_restarts_num=0
fi
before_pid=$(printf '%s\n' "$before" | status_value proxyPid)
before_epoch_ms=$(printf '%s\n' "$before" | status_value statusUpdatedAtEpochMillis)
before_age=$(status_age_seconds "$before_epoch_ms" || true)

if ! status_is_fresh "$before_age"; then
  echo "Runtime status is stale or unreadable before fault injection: statusAgeSeconds=${before_age:-unknown}, max=${STATUS_MAX_AGE_SECONDS}." >&2
  printf '%s\n' "$before" >&2
  exit 1
fi

if [ "$before_running" != "true" ]; then
  echo "Proxy is not running. Start it first with scripts/adb_verify_device.sh or scripts/adb_start.sh." >&2
  printf '%s\n' "$before" >&2
  exit 1
fi

echo "Before fault: pid=$before_pid restartCount=$before_restarts"
echo "==> Killing GOST child process on device"
if is_unsigned_int "$before_pid" && [ "$before_pid" != "0" ] && [ "$before_pid" != "-1" ]; then
  "$ADB" shell run-as "$PKG" kill "$before_pid" || "$ADB" shell "kill $before_pid" || true
else
  "$ADB" shell run-as "$PKG" sh -c 'pid=$(pidof libgost.so 2>/dev/null || pidof gost 2>/dev/null || true); [ -n "$pid" ] && kill $pid' || true
fi

deadline=$(( $(date +%s) + WAIT_SECONDS ))
last_status=""
while [ "$(date +%s)" -le "$deadline" ]; do
  last_status=$(read_status)
  running=$(printf '%s\n' "$last_status" | status_value proxyRunning)
  port_ok=$(printf '%s\n' "$last_status" | status_value portOk)
  restarts=$(printf '%s\n' "$last_status" | status_value restartCount)
  reason=$(printf '%s\n' "$last_status" | status_value lastRestartReason)
  pid=$(printf '%s\n' "$last_status" | status_value proxyPid)
  status_updated_at_epoch_ms=$(printf '%s\n' "$last_status" | status_value statusUpdatedAtEpochMillis)
  status_age=$(status_age_seconds "$status_updated_at_epoch_ms" || true)
  echo "proxy=$running port=$port_ok pid=$pid restartCount=$restarts status_age=${status_age:-unknown}s reason=$reason"
  if [ "$running" = "true" ] && [ "$port_ok" = "true" ] && status_is_fresh "$status_age" && is_unsigned_int "$restarts" && [ "$restarts" -gt "$before_restarts_num" ]; then
    echo
    echo "Fault injection passed."
    exit 0
  fi
  sleep 3
done

echo
echo "Fault injection did not recover within ${WAIT_SECONDS}s." >&2
printf '%s\n' "$last_status" >&2
exit 1
