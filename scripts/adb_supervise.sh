#!/usr/bin/env sh
set -u

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
EXPECTED_PROXY_IP=${EXPECTED_PROXY_IP:-}
REQUIRE_PROXY_DIFF_FROM_DIRECT=${REQUIRE_PROXY_DIFF_FROM_DIRECT:-false}
EXPECTED_STATUS=${EXPECTED_STATUS:-204}
INTERVAL_SECONDS=${INTERVAL_SECONDS:-300}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-15}
FAILURE_THRESHOLD=${FAILURE_THRESHOLD:-2}
START_ON_BOOT=${START_ON_BOOT:-true}

DURATION_SECONDS=${DURATION_SECONDS:-28800}
CHECK_INTERVAL_SECONDS=${CHECK_INTERVAL_SECONDS:-60}
SUPERVISOR_FAILURE_THRESHOLD=${SUPERVISOR_FAILURE_THRESHOLD:-3}
REQUIRE_REQUEST_OK=${REQUIRE_REQUEST_OK:-false}
LAN_CHECK=${LAN_CHECK:-false}
REQUIRE_LAN_OK=${REQUIRE_LAN_OK:-false}
PIXEL_IP=${PIXEL_IP:-}
COLLECT_ON_RECOVERY=${COLLECT_ON_RECOVERY:-true}
OUT_DIR=${OUT_DIR:-$ROOT/reports/supervise-$(date +%Y%m%d-%H%M%S)}
STATUS_MAX_AGE_SECONDS=${STATUS_MAX_AGE_SECONDS:-120}
. "$ROOT/scripts/lib_status.sh"

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

mkdir -p "$OUT_DIR"

read_status() {
  "$ADB" shell dumpsys activity service "$PKG/.ProxyForegroundService" 2>/dev/null || true
}

start_proxy() {
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
}

collect_diagnostics() {
  stamp=$1
  if [ "$COLLECT_ON_RECOVERY" != "true" ]; then
    return 0
  fi
  diag_dir="$OUT_DIR/diagnostics-$stamp"
  ADB="$ADB" PKG="$PKG" HTTP_PORT="$HTTP_PORT" SOCKS_PORT="$SOCKS_PORT" OUT_DIR="$diag_dir" \
    "$ROOT/scripts/adb_collect_diagnostics.sh" >/dev/null 2>&1 || true
}

run_lan_check() {
  stamp=$1
  if [ "$LAN_CHECK" != "true" ] && [ "$REQUIRE_LAN_OK" != "true" ]; then
    return 0
  fi
  lan_file="$OUT_DIR/$stamp-lan.txt"
  if [ -z "$PIXEL_IP" ]; then
    PIXEL_IP=$("$ADB" shell "ip -f inet addr show 2>/dev/null | sed -n 's/.*inet \([0-9.]*\)\/.*/\1/p' | grep -v '^127\.' | head -1" 2>/dev/null | tr -d '\r' || true)
  fi
  if [ -z "$PIXEL_IP" ]; then
    echo "PIXEL_IP unavailable; LAN smoke skipped." > "$lan_file"
    return 2
  fi
  PIXEL_IP="$PIXEL_IP" HTTP_PORT="$HTTP_PORT" SOCKS_PORT="$SOCKS_PORT" \
    ENABLE_HTTP="$ENABLE_HTTP" ENABLE_SOCKS="$ENABLE_SOCKS" \
    AUTH_ENABLED="$AUTH_ENABLED" USERNAME="$USERNAME" PASSWORD="$PASSWORD" \
    HEALTH_URL="$HEALTH_URL" EXPECTED_PROXY_IP="$EXPECTED_PROXY_IP" \
    REQUIRE_PROXY_DIFF_FROM_DIRECT="$REQUIRE_PROXY_DIFF_FROM_DIRECT" \
    "$ROOT/scripts/lan_smoke.sh" > "$lan_file" 2>&1
}

summary="$OUT_DIR/summary.tsv"
printf 'timestamp\thealthy\tfailureCount\trecoveryCount\tstatusUpdatedAt\tstatusUpdatedAtEpochMillis\tstatusAgeSeconds\tautoStart\tstartOnBoot\tserviceRunning\tdesiredRunning\tproxyRunning\tportOk\trequestOk\tlanExit\trestartCount\tlastRestartReason\tlastError\taction\n' > "$summary"

