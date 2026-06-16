#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
set -euo pipefail

SERIAL="${1:-46734915123233}"
PACKAGE="app.solstone.validation.rogbid"
APK="apps/validation-rogbid/build/outputs/apk/debug/validation-rogbid-debug.apk"
OUT_DIR="/tmp/rogbid-media"

./gradlew :apps:validation-rogbid:assembleDebug

adb -s "$SERIAL" install -r "$APK"
adb -s "$SERIAL" shell pm grant "$PACKAGE" android.permission.CAMERA
adb -s "$SERIAL" shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO

adb -s "$SERIAL" shell input keyevent 224 >/dev/null 2>&1 || true
adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb -s "$SERIAL" shell am start -S -W -n "$PACKAGE/.MainActivity"

echo "--- initial UI ---"
adb -s "$SERIAL" shell uiautomator dump /sdcard/rogbid-media-window.xml >/dev/null
adb -s "$SERIAL" shell cat /sdcard/rogbid-media-window.xml \
  | tr '<>' '\n\n' \
  | grep -E 'Ready on Rogbid|media_button|Run media test'

adb -s "$SERIAL" shell input tap 200 141

echo "--- waiting for media evidence ---"
for _ in $(seq 1 30); do
  evidence="$(adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/media-evidence.txt 2>/dev/null || true)"
  if printf "%s\n" "$evidence" | grep -Eq 'completed=|ERROR='; then
    printf "%s\n" "$evidence"
    break
  fi
  sleep 1
done

evidence="$(adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/media-evidence.txt)"
printf "%s\n" "$evidence" | grep -q 'completed='
printf "%s\n" "$evidence" | grep -q 'camera_0_jpg='
printf "%s\n" "$evidence" | grep -q 'camera_1_jpg='
printf "%s\n" "$evidence" | grep -q 'mic_m4a='
printf "%s\n" "$evidence" | grep -q 'audio_input_0_pcm='
printf "%s\n" "$evidence" | grep -q 'audio_input_1_pcm='

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
adb -s "$SERIAL" exec-out run-as "$PACKAGE" cat files/camera-0.jpg > "$OUT_DIR/camera-0.jpg"
adb -s "$SERIAL" exec-out run-as "$PACKAGE" cat files/camera-1.jpg > "$OUT_DIR/camera-1.jpg"
adb -s "$SERIAL" exec-out run-as "$PACKAGE" cat files/mic.m4a > "$OUT_DIR/mic.m4a"
adb -s "$SERIAL" exec-out run-as "$PACKAGE" cat files/mic-input-0.pcm > "$OUT_DIR/mic-input-0.pcm"
adb -s "$SERIAL" exec-out run-as "$PACKAGE" cat files/mic-input-1.pcm > "$OUT_DIR/mic-input-1.pcm"

echo "--- pulled artifacts ---"
file "$OUT_DIR/camera-0.jpg" \
  "$OUT_DIR/camera-1.jpg" \
  "$OUT_DIR/mic.m4a" \
  "$OUT_DIR/mic-input-0.pcm" \
  "$OUT_DIR/mic-input-1.pcm"
ls -lh "$OUT_DIR"
