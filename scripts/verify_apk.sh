#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
APK=${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}
PKG=${PKG:-com.wsy.pixelproxygateway}
EXPECTED_GOST_TAG=${EXPECTED_GOST_TAG:-v3.2.6}
EXPECTED_GOST_COMMIT=${EXPECTED_GOST_COMMIT:-340ba32ef0bebc7293908007cc423dd5f33dd88c}
EXPECTED_MIN_SDK=${EXPECTED_MIN_SDK:-29}
EXPECTED_TARGET_SDK=${EXPECTED_TARGET_SDK:-36}
ALLOW_MISSING_AAPT=${ALLOW_MISSING_AAPT:-false}
export LC_ALL=C
export LANG=C

if [ ! -f "$APK" ]; then
  echo "APK not found: $APK" >&2
  exit 1
fi

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "$1 is required." >&2
    exit 2
  fi
}

need unzip
need awk

sha256() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 | awk '{ print $1 }'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{ print $1 }'
  else
    echo "shasum or sha256sum is required." >&2
    exit 2
  fi
}

require_asset() {
  asset=$1
  if ! unzip -l "$APK" "$asset" >/dev/null 2>&1; then
    echo "Missing APK asset: $asset" >&2
    exit 1
  fi
}

require_asset "lib/arm64-v8a/libgost.so"
require_asset "assets/gost/android-arm64/gost.sha256"
require_asset "assets/gost/android-arm64/gost.tag"
require_asset "assets/gost/android-arm64/gost.commit"

expected_sha=$(unzip -p "$APK" assets/gost/android-arm64/gost.sha256 | awk '{ print $1 }')
actual_sha=$(unzip -p "$APK" lib/arm64-v8a/libgost.so | sha256)
if [ "$expected_sha" != "$actual_sha" ]; then
  echo "GOST SHA mismatch: expected=$expected_sha actual=$actual_sha" >&2
  exit 1
fi

tag=$(unzip -p "$APK" assets/gost/android-arm64/gost.tag | tr -d '\r\n')
commit=$(unzip -p "$APK" assets/gost/android-arm64/gost.commit | tr -d '\r\n')
if [ "$tag" != "$EXPECTED_GOST_TAG" ]; then
  echo "Unexpected GOST tag: expected=$EXPECTED_GOST_TAG actual=$tag" >&2
  exit 1
fi
if [ "$commit" != "$EXPECTED_GOST_COMMIT" ]; then
  echo "Unexpected GOST commit: expected=$EXPECTED_GOST_COMMIT actual=$commit" >&2
  exit 1
fi
if [ "${#actual_sha}" -ne 64 ]; then
  echo "GOST SHA256 is malformed: $actual_sha" >&2
  exit 1
fi
case "$actual_sha" in
  *[!0123456789abcdef]*)
    echo "GOST SHA256 is malformed: $actual_sha" >&2
    exit 1
    ;;
esac

AAPT_BIN=""
if command -v aapt >/dev/null 2>&1; then
  AAPT_BIN=$(command -v aapt)
elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/build-tools/36.0.0/aapt" ]; then
  AAPT_BIN="$ANDROID_HOME/build-tools/36.0.0/aapt"
elif [ -x "$HOME/Library/Android/sdk/build-tools/36.0.0/aapt" ]; then
  AAPT_BIN="$HOME/Library/Android/sdk/build-tools/36.0.0/aapt"
fi

if [ -n "$AAPT_BIN" ]; then
  badging=$("$AAPT_BIN" dump badging "$APK")
else
  if [ "$ALLOW_MISSING_AAPT" = "true" ]; then
    badging=""
    echo "aapt not found; package metadata and manifest checks skipped because ALLOW_MISSING_AAPT=true."
  else
    echo "aapt is required for APK package metadata and stability-critical manifest checks. Set ALLOW_MISSING_AAPT=true only for a lightweight asset/provenance check." >&2
    exit 2
  fi
fi

