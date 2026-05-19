#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ADB=${ADB:-adb}
STATUS_MAX_AGE_SECONDS=${STATUS_MAX_AGE_SECONDS:-120}
. "$ROOT/scripts/lib_status.sh"

tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/pixel-status-selftest.XXXXXX")
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT INT TERM

cat > "$tmp_dir/adb" <<'EOF'
#!/usr/bin/env sh
if [ "$1" = "shell" ] && [ "$2" = "date" ] && [ "$3" = "+%s" ]; then
  printf '%s\n' "${FAKE_DEVICE_NOW:-1700000010}"
  exit 0
fi
exit 1
EOF
chmod +x "$tmp_dir/adb"
ADB="$tmp_dir/adb"

sample=$(printf '%s\n' \
  'lastError=unexpected status 500 expected=204' \
  '    lastRestartReason=request_watchdog:health_url=https://example.test/generate_204' \
  'autoStart=true' \
  'startOnBoot=true' \
  '    statusUpdatedAtEpochMillis=1700000000000')

last_error=$(printf '%s\n' "$sample" | status_value lastError)
reason=$(printf '%s\n' "$sample" | status_value lastRestartReason)
auto_start=$(printf '%s\n' "$sample" | status_value autoStart)
start_on_boot=$(printf '%s\n' "$sample" | status_value startOnBoot)
updated_at=$(printf '%s\n' "$sample" | status_value statusUpdatedAtEpochMillis)
missing=$(printf '%s\n' "$sample" | status_value missingKey)

[ "$last_error" = "unexpected status 500 expected=204" ] || {
  echo "lastError parser failed: $last_error" >&2
  exit 1
}
[ "$reason" = "request_watchdog:health_url=https://example.test/generate_204" ] || {
  echo "lastRestartReason parser failed: $reason" >&2
  exit 1
}
[ "$auto_start" = "true" ] || {
  echo "autoStart parser failed: $auto_start" >&2
  exit 1
}
[ "$start_on_boot" = "true" ] || {
  echo "startOnBoot parser failed: $start_on_boot" >&2
  exit 1
}
[ "$updated_at" = "1700000000000" ] || {
  echo "statusUpdatedAtEpochMillis parser failed: $updated_at" >&2
  exit 1
}
[ -z "$missing" ] || {
  echo "missing key parser failed: $missing" >&2
  exit 1
}
is_unsigned_int 123 || {
  echo "is_unsigned_int rejected digits" >&2
  exit 1
}
if is_unsigned_int "12x"; then
  echo "is_unsigned_int accepted non-digits" >&2
  exit 1
fi
status_age=$(status_age_seconds "$updated_at") || {
  echo "status_age_seconds rejected valid epoch milliseconds" >&2
  exit 1
}
[ "$status_age" = "10" ] || {
  echo "status_age_seconds produced wrong age: $status_age" >&2
  exit 1
}
if status_age_seconds "not-a-number" >/dev/null 2>&1; then
  echo "status_age_seconds accepted invalid epoch milliseconds" >&2
  exit 1
fi
status_is_fresh "$status_age" || {
  echo "status_is_fresh rejected fresh age" >&2
  exit 1
}
if status_is_fresh 121; then
  echo "status_is_fresh accepted stale age" >&2
  exit 1
fi
if status_is_fresh -1; then
  echo "status_is_fresh accepted negative age" >&2
  exit 1
fi

echo "status parser self-test passed."
