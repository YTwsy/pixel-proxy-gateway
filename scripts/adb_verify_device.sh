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

INSTALL=${INSTALL:-true}
BOOTSTRAP=${BOOTSTRAP:-true}
START=${START:-true}
WAIT_SECONDS=${WAIT_SECONDS:-90}
REQUIRE_REQUEST_OK=${REQUIRE_REQUEST_OK:-false}
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

abi_list=$("$ADB" shell getprop ro.product.cpu.abilist | tr -d '\r')
case ",$abi_list," in
  *,arm64-v8a,*) ;;
  *)
    echo "Connected device does not report arm64-v8a support: $abi_list" >&2
    echo "This APK currently embeds only the Android arm64 GOST binary." >&2
    exit 2
    ;;
esac

if [ "$INSTALL" = "true" ]; then
  echo "==> Installing $APK"
  "$ADB" install -r "$APK"
fi

if [ "$BOOTSTRAP" = "true" ]; then
  echo "==> Applying ADB bootstrap hints"
  ADB="$ADB" PKG="$PKG" "$ROOT/scripts/adb_bootstrap.sh"
fi

if [ "$START" = "true" ]; then
  echo "==> Starting Pixel Proxy Gateway"
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
fi

read_status() {
  "$ADB" shell dumpsys activity service "$PKG/.ProxyForegroundService" 2>/dev/null || true
}

deadline=$(( $(date +%s) + WAIT_SECONDS ))
healthy=false
last_status=""
echo "==> Waiting up to ${WAIT_SECONDS}s for service health"
while [ "$(date +%s)" -le "$deadline" ]; do
  last_status=$(read_status)
  service_running=$(printf '%s\n' "$last_status" | status_value serviceRunning)
  desired_running=$(printf '%s\n' "$last_status" | status_value desiredRunning)
  proxy_running=$(printf '%s\n' "$last_status" | status_value proxyRunning)
  port_ok=$(printf '%s\n' "$last_status" | status_value portOk)
  request_ok=$(printf '%s\n' "$last_status" | status_value requestOk)
  last_error=$(printf '%s\n' "$last_status" | status_value lastError)
  wake_lock_held=$(printf '%s\n' "$last_status" | status_value wakeLockHeld)
  battery_ignoring=$(printf '%s\n' "$last_status" | status_value batteryIgnoringOptimizations)
  status_updated_at_epoch_ms=$(printf '%s\n' "$last_status" | status_value statusUpdatedAtEpochMillis)
  status_age=$(status_age_seconds "$status_updated_at_epoch_ms" || true)

  echo "service=$service_running desired=$desired_running proxy=$proxy_running port=$port_ok request=$request_ok status_age=${status_age:-unknown}s wake=$wake_lock_held battery_ignoring=$battery_ignoring error=$last_error"
  if [ "$service_running" = "true" ] && [ "$desired_running" = "true" ] && [ "$proxy_running" = "true" ] && [ "$port_ok" = "true" ]; then
    if status_is_fresh "$status_age" && { [ "$REQUIRE_REQUEST_OK" != "true" ] || [ "$request_ok" = "true" ]; }; then
      healthy=true
      break
    fi
  fi
  sleep 3
done

echo
echo "==> Final status"
printf '%s\n' "$last_status"

if [ "$healthy" != "true" ]; then
  echo "Service did not become healthy within ${WAIT_SECONDS}s." >&2
  exit 1
fi

gost_tag=$(printf '%s\n' "$last_status" | status_value gostTag)
gost_sha=$(printf '%s\n' "$last_status" | status_value gostSha256)
gost_path=$(printf '%s\n' "$last_status" | status_value gostPath)
native_library_dir=$(printf '%s\n' "$last_status" | status_value nativeLibraryDir)
wake_lock_held=$(printf '%s\n' "$last_status" | status_value wakeLockHeld)
battery_ignoring=$(printf '%s\n' "$last_status" | status_value batteryIgnoringOptimizations)
status_updated_at=$(printf '%s\n' "$last_status" | status_value statusUpdatedAt)
status_updated_at_epoch_ms=$(printf '%s\n' "$last_status" | status_value statusUpdatedAtEpochMillis)
status_age=$(status_age_seconds "$status_updated_at_epoch_ms" || true)
status_auto_start=$(printf '%s\n' "$last_status" | status_value autoStart)
status_start_on_boot=$(printf '%s\n' "$last_status" | status_value startOnBoot)

if [ -z "$status_updated_at" ]; then
  echo "Runtime status did not report statusUpdatedAt." >&2
  exit 1
fi
if [ -z "$status_updated_at_epoch_ms" ]; then
  echo "Runtime status did not report statusUpdatedAtEpochMillis." >&2
  exit 1
fi
if ! status_is_fresh "$status_age"; then
  echo "Runtime status is stale or unreadable: statusAgeSeconds=${status_age:-unknown}, max=${STATUS_MAX_AGE_SECONDS}." >&2
  exit 1
fi
if [ "$status_auto_start" != "true" ]; then
  echo "Runtime status did not report autoStart=true after start: $status_auto_start" >&2
  exit 1
fi
if [ "$status_start_on_boot" != "$START_ON_BOOT" ]; then
  echo "Runtime status startOnBoot mismatch: expected=$START_ON_BOOT actual=$status_start_on_boot" >&2
  exit 1
fi
if [ "$gost_tag" != "v3.2.6" ]; then
  echo "Unexpected GOST tag in runtime status: $gost_tag" >&2
  exit 1
fi
if [ -z "$gost_sha" ]; then
  echo "Runtime status did not report GOST sha256." >&2
  exit 1
fi
if [ -z "$native_library_dir" ] || [ "$gost_path" != "$native_library_dir/libgost.so" ]; then
  echo "Runtime GOST path does not match nativeLibraryDir/libgost.so: $gost_path" >&2
  echo "nativeLibraryDir=$native_library_dir" >&2
  exit 1
fi
if [ "$wake_lock_held" != "true" ]; then
  echo "Wake lock is not reported as held after start." >&2
  exit 1
fi
if [ "$battery_ignoring" != "true" ]; then
  echo "Warning: batteryIgnoringOptimizations=false. Set Battery usage to Unrestricted for overnight stability." >&2
fi

check_listen() {
  port=$1
  label=$2
  hex=$(printf '%04X' "$port")
  if "$ADB" shell "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -i ':$hex ' | grep -q ' 0A '" >/dev/null 2>&1; then
    echo "$label port $port is listening on device."
  else
    echo "$label port $port was not found in /proc/net/tcp* LISTEN state." >&2
    return 1
  fi
}

if [ "$ENABLE_HTTP" = "true" ]; then
  check_listen "$HTTP_PORT" HTTP
fi
if [ "$ENABLE_SOCKS" = "true" ]; then
  check_listen "$SOCKS_PORT" SOCKS5
fi

echo
echo "==> Candidate device LAN IPs"
"$ADB" shell "ip -f inet addr show 2>/dev/null | sed -n 's/.*inet \([0-9.]*\)\/.*/\1/p' | grep -v '^127\.' || true"

echo
echo "ADB verification passed. Use scripts/lan_smoke.sh from a LAN client to confirm Google VPN egress."
