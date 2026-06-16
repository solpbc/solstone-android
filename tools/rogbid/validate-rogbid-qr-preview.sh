#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
set -euo pipefail

SERIAL="${1:-46734915123233}"
PACKAGE="org.solpbc.rogbidhello"
APK="apps/validation-rogbid/build/outputs/apk/debug/validation-rogbid-debug.apk"
WAIT_SECONDS="${QR_WAIT_SECONDS:-45}"

./gradlew :apps:validation-rogbid:assembleDebug

adb -s "$SERIAL" install -r "$APK"
adb -s "$SERIAL" shell pm grant "$PACKAGE" android.permission.CAMERA || true

adb -s "$SERIAL" shell input keyevent 224 >/dev/null 2>&1 || true
adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb -s "$SERIAL" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
adb -s "$SERIAL" shell run-as "$PACKAGE" rm -f files/qr-evidence.txt >/dev/null 2>&1 || true
adb -s "$SERIAL" shell am start -W -n "$PACKAGE/.QrProbeActivity"

echo "--- waiting for QR evidence (${WAIT_SECONDS}s) ---"
for _ in $(seq 1 "$WAIT_SECONDS"); do
  evidence="$(adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/qr-evidence.txt 2>/dev/null || true)"
  if printf "%s\n" "$evidence" | grep -q '^decoded='; then
    printf "%s\n" "$evidence"
    exit 0
  fi
  if printf "%s\n" "$evidence" | grep -q '^first_frame_ms='; then
    printf "%s\n" "$evidence" | tail -n 20
  fi
  sleep 1
done

echo "--- final QR evidence ---"
adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/qr-evidence.txt 2>/dev/null || true
exit 2
