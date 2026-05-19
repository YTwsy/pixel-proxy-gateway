#!/usr/bin/env sh
set -u

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
EXPECTED_PROXY_IP=${EXPECTED_PROXY_IP:-}
REQUIRE_PROXY_DIFF_FROM_DIRECT=${REQUIRE_PROXY_DIFF_FROM_DIRECT:-false}
EXPECTED_STATUS=${EXPECTED_STATUS:-204}
INTERVAL_SECONDS=${INTERVAL_SECONDS:-300}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-15}
FAILURE_THRESHOLD=${FAILURE_THRESHOLD:-2}
START_ON_BOOT=${START_ON_BOOT:-true}

RUN_FAULT_INJECT=${RUN_FAULT_INJECT:-true}
RUN_RESTORE_CHECK=${RUN_RESTORE_CHECK:-true}
RUN_REBOOT_RESTORE_CHECK=${RUN_REBOOT_RESTORE_CHECK:-false}
RUN_LAN_SMOKE=${RUN_LAN_SMOKE:-true}
RUN_SUPERVISOR_SMOKE=${RUN_SUPERVISOR_SMOKE:-false}
REQUIRE_REQUEST_OK=${REQUIRE_REQUEST_OK:-false}
COLLECT_DIAGNOSTICS_ON_FAILURE=${COLLECT_DIAGNOSTICS_ON_FAILURE:-true}
PIXEL_IP=${PIXEL_IP:-}
OUT_DIR=${OUT_DIR:-$ROOT/reports/acceptance-$(date +%Y%m%d-%H%M%S)}
STATUS_MAX_AGE_SECONDS=${STATUS_MAX_AGE_SECONDS:-120}

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
summary="$OUT_DIR/summary.tsv"
printf 'step\texit\tlog\tdiagnostics\n' > "$summary"

collect_failure_diagnostics() {
  step=$1
  if [ "$COLLECT_DIAGNOSTICS_ON_FAILURE" != "true" ]; then
    return 0
  fi
  case "$step" in
    verify-apk) return 0 ;;
  esac
  diag_dir="$OUT_DIR/diagnostics-$step-$(date +%Y%m%d-%H%M%S)"
  ADB="$ADB" PKG="$PKG" HTTP_PORT="$HTTP_PORT" SOCKS_PORT="$SOCKS_PORT" OUT_DIR="$diag_dir" \
    "$ROOT/scripts/adb_collect_diagnostics.sh" >/dev/null 2>&1 || true
  if [ -d "$diag_dir" ]; then
    printf '%s\n' "$diag_dir"
  fi
}

run_step() {
  name=$1
  shift
  log="$OUT_DIR/$name.log"
  echo "==> $name"
  {
    echo "step=$name"
    echo "command=<redacted>"
    echo
    "$@"
  } > "$log" 2>&1
  code=$?
  diagnostics=""
  if [ "$code" -ne 0 ]; then
    diagnostics=$(collect_failure_diagnostics "$name")
    printf '%s\t%s\t%s\t%s\n' "$name" "$code" "$log" "$diagnostics" >> "$summary"
    echo "$name failed. See $log" >&2
    if [ -n "$diagnostics" ]; then
      echo "Diagnostics: $diagnostics" >&2
    fi
    return "$code"
  fi
  printf '%s\t%s\t%s\t%s\n' "$name" "$code" "$log" "$diagnostics" >> "$summary"
  echo "$name passed."
  return 0
}

run_step verify-apk "$ROOT/scripts/verify_apk.sh" || exit $?

run_step adb-verify-device env \
  ADB="$ADB" \
  PKG="$PKG" \
  APK="$APK" \
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
  REQUIRE_REQUEST_OK="$REQUIRE_REQUEST_OK" \
  STATUS_MAX_AGE_SECONDS="$STATUS_MAX_AGE_SECONDS" \
  "$ROOT/scripts/adb_verify_device.sh" || exit $?

