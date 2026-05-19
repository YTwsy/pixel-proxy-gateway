#!/usr/bin/env sh
set -eu

PKG=${PKG:-com.wsy.pixelproxygateway}
ADB=${ADB:-adb}

"$ADB" shell am start-foreground-service \
  -n "$PKG/.ProxyForegroundService" \
  -a "$PKG.action.STOP"
