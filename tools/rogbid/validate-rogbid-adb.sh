#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
set -euo pipefail

SERIAL="${1:-46734915123233}"
PACKAGE="app.solstone.validation.rogbid"
APK="apps/validation-rogbid/build/outputs/apk/debug/validation-rogbid-debug.apk"
SCREENSHOT="/tmp/rogbid-hello-after-tap.png"

./gradlew :apps:validation-rogbid:assembleDebug

adb -s "$SERIAL" install -r "$APK"
adb -s "$SERIAL" shell input keyevent 224 >/dev/null 2>&1 || true
adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb -s "$SERIAL" shell am start -S -W -n "$PACKAGE/.MainActivity"

echo "--- evidence after launch ---"
adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/tap-evidence.txt

echo "--- initial UI ---"
adb -s "$SERIAL" shell uiautomator dump /sdcard/rogbid-hello-window.xml >/dev/null
adb -s "$SERIAL" shell cat /sdcard/rogbid-hello-window.xml \
  | tr "<>" "\n\n" \
  | grep -E "Ready on Rogbid|hello_status|hello_button"

adb -s "$SERIAL" shell input tap 200 151
sleep 1

echo "--- evidence after tap ---"
adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/tap-evidence.txt

adb -s "$SERIAL" exec-out screencap -p > "$SCREENSHOT"
file "$SCREENSHOT"
ls -lh "$SCREENSHOT"