failure_count=0
recovery_count=0
end_at=$(( $(date +%s) + DURATION_SECONDS ))

echo "Writing supervisor samples to $OUT_DIR"
echo "Duration: ${DURATION_SECONDS}s; interval: ${CHECK_INTERVAL_SECONDS}s; recovery threshold: $SUPERVISOR_FAILURE_THRESHOLD"

while [ "$(date +%s)" -le "$end_at" ]; do
  stamp=$(date +%Y%m%d-%H%M%S)
  status_file="$OUT_DIR/$stamp-status.txt"
  status=$(read_status)
  printf '%s\n' "$status" > "$status_file"

  status_updated_at=$(printf '%s\n' "$status" | status_value statusUpdatedAt)
  status_updated_at_epoch_ms=$(printf '%s\n' "$status" | status_value statusUpdatedAtEpochMillis)
  status_age=$(status_age_seconds "$status_updated_at_epoch_ms" || true)
  auto_start=$(printf '%s\n' "$status" | status_value autoStart)
  start_on_boot=$(printf '%s\n' "$status" | status_value startOnBoot)
  service_running=$(printf '%s\n' "$status" | status_value serviceRunning)
  desired_running=$(printf '%s\n' "$status" | status_value desiredRunning)
  proxy_running=$(printf '%s\n' "$status" | status_value proxyRunning)
  port_ok=$(printf '%s\n' "$status" | status_value portOk)
  request_ok=$(printf '%s\n' "$status" | status_value requestOk)
  restart_count=$(printf '%s\n' "$status" | status_value restartCount)
  last_restart_reason=$(printf '%s\n' "$status" | status_value lastRestartReason)
  last_error=$(printf '%s\n' "$status" | status_value lastError)

  lan_exit=0
  run_lan_check "$stamp"
  lan_exit=$?

  healthy=true
  action=none
  if [ "$service_running" != "true" ] || [ "$desired_running" != "true" ] || [ "$proxy_running" != "true" ] || [ "$port_ok" != "true" ]; then
    healthy=false
  fi
  if [ "$REQUIRE_REQUEST_OK" = "true" ] && [ "$request_ok" != "true" ]; then
    healthy=false
  fi
  if [ "$REQUIRE_LAN_OK" = "true" ] && [ "$lan_exit" -ne 0 ]; then
    healthy=false
  fi
  if ! is_unsigned_int "$status_age" || [ "$status_age" -gt "$STATUS_MAX_AGE_SECONDS" ]; then
    healthy=false
  fi

  if [ "$healthy" = "true" ]; then
    failure_count=0
  else
    failure_count=$((failure_count + 1))
  fi

  if [ "$failure_count" -ge "$SUPERVISOR_FAILURE_THRESHOLD" ]; then
    action=recover_start
    collect_diagnostics "$stamp"
    if start_proxy > "$OUT_DIR/$stamp-recover.txt" 2>&1; then
      action=recover_start_ok
    else
      action=recover_start_failed
    fi
    recovery_count=$((recovery_count + 1))
    failure_count=0
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$stamp" \
    "$healthy" \
    "$failure_count" \
    "$recovery_count" \
    "$status_updated_at" \
    "$status_updated_at_epoch_ms" \
    "$status_age" \
    "$auto_start" \
    "$start_on_boot" \
    "$service_running" \
    "$desired_running" \
    "$proxy_running" \
    "$port_ok" \
    "$request_ok" \
    "$lan_exit" \
    "$restart_count" \
    "$last_restart_reason" \
    "$last_error" \
    "$action" >> "$summary"

  echo "sample=$stamp healthy=$healthy failures=$failure_count recoveries=$recovery_count status_updated=$status_updated_at status_age=${status_age:-unknown}s auto_start=$auto_start start_on_boot=$start_on_boot proxy=$proxy_running port=$port_ok request=$request_ok lan_exit=$lan_exit action=$action error=$last_error"

  now=$(date +%s)
  [ "$now" -ge "$end_at" ] && break
  sleep "$CHECK_INTERVAL_SECONDS"
done

echo
echo "ADB supervisor finished."
echo "Summary: $summary"
