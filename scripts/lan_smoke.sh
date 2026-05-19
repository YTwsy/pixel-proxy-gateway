#!/usr/bin/env sh
set -eu

ADB=${ADB:-adb}
PIXEL_IP=${PIXEL_IP:-}
HTTP_PORT=${HTTP_PORT:-8080}
SOCKS_PORT=${SOCKS_PORT:-1080}
ENABLE_HTTP=${ENABLE_HTTP:-true}
ENABLE_SOCKS=${ENABLE_SOCKS:-true}
AUTH_ENABLED=${AUTH_ENABLED:-false}
USERNAME=${USERNAME:-}
PASSWORD=${PASSWORD:-}
PUBLIC_IP_URL=${PUBLIC_IP_URL:-}
PUBLIC_IP_URLS=${PUBLIC_IP_URLS:-}
HEALTH_URL=${HEALTH_URL:-https://connectivitycheck.gstatic.com/generate_204}
EXPECTED_PROXY_IP=${EXPECTED_PROXY_IP:-}
REQUIRE_PROXY_DIFF_FROM_DIRECT=${REQUIRE_PROXY_DIFF_FROM_DIRECT:-false}

default_public_ip_urls="https://api.ipify.org https://checkip.amazonaws.com https://ifconfig.co/ip"
if [ -n "$PUBLIC_IP_URLS" ]; then
  public_ip_urls=$PUBLIC_IP_URLS
elif [ -n "$PUBLIC_IP_URL" ]; then
  public_ip_urls=$PUBLIC_IP_URL
else
  public_ip_urls=$default_public_ip_urls
fi

if [ "$ENABLE_HTTP" != "true" ] && [ "$ENABLE_SOCKS" != "true" ]; then
  echo "At least one listener must be enabled for LAN smoke." >&2
  exit 2
fi

if [ -z "$PIXEL_IP" ]; then
  PIXEL_IP=$("$ADB" shell "ip -f inet addr show wlan0 2>/dev/null | sed -n 's/.*inet \([0-9.]*\)\/.*/\1/p' | head -1" 2>/dev/null | tr -d '\r' || true)
fi

if [ -z "$PIXEL_IP" ]; then
  PIXEL_IP=$("$ADB" shell "ip -f inet addr show 2>/dev/null | sed -n 's/.*inet \([0-9.]*\)\/.*/\1/p' | grep -v '^127\.' | head -1" 2>/dev/null | tr -d '\r' || true)
fi

if [ -z "$PIXEL_IP" ]; then
  echo "PIXEL_IP is required. Example: PIXEL_IP=192.168.1.50 scripts/lan_smoke.sh" >&2
  exit 2
fi

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "$1 is required." >&2
    exit 2
  fi
}

need curl

check_port() {
  host=$1
  port=$2
  label=$3
  if command -v nc >/dev/null 2>&1; then
    if nc -z -G 3 "$host" "$port" >/dev/null 2>&1 || nc -z -w 3 "$host" "$port" >/dev/null 2>&1; then
      echo "$label port $host:$port reachable"
      return 0
    fi
    echo "$label port $host:$port not reachable" >&2
    return 1
  fi
  echo "nc not found; skipping raw port check for $label"
}

curl_http_proxy() {
  if [ "$AUTH_ENABLED" = "true" ]; then
    curl --proxy-user "$USERNAME:$PASSWORD" --proxy "http://$PIXEL_IP:$HTTP_PORT" "$@"
  else
    curl --proxy "http://$PIXEL_IP:$HTTP_PORT" "$@"
  fi
}

curl_socks_proxy() {
  if [ "$AUTH_ENABLED" = "true" ]; then
    curl --proxy-user "$USERNAME:$PASSWORD" --socks5-hostname "$PIXEL_IP:$SOCKS_PORT" "$@"
  else
    curl --socks5-hostname "$PIXEL_IP:$SOCKS_PORT" "$@"
  fi
}

normalize_public_ip() {
  printf '%s\n' "$1" | tr -d '\r' | sed -n '1{s/[[:space:]]*$//;p;}'
}

first_public_ip() {
  mode=$1
  for url in $public_ip_urls; do
    case "$mode" in
      direct)
        observed=$(curl -fsS --max-time 15 "$url" 2>/dev/null || true)
        ;;
      http)
        observed=$(curl_http_proxy -fsS --max-time 25 "$url" 2>/dev/null || true)
        ;;
      socks)
        observed=$(curl_socks_proxy -fsS --max-time 25 "$url" 2>/dev/null || true)
        ;;
      *)
        observed=
        ;;
    esac
    observed=$(normalize_public_ip "$observed")
    if [ -n "$observed" ]; then
      printf '%s' "$observed"
      return 0
    fi
  done
  return 1
}

