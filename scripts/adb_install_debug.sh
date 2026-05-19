#!/usr/bin/env sh
set -eu

ADB=${ADB:-adb}
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}

"$ADB" install -r "$APK"
