#!/usr/bin/env sh
set -eu

PKG=${PKG:-com.wsy.pixelproxygateway}
ADB=${ADB:-adb}

"$ADB" shell dumpsys activity service "$PKG/.ProxyForegroundService" || true
echo
"$ADB" shell content query --uri "content://$PKG.status/status" || true
