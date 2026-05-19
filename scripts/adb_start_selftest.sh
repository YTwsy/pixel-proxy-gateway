#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/pixel-adb-start-selftest.XXXXXX")
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT INT TERM

expect_exit_2() {
  name=$1
  expected=$2
  shift 2
  output=$("$@" 2>&1) && {
    echo "$name unexpectedly passed." >&2
    exit 1
  }
  code=$?
  [ "$code" -eq 2 ] || {
    echo "$name exited with $code, expected 2." >&2
    printf '%s\n' "$output" >&2
    exit 1
  }
  printf '%s\n' "$output" | grep "$expected" >/dev/null || {
    echo "$name did not report expected error: $expected" >&2
    printf '%s\n' "$output" >&2
    exit 1
  }
}

expect_exit_2 \
  no-listeners \
  'At least one listener must be enabled' \
  env ADB=false ENABLE_HTTP=false ENABLE_SOCKS=false "$ROOT/scripts/adb_start.sh"

expect_exit_2 \
  port-collision \
  'HTTP_PORT and SOCKS_PORT cannot be the same' \
  env ADB=false ENABLE_HTTP=true ENABLE_SOCKS=true HTTP_PORT=18080 SOCKS_PORT=18080 "$ROOT/scripts/adb_start.sh"

output=$(env ADB=false ENABLE_HTTP=true ENABLE_SOCKS=false HTTP_PORT=18080 SOCKS_PORT=18080 "$ROOT/scripts/adb_start.sh" 2>&1) && {
  echo "single-listener unexpectedly reached a successful ADB command." >&2
  exit 1
}
code=$?
[ "$code" -ne 2 ] || {
  echo "single-listener was rejected by local config validation." >&2
  printf '%s\n' "$output" >&2
  exit 1
}

cat > "$tmp_dir/adb" <<'EOF'
#!/usr/bin/env sh
for arg in "$@"; do
  printf '%s\n' "$arg"
done > "$CAPTURE_FILE"
EOF
chmod +x "$tmp_dir/adb"

CAPTURE_FILE="$tmp_dir/empty-auth.args" \
  ADB="$tmp_dir/adb" \
  AUTH_ENABLED=false \
  USERNAME= \
  PASSWORD= \
  "$ROOT/scripts/adb_start.sh"

if grep -x 'username' "$tmp_dir/empty-auth.args" >/dev/null || grep -x 'password' "$tmp_dir/empty-auth.args" >/dev/null; then
  echo "empty auth args should not include username/password extras." >&2
  cat "$tmp_dir/empty-auth.args" >&2
  exit 1
fi

CAPTURE_FILE="$tmp_dir/auth.args" \
  ADB="$tmp_dir/adb" \
  AUTH_ENABLED=true \
  USERNAME=alice \
  PASSWORD=secret \
  "$ROOT/scripts/adb_start.sh"

grep -x 'username' "$tmp_dir/auth.args" >/dev/null || {
  echo "auth args omitted username extra." >&2
  cat "$tmp_dir/auth.args" >&2
  exit 1
}
grep -x 'alice' "$tmp_dir/auth.args" >/dev/null || {
  echo "auth args omitted username value." >&2
  cat "$tmp_dir/auth.args" >&2
  exit 1
}
grep -x 'password' "$tmp_dir/auth.args" >/dev/null || {
  echo "auth args omitted password extra." >&2
  cat "$tmp_dir/auth.args" >&2
  exit 1
}
grep -x 'secret' "$tmp_dir/auth.args" >/dev/null || {
  echo "auth args omitted password value." >&2
  cat "$tmp_dir/auth.args" >&2
  exit 1
}

echo "adb_start self-test passed."
