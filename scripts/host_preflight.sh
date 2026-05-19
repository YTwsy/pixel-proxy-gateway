#!/usr/bin/env sh
set -u

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ADB=${ADB:-adb}
OUT_DIR=${OUT_DIR:-$ROOT/reports/host-preflight-$(date +%Y%m%d-%H%M%S)}
REQUIRE_DEVICE=${REQUIRE_DEVICE:-false}
SKIP_GRADLE=${SKIP_GRADLE:-false}

mkdir -p "$OUT_DIR"

summary="$OUT_DIR/summary.tsv"
next_steps="$OUT_DIR/next_device_steps.txt"
printf 'step\texit\tlog\n' > "$summary"
failures=0

run_step() {
  name=$1
  shift
  log="$OUT_DIR/$name.log"
  echo "==> $name"
  {
    echo "step=$name"
    echo "command=$*"
    echo
    "$@"
  } > "$log" 2>&1
  code=$?
  printf '%s\t%s\t%s\n' "$name" "$code" "$log" >> "$summary"
  if [ "$code" -ne 0 ]; then
    failures=$((failures + 1))
    echo "$name failed. See $log" >&2
  else
    echo "$name passed."
  fi
  return 0
}

check_shell_syntax() {
  for file in "$ROOT"/scripts/*.sh "$ROOT"/tools/build-gost-android-arm64.sh; do
    [ -f "$file" ] || continue
    sh -n "$file" || return 1
  done
}

run_gradle() {
  if [ "$SKIP_GRADLE" = "true" ]; then
    echo "SKIP_GRADLE=true; Gradle build/lint/unit-test gate skipped."
    return 0
  fi
  "$ROOT/gradlew" :app:assembleDebug :app:lintDebug :app:testDebugUnitTest --no-daemon
}

check_adb_devices() {
  "$ADB" devices
  device_count=$("$ADB" devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')
  echo "authorized_device_count=$device_count"
  if [ "$device_count" -eq 0 ]; then
    echo "device_status=no_authorized_device"
    if [ "$REQUIRE_DEVICE" = "true" ]; then
      echo "No authorized Android device found and REQUIRE_DEVICE=true." >&2
      return 1
    fi
    return 0
  fi
  if [ "$device_count" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
    echo "device_status=multiple_devices_without_android_serial"
    echo "Multiple devices found. Set ANDROID_SERIAL before device acceptance." >&2
    return 1
  fi
  echo "device_status=ready"
}

write_next_steps() {
  {
    echo "Pixel Proxy Gateway device validation checklist"
    echo
    echo "1. Connect and authorize the Pixel over ADB."
    echo "2. Run scripts/acceptance_check.sh."
    echo "3. If you know the Google VPN exit IP, run:"
    echo "   EXPECTED_PROXY_IP=<google-vpn-exit-ip> scripts/acceptance_check.sh"
    echo "4. To include the post-unlock reboot restore proof, run:"
    echo "   RUN_REBOOT_RESTORE_CHECK=true scripts/acceptance_check.sh"
    echo "5. For overnight stability while connected to ADB, run:"
    echo "   DURATION_SECONDS=28800 INTERVAL_SECONDS=300 PIXEL_IP=<pixel-lan-ip> scripts/stability_monitor.sh"
    echo "6. For last-resort host-side recovery observation, run:"
    echo "   DURATION_SECONDS=28800 CHECK_INTERVAL_SECONDS=60 PIXEL_IP=<pixel-lan-ip> scripts/adb_supervise.sh"
    echo
    echo "The goal is not complete until install/start, LAN Google VPN egress, fault recovery, package-replace restore, optional reboot restore, and overnight locked/charging stability are verified on the Pixel."
  } > "$next_steps"
}

run_step shell-syntax check_shell_syntax
run_step status-parser-selftest "$ROOT/scripts/status_parser_selftest.sh"
run_step adb-start-selftest "$ROOT/scripts/adb_start_selftest.sh"
run_step lan-smoke-selftest "$ROOT/scripts/lan_smoke_selftest.sh"
run_step gradle-build-lint-tests run_gradle
run_step verify-apk "$ROOT/scripts/verify_apk.sh"
run_step verify-gost-provenance "$ROOT/scripts/verify_gost_provenance.sh"
run_step adb-devices check_adb_devices
write_next_steps

echo
echo "Host preflight summary: $summary"
echo "Next device steps: $next_steps"

if [ "$failures" -ne 0 ]; then
  echo "Host preflight finished with $failures failed step(s)." >&2
  exit 1
fi

echo "Host preflight passed."