if [ "$RUN_RESTORE_CHECK" = "true" ]; then
  run_step adb-restore-check env \
    ADB="$ADB" \
    PKG="$PKG" \
    APK="$APK" \
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
    REQUIRE_REQUEST_OK="$REQUIRE_REQUEST_OK" \
    STATUS_MAX_AGE_SECONDS="$STATUS_MAX_AGE_SECONDS" \
    "$ROOT/scripts/adb_restore_check.sh" || exit $?
else
  printf '%s\t%s\t%s\t%s\n' "adb-restore-check" "skipped" "" "" >> "$summary"
fi

if [ "$RUN_FAULT_INJECT" = "true" ]; then
  run_step adb-fault-inject env \
    ADB="$ADB" \
    PKG="$PKG" \
    STATUS_MAX_AGE_SECONDS="$STATUS_MAX_AGE_SECONDS" \
    "$ROOT/scripts/adb_fault_inject.sh" || exit $?
else
  printf '%s\t%s\t%s\t%s\n' "adb-fault-inject" "skipped" "" "" >> "$summary"
fi

if [ "$RUN_REBOOT_RESTORE_CHECK" = "true" ]; then
  run_step adb-reboot-restore-check env \
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
    REQUIRE_REQUEST_OK="$REQUIRE_REQUEST_OK" \
    STATUS_MAX_AGE_SECONDS="$STATUS_MAX_AGE_SECONDS" \
    "$ROOT/scripts/adb_reboot_restore_check.sh" || exit $?
else
  printf '%s\t%s\t%s\t%s\n' "adb-reboot-restore-check" "skipped" "" "" >> "$summary"
fi

if [ "$RUN_LAN_SMOKE" = "true" ]; then
  run_step lan-smoke env \
    ADB="$ADB" \
    PIXEL_IP="$PIXEL_IP" \
    HTTP_PORT="$HTTP_PORT" \
    SOCKS_PORT="$SOCKS_PORT" \
    ENABLE_HTTP="$ENABLE_HTTP" \
    ENABLE_SOCKS="$ENABLE_SOCKS" \
    AUTH_ENABLED="$AUTH_ENABLED" \
    USERNAME="$USERNAME" \
    PASSWORD="$PASSWORD" \
    HEALTH_URL="$HEALTH_URL" \
    EXPECTED_PROXY_IP="$EXPECTED_PROXY_IP" \
    REQUIRE_PROXY_DIFF_FROM_DIRECT="$REQUIRE_PROXY_DIFF_FROM_DIRECT" \
    "$ROOT/scripts/lan_smoke.sh" || exit $?
else
  printf '%s\t%s\t%s\t%s\n' "lan-smoke" "skipped" "" "" >> "$summary"
fi

if [ "$RUN_SUPERVISOR_SMOKE" = "true" ]; then
  run_step adb-supervise-smoke env \
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
    EXPECTED_PROXY_IP="$EXPECTED_PROXY_IP" \
    REQUIRE_PROXY_DIFF_FROM_DIRECT="$REQUIRE_PROXY_DIFF_FROM_DIRECT" \
    EXPECTED_STATUS="$EXPECTED_STATUS" \
    INTERVAL_SECONDS="$INTERVAL_SECONDS" \
    TIMEOUT_SECONDS="$TIMEOUT_SECONDS" \
    FAILURE_THRESHOLD="$FAILURE_THRESHOLD" \
    START_ON_BOOT="$START_ON_BOOT" \
    PIXEL_IP="$PIXEL_IP" \
    DURATION_SECONDS=120 \
    CHECK_INTERVAL_SECONDS=30 \
    LAN_CHECK=true \
    STATUS_MAX_AGE_SECONDS="$STATUS_MAX_AGE_SECONDS" \
    OUT_DIR="$OUT_DIR/supervisor-smoke" \
    "$ROOT/scripts/adb_supervise.sh" || exit $?
else
  printf '%s\t%s\t%s\t%s\n' "adb-supervise-smoke" "skipped" "" "" >> "$summary"
fi

echo
echo "Acceptance check passed."
echo "Summary: $summary"