if [ -n "$badging" ]; then
  printf '%s\n' "$badging" | grep "package: name='$PKG'" >/dev/null || {
    echo "Package name check failed." >&2
    exit 1
  }
  printf '%s\n' "$badging" | grep "sdkVersion:'$EXPECTED_MIN_SDK'" >/dev/null || {
    echo "minSdkVersion check failed." >&2
    exit 1
  }
  printf '%s\n' "$badging" | grep "targetSdkVersion:'$EXPECTED_TARGET_SDK'" >/dev/null || {
    echo "targetSdkVersion check failed." >&2
    exit 1
  }
  printf '%s\n' "$badging" | grep "native-code: 'arm64-v8a'" >/dev/null || {
    echo "native-code arm64-v8a check failed." >&2
    exit 1
  }

  "$AAPT_BIN" dump xmltree "$APK" AndroidManifest.xml | grep 'android:extractNativeLibs.*0xffffffff' >/dev/null || {
    echo "extractNativeLibs=true check failed." >&2
    exit 1
  }

  manifest=$("$AAPT_BIN" dump xmltree "$APK" AndroidManifest.xml)
  require_manifest() {
    pattern=$1
    label=$2
    printf '%s\n' "$manifest" | grep "$pattern" >/dev/null || {
      echo "Manifest check failed: $label" >&2
      exit 1
    }
  }
  require_manifest_literal() {
    literal=$1
    label=$2
    printf '%s\n' "$manifest" | grep -F "$literal" >/dev/null || {
      echo "Manifest check failed: $label" >&2
      exit 1
    }
  }

  require_manifest_literal '"android.permission.FOREGROUND_SERVICE_SPECIAL_USE"' 'specialUse foreground service permission'
  require_manifest_literal '"android.permission.FOREGROUND_SERVICE"' 'base foreground service permission'
  require_manifest_literal '"android.permission.INTERNET"' 'internet permission'
  require_manifest_literal '"android.permission.ACCESS_NETWORK_STATE"' 'network state permission'
  require_manifest_literal '"android.permission.POST_NOTIFICATIONS"' 'notification permission'
  require_manifest_literal '"android.permission.RECEIVE_BOOT_COMPLETED"' 'boot completed permission'
  require_manifest_literal '"android.permission.WAKE_LOCK"' 'wake lock permission'
  require_manifest_literal '"android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"' 'battery optimization permission'
  require_manifest_literal '"com.wsy.pixelproxygateway.ProxyForegroundService"' 'foreground service declaration'
  require_manifest 'android:foregroundServiceType.*0x40000000' 'specialUse foreground service type'
  require_manifest_literal '"android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"' 'specialUse foreground service subtype property'
  require_manifest_literal '"com.wsy.pixelproxygateway.ControlReceiver"' 'ADB control receiver'
  require_manifest_literal '"com.wsy.pixelproxygateway.BootReceiver"' 'boot/package receiver'
  require_manifest_literal '"android.intent.action.BOOT_COMPLETED"' 'BOOT_COMPLETED action'
  require_manifest_literal '"android.intent.action.MY_PACKAGE_REPLACED"' 'MY_PACKAGE_REPLACED action'
  require_manifest_literal '"com.wsy.pixelproxygateway.StatusProvider"' 'status provider'
  require_manifest_literal '"com.wsy.pixelproxygateway.status"' 'status provider authority'
  for action in START STOP RESTART APPLY_CONFIG; do
    require_manifest_literal "\"$PKG.action.$action\"" "control action $action"
  done
fi

echo "APK verification passed:"
echo "  apk=$APK"
echo "  package=$PKG"
if [ -n "$AAPT_BIN" ]; then
  echo "  aapt=$AAPT_BIN"
  echo "  min_sdk=$EXPECTED_MIN_SDK"
  echo "  target_sdk=$EXPECTED_TARGET_SDK"
  echo "  manifest_checks=passed"
else
  echo "  manifest_checks=skipped"
fi
echo "  gost_tag=$tag"
echo "  gost_commit=$commit"
echo "  gost_sha256=$actual_sha"
