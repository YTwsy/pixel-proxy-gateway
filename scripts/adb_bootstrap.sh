#!/usr/bin/env sh
set -eu

PKG=${PKG:-com.wsy.pixelproxygateway}
ADB=${ADB:-adb}

"$ADB" shell cmd deviceidle whitelist +"$PKG" || true
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
"$ADB" shell am set-standby-bucket "$PKG" active || true
"$ADB" shell appops set "$PKG" RUN_ANY_IN_BACKGROUND allow || true
"$ADB" shell appops set "$PKG" WAKE_LOCK allow || true
"$ADB" shell appops set "$PKG" START_FOREGROUND allow || true
"$ADB" shell appops set "$PKG" POST_NOTIFICATION allow || true
"$ADB" shell settings put global stay_on_while_plugged_in 7 || true

echo "Bootstrap hints applied. Also open Android battery settings and set Pixel Proxy Gateway to Unrestricted."
