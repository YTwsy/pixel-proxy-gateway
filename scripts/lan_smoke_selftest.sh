#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/lan-smoke-selftest.XXXXXX")

cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT INT HUP TERM

cat > "$tmp_dir/curl" <<'FAKE_CURL'
#!/usr/bin/env sh
set -eu

proxy_mode=direct
write_format=
url=

while [ "$#" -gt 0 ]; do
  case "$1" in
    --proxy|--socks5-hostname)
      proxy_mode=proxy
      shift 2
      ;;
    --proxy-user|--max-time|-o)
      shift 2
      ;;
    -w)
      write_format=$2
      shift 2
      ;;
    -fsS|-f|-s|-S)
      shift
      ;;
    http://*|https://*)
      url=$1
      shift
      ;;
    *)
      shift
      ;;
  esac
done

if [ "${FAKE_API_IPIFY_EMPTY:-false}" = "true" ] && [ "$url" = "https://api.ipify.org" ]; then
  exit 22
fi

if [ "$write_format" = "%{http_code}" ]; then
  printf '%s' "${FAKE_HEALTH_CODE:-204}"
elif [ "$proxy_mode" = "proxy" ]; then
  printf '%s\n' "${FAKE_PROXY_IP-203.0.113.8}"
else
  printf '%s\n' "${FAKE_DIRECT_IP-198.51.100.10}"
fi
FAKE_CURL

cat > "$tmp_dir/nc" <<'FAKE_NC'
#!/usr/bin/env sh
exit "${FAKE_NC_EXIT:-0}"
FAKE_NC

chmod +x "$tmp_dir/curl" "$tmp_dir/nc"

run_smoke() {
  env PATH="$tmp_dir:$PATH" PIXEL_IP=192.0.2.55 "$@" "$ROOT/scripts/lan_smoke.sh"
}

expect_pass() {
  name=$1
  shift
  if output=$(run_smoke "$@" 2>&1); then
    printf '%s\n' "$output" | grep 'LAN smoke passed' >/dev/null || {
      echo "$name passed without the expected success message." >&2
      printf '%s\n' "$output" >&2
      exit 1
    }
    echo "$name passed."
  else
    code=$?
    echo "$name failed with exit $code." >&2
    printf '%s\n' "$output" >&2
    exit 1
  fi
}

expect_fail_contains() {
  name=$1
  expected=$2
  shift 2
  if output=$(run_smoke "$@" 2>&1); then
    echo "$name unexpectedly passed." >&2
    printf '%s\n' "$output" >&2
    exit 1
  fi
  printf '%s\n' "$output" | grep "$expected" >/dev/null || {
    echo "$name did not report expected error: $expected" >&2
    printf '%s\n' "$output" >&2
    exit 1
  }
  echo "$name failed as expected."
}

expect_pass \
  expected-proxy-ip-match \
  EXPECTED_PROXY_IP=203.0.113.8

expect_pass \
  public-ip-fallback \
  FAKE_API_IPIFY_EMPTY=true \
  EXPECTED_PROXY_IP=203.0.113.8

expect_pass \
  proxy-ip-unavailable-is-informational \
  FAKE_PROXY_IP=

expect_fail_contains \
  expected-proxy-ip-mismatch \
  'public IP mismatch' \
  EXPECTED_PROXY_IP=203.0.113.9

expect_pass \
  proxy-diff-from-direct \
  REQUIRE_PROXY_DIFF_FROM_DIRECT=true

expect_fail_contains \
  proxy-matches-direct \
  'matches direct IP' \
  FAKE_PROXY_IP=198.51.100.10 \
  REQUIRE_PROXY_DIFF_FROM_DIRECT=true

echo "lan_smoke self-test passed."