echo "==> Checking LAN reachability for Pixel $PIXEL_IP"
if [ "$ENABLE_HTTP" = "true" ]; then
  check_port "$PIXEL_IP" "$HTTP_PORT" HTTP
fi
if [ "$ENABLE_SOCKS" = "true" ]; then
  check_port "$PIXEL_IP" "$SOCKS_PORT" SOCKS5
fi

echo
echo "==> Public IP comparison"
direct_ip=$(first_public_ip direct || true)
http_ip=skipped
socks_ip=skipped
if [ "$ENABLE_HTTP" = "true" ]; then
  http_ip=$(first_public_ip http || true)
fi
if [ "$ENABLE_SOCKS" = "true" ]; then
  socks_ip=$(first_public_ip socks || true)
fi
echo "direct=$direct_ip"
echo "http_proxy=$http_ip"
echo "socks5_proxy=$socks_ip"

check_expected_proxy_ip() {
  label=$1
  observed=$2
  if [ "$observed" = "skipped" ]; then
    return 0
  fi
  if [ -z "$observed" ]; then
    if [ -n "$EXPECTED_PROXY_IP" ] || [ "$REQUIRE_PROXY_DIFF_FROM_DIRECT" = "true" ]; then
      echo "$label public IP unavailable." >&2
      return 1
    fi
    echo "$label public IP unavailable; default smoke will rely on the 204 health check." >&2
    return 0
  fi
  if [ -n "$EXPECTED_PROXY_IP" ] && [ "$observed" != "$EXPECTED_PROXY_IP" ]; then
    echo "$label public IP mismatch: expected=$EXPECTED_PROXY_IP actual=$observed" >&2
    return 1
  fi
  if [ "$REQUIRE_PROXY_DIFF_FROM_DIRECT" = "true" ] && [ -n "$direct_ip" ] && [ "$observed" = "$direct_ip" ]; then
    echo "$label public IP matches direct IP but REQUIRE_PROXY_DIFF_FROM_DIRECT=true: $observed" >&2
    return 1
  fi
  return 0
}

echo
echo "==> 204 health through Pixel HTTP proxy"
http_code=skipped
if [ "$ENABLE_HTTP" = "true" ]; then
  http_code=$(curl_http_proxy -fsS -o /dev/null -w '%{http_code}' --max-time 25 "$HEALTH_URL" 2>/dev/null || true)
fi
echo "http_proxy_health_status=$http_code"

echo
echo "==> 204 health through Pixel SOCKS5 proxy"
socks_code=skipped
if [ "$ENABLE_SOCKS" = "true" ]; then
  socks_code=$(curl_socks_proxy -fsS -o /dev/null -w '%{http_code}' --max-time 25 "$HEALTH_URL" 2>/dev/null || true)
fi
echo "socks5_proxy_health_status=$socks_code"

failed=false
if [ "$ENABLE_HTTP" = "true" ] && [ "$http_code" != "204" ]; then
  failed=true
fi
if [ "$ENABLE_SOCKS" = "true" ] && [ "$socks_code" != "204" ]; then
  failed=true
fi
if [ "$REQUIRE_PROXY_DIFF_FROM_DIRECT" = "true" ] && [ -z "$direct_ip" ]; then
  echo "Direct public IP unavailable but REQUIRE_PROXY_DIFF_FROM_DIRECT=true." >&2
  failed=true
fi
if [ "$ENABLE_HTTP" = "true" ] && ! check_expected_proxy_ip "HTTP proxy" "$http_ip"; then
  failed=true
fi
if [ "$ENABLE_SOCKS" = "true" ] && ! check_expected_proxy_ip "SOCKS5 proxy" "$socks_ip"; then
  failed=true
fi
if [ "$failed" = "true" ]; then
  echo "LAN smoke failed. Check Pixel battery mode, Wi-Fi isolation, firewall, auth, and Google VPN state." >&2
  exit 1
fi

echo
if [ -n "$EXPECTED_PROXY_IP" ]; then
  echo "LAN smoke passed. Proxy public IP matched expected Google VPN exit: $EXPECTED_PROXY_IP"
else
  echo "LAN smoke passed. The proxy path is usable; compare proxy IPs with your expected Google VPN exit."
fi
