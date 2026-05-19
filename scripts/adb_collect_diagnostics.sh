#!/usr/bin/env sh
set -u

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ADB=${ADB:-adb}
PKG=${PKG:-com.wsy.pixelproxygateway}
HTTP_PORT=${HTTP_PORT:-8080}
SOCKS_PORT=${SOCKS_PORT:-1080}
OUT_DIR=${OUT_DIR:-$ROOT/reports/diagnostics-$(date +%Y%m%d-%H%M%S)}
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

run_capture() {
  name=$1
  shift
  {
    echo "== $name =="
    echo "command=$*"
    echo
    "$@" 2>&1
    code=$?
    echo
    echo "exit=$code"
  } > "$OUT_DIR/$name.txt" 2>&1
}

run_capture adb-devices "$ADB" devices -l
run_capture device-props "$ADB" shell "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.cpu.abilist; getprop ro.build.fingerprint"
run_capture package "$ADB" shell dumpsys package "$PKG"
run_capture service "$ADB" shell dumpsys activity service "$PKG/.ProxyForegroundService"
run_capture status-provider "$ADB" shell content query --uri "content://$PKG.status/status"
run_capture appops "$ADB" shell appops get "$PKG"
run_capture standby "$ADB" shell am get-standby-bucket "$PKG"
run_capture deviceidle "$ADB" shell dumpsys deviceidle whitelist
run_capture power "$ADB" shell dumpsys power
run_capture battery "$ADB" shell dumpsys battery
run_capture connectivity "$ADB" shell dumpsys connectivity
run_capture netpolicy "$ADB" shell dumpsys netpolicy
run_capture addresses "$ADB" shell ip -f inet addr show
run_capture routes "$ADB" shell ip route
run_capture pids "$ADB" shell "pidof gost 2>/dev/null || pidof libgost.so 2>/dev/null || true"
run_capture sockets "$ADB" shell "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -Ei '$(printf '%04X' "$HTTP_PORT")|$(printf '%04X' "$SOCKS_PORT")' || true"
run_capture logcat "$ADB" logcat -d -t 1000

service_file="$OUT_DIR/service.txt"
summary="$OUT_DIR/summary.txt"
status_text=$(sed -n '/statusUpdatedAt=/,/appVersion=/p' "$service_file")
status_updated_at_epoch_ms=$(printf '%s\n' "$status_text" | status_value statusUpdatedAtEpochMillis)
status_age=$(status_age_seconds "$status_updated_at_epoch_ms" || true)
{
  echo "diagnostics_dir=$OUT_DIR"
  echo "android_serial=${ANDROID_SERIAL:-}"
  echo "package=$PKG"
  echo "httpPort=$HTTP_PORT"
  echo "socksPort=$SOCKS_PORT"
  echo "statusUpdatedAt=$(printf '%s\n' "$status_text" | status_value statusUpdatedAt)"
  echo "statusUpdatedAtEpochMillis=$status_updated_at_epoch_ms"
  echo "statusAgeSeconds=$status_age"
  echo "autoStart=$(printf '%s\n' "$status_text" | status_value autoStart)"
  echo "startOnBoot=$(printf '%s\n' "$status_text" | status_value startOnBoot)"
  echo "serviceRunning=$(printf '%s\n' "$status_text" | status_value serviceRunning)"
  echo "desiredRunning=$(printf '%s\n' "$status_text" | status_value desiredRunning)"
  echo "proxyRunning=$(printf '%s\n' "$status_text" | status_value proxyRunning)"
  echo "proxyPid=$(printf '%s\n' "$status_text" | status_value proxyPid)"
  echo "portOk=$(printf '%s\n' "$status_text" | status_value portOk)"
  echo "requestOk=$(printf '%s\n' "$status_text" | status_value requestOk)"
  echo "wakeLockHeld=$(printf '%s\n' "$status_text" | status_value wakeLockHeld)"
  echo "batteryIgnoringOptimizations=$(printf '%s\n' "$status_text" | status_value batteryIgnoringOptimizations)"
  echo "restartCount=$(printf '%s\n' "$status_text" | status_value restartCount)"
  echo "lastRestartReason=$(printf '%s\n' "$status_text" | status_value lastRestartReason)"
  echo "lastError=$(printf '%s\n' "$status_text" | status_value lastError)"
} > "$summary"

echo "Diagnostics written to $OUT_DIR"
cat "$summary"
