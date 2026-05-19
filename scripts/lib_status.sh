status_value() {
  key=$1
  awk -v key="$key" '{
    line = $0
    sub(/^[[:space:]]+/, "", line)
    if (index(line, key "=") == 1) {
      value = substr(line, length(key) + 2)
      gsub(/\r/, "", value)
      print value
      exit
    }
  }'
}

is_unsigned_int() {
  case "${1:-}" in
    ''|*[!0-9]*) return 1 ;;
    *) return 0 ;;
  esac
}

status_age_seconds() {
  epoch_ms=${1:-}
  if ! is_unsigned_int "$epoch_ms"; then
    return 1
  fi
  device_now=$("$ADB" shell date +%s 2>/dev/null | tr -d '\r' || true)
  if ! is_unsigned_int "$device_now"; then
    return 1
  fi
  echo $((device_now - epoch_ms / 1000))
}

status_is_fresh() {
  age=${1:-}
  if ! is_unsigned_int "$age"; then
    return 1
  fi
  [ "$age" -le "$STATUS_MAX_AGE_SECONDS" ]
}
