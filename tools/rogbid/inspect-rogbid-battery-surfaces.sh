#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
set -euo pipefail

SERIAL="${1:-192.168.4.69:5555}"
OUT_DIR="${2:-/tmp/rogbid-battery-surfaces}"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

adb -s "$SERIAL" shell getprop > "$OUT_DIR/getprop.txt"
adb -s "$SERIAL" shell dumpsys battery > "$OUT_DIR/battery.txt"
adb -s "$SERIAL" shell df -h > "$OUT_DIR/df-h.txt"
adb -s "$SERIAL" shell settings get global wifi_sleep_policy > "$OUT_DIR/wifi_sleep_policy.txt" || true
adb -s "$SERIAL" shell dumpsys wifi > "$OUT_DIR/dumpsys-wifi.txt" || true
adb -s "$SERIAL" shell dumpsys deviceidle > "$OUT_DIR/dumpsys-deviceidle.txt" || true
adb -s "$SERIAL" shell dumpsys media.audio_policy > "$OUT_DIR/dumpsys-media-audio-policy.txt" || true
adb -s "$SERIAL" shell dumpsys media.audio_flinger > "$OUT_DIR/dumpsys-media-audio-flinger.txt" || true
adb -s "$SERIAL" shell dumpsys media.codec > "$OUT_DIR/dumpsys-media-codec.txt" || true
adb -s "$SERIAL" shell cmd media.codec list > "$OUT_DIR/cmd-media-codec-list.txt" 2>&1 || true

echo "--- storage ---"
grep -E 'Filesystem|/data|/storage|/sdcard|emulated' "$OUT_DIR/df-h.txt" || cat "$OUT_DIR/df-h.txt"

echo "--- battery ---"
sed -n '1,40p' "$OUT_DIR/battery.txt"

echo "--- wifi sleep policy ---"
cat "$OUT_DIR/wifi_sleep_policy.txt"

echo "--- audio input policy ---"
grep -i -E 'Available input devices|Built-In Mic|Built-In Back Mic|microphones_count|AUDIO_DEVICE_IN' \
  "$OUT_DIR/dumpsys-media-audio-policy.txt" "$OUT_DIR/dumpsys-media-audio-flinger.txt" \
  | head -n 80 || true

echo "--- possible AAC encoders ---"
grep -i -E 'aac|encoder|OMX\\.|c2\\.' "$OUT_DIR/dumpsys-media-codec.txt" "$OUT_DIR/cmd-media-codec-list.txt" \
  | head -n 120 || true

echo "wrote=$OUT_DIR"
