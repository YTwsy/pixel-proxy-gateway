#!/usr/bin/env sh
set -u

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ADB=${ADB:-adb}
PKG=${PKG:-com.wsy.pixelproxygateway}
HTTP_PORT=${HTTP_PORT:-8080}
SOCKS_PORT=${SOCKS_PORT:-1080}
ENABLE_HTTP=${ENABLE_HTTP:-true}
ENABLE_SOCKS=${ENABLE_SOCKS:-true}
EXPECTED_PROXY_IP=${EXPECTED_PROXY_IP:-}
REQUIRE_PROXY_DIFF_FROM_DIRECT=${REQUIRE_PROXY_DIFF_FROM_DIRECT:-false}
PIXEL_IP=${PIXEL_IP:-}
DURATION_SECONDS=${DURATION_SECONDS:-28800}
INTERVAL_SECONDS=${INTERVAL_SECONDS:-300}
OUT_DIR=${OUT_DIR:-$ROOT/reports/stability-$(date +%Y%m%d-%H%M%S)}
STATUS_MAX_AGE_SECONDS=${STATUS_MAX_AGE_SECONDS:-120}
. "$ROOT/scripts/lib_status.sh"

mkdir -p "$OUT_DIR"

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

if [ -z "$PIXEL_IP" ]; then
  PIXEL_IP=$("$ADB" shell "ip -f inet addr show 2>/dev/null | sed -n 's/.*inet \([0-9.]*\)\/.*/\1/p' | grep -v '^127\.' | head -1" 2>/dev/null | tr -d '\r' || true)
fi

end_at=$(( $(date +%s) + DURATION_SECONDS ))
summary="$OUT_DIR/summary.tsv"
printf 'timestamp\tadb_exit\tlan_exit\tstatusUpdatedAt\tstatusUpdatedAtEpochMillis\tstatusAgeSeconds\tautoStart\tstartOnBoot\tproxyRunning\tportOk\trequestOk\twakeLockHeld\tbatteryIgnoringOptimizations\trestartCount\tlastRestartReason\tlastError\n' > "$summary"

sample_once() {
  stamp=$(date +%Y%m%d-%H%M%S)
  adb_file="$OUT_DIR/$stamp-adb.txt"
  lan_file="$OUT_DIR/$stamp-lan.txt"

  {
    echo "timestamp=$stamp"
    echo "android_serial=${ANDROID_SERIAL:-}"
    echo
    echo "== adb devices =="
    "$ADB" devices
    echo
    echo "== service dumpsys =="
    "$ADB" shell dumpsys activity service "$PKG/.ProxyForegroundService" 2>&1 || true
    echo
    echo "== status provider =="
    "$ADB" shell content query --uri "content://$PKG.status/status" 2>&1 || true
    echo
    echo "== pids =="
    "$ADB" shell "pidof gost 2>/dev/null || pidof libgost.so 2>/dev/null || true"
    echo
    echo "== listening sockets =="
    "$ADB" shell "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -Ei '$(printf '%04X' "$HTTP_PORT")|$(printf '%04X' "$SOCKS_PORT")' || true"
    echo
    echo "== device idle whitelist =="
    "$ADB" shell dumpsys deviceidle whitelist 2>/dev/null | grep "$PKG" || true
  } > "$adb_file" 2>&1

  status_text=$(sed -n '/== service dumpsys ==/,/== status provider ==/p' "$adb_file")
  status_updated_at=$(printf '%s\n' "$status_text" | status_value statusUpdatedAt)
  status_updated_at_epoch_ms=$(printf '%s\n' "$status_text" | status_value statusUpdatedAtEpochMillis)
  status_age=$(status_age_seconds "$status_updated_at_epoch_ms" || true)
  auto_start=$(printf '%s\n' "$status_text" | status_value autoStart)
  start_on_boot=$(printf '%s\n' "$status_text" | status_value startOnBoot)
  proxy_running=$(printf '%s\n' "$status_text" | status_value proxyRunning)
  port_ok=$(printf '%s\n' "$status_text" | status_value portOk)
  request_ok=$(printf '%s\n' "$status_text" | status_value requestOk)
  wake_lock_held=$(printf '%s\n' "$status_text" | status_value wakeLockHeld)
  battery_ignoring=$(printf '%s\n' "$status_text" | status_value batteryIgnoringOptimizations)
  restart_count=$(printf '%s\n' "$status_text" | status_value restartCount)
  last_restart_reason=$(printf '%s\n' "$status_text" | status_value lastRestartReason)
  last_error=$(printf '%s\n' "$status_text" | status_value lastError)
  if [ -n "$proxy_running$port_ok$request_ok$restart_count" ]; then
    adb_exit=0
  else
    adb_exit=1
  fi
  if ! is_unsigned_int "$status_age" || [ "$status_age" -gt "$STATUS_MAX_AGE_SECONDS" ]; then
    adb_exit=1
  fi

  lan_exit=0
  if [ -n "$PIXEL_IP" ]; then
    PIXEL_IP="$PIXEL_IP" \
    HTTP_PORT="$HTTP_PORT" \
    SOCKS_PORT="$SOCKS_PORT" \
    ENABLE_HTTP="$ENABLE_HTTP" \
    ENABLE_SOCKS="$ENABLE_SOCKS" \
    EXPECTED_PROXY_IP="$EXPECTED_PROXY_IP" \
    REQUIRE_PROXY_DIFF_FROM_DIRECT="$REQUIRE_PROXY_DIFF_FROM_DIRECT" \
    "$ROOT/scripts/lan_smoke.sh" > "$lan_file" 2>&1
    lan_exit=$?
  else
    echo "PIXEL_IP unavailable; LAN smoke skipped." > "$lan_file"
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$stamp" \
    "$adb_exit" \
    "$lan_exit" \
    "$status_updated_at" \
    "$status_updated_at_epoch_ms" \
    "$status_age" \
    "$auto_start" \
    "$start_on_boot" \
    "$proxy_running" \
    "$port_ok" \
    "$request_ok" \
    "$wake_lock_held" \
    "$battery_ignoring" \
    "$restart_count" \
    "$last_restart_reason" \
    "$last_error" >> "$summary"

  echo "sample=$stamp adb_exit=$adb_exit lan_exit=$lan_exit status_updated=$status_updated_at status_age=${status_age:-unknown}s auto_start=$auto_start start_on_boot=$start_on_boot proxy=$proxy_running port=$port_ok request=$request_ok wake=$wake_lock_held battery_ignoring=$battery_ignoring restarts=$restart_count"
}

echo "Writing stability samples to $OUT_DIR"
echo "Duration: ${DURATION_SECONDS}s; interval: ${INTERVAL_SECONDS}s; Pixel IP: ${PIXEL_IP:-unknown}"

while [ "$(date +%s)" -le "$end_at" ]; do
  sample_once
  now=$(date +%s)
  [ "$now" -ge "$end_at" ] && break
  sleep "$INTERVAL_SECONDS"
done

echo
echo "Stability monitor finished."
echo "Summary: $summary"
