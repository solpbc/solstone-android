#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
set -euo pipefail

SERIAL="${1:-46734915123233}"
PACKAGE="org.solpbc.rogbidhello"
OUT_ROOT="${ROGBID_BATTERY_OUT:-/tmp/rogbid-battery-trials}"

adb -s "$SERIAL" get-state >/dev/null

evidence=""
for _ in $(seq 1 15); do
  evidence="$(adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/battery-trial-evidence.txt 2>/dev/null || true)"
  if printf "%s\n" "$evidence" | grep -Eq '^completed=|^ERROR=|^stopped='; then
    break
  fi
  sleep 2
done

mode="$(printf "%s\n" "$evidence" | awk -F= '/^mode=/ {print $2; exit}')"
mode="${mode:-unknown}"
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$mode"
OUT_DIR="$OUT_ROOT/$run_id"
mkdir -p "$OUT_DIR"

printf "%s\n" "$evidence" > "$OUT_DIR/evidence.txt"
adb -s "$SERIAL" exec-out run-as "$PACKAGE" cat files/battery-trial-samples.csv \
  > "$OUT_DIR/battery-trial-samples.csv" 2>/dev/null || true
adb -s "$SERIAL" shell run-as "$PACKAGE" ls -lh files \
  > "$OUT_DIR/app-files-ls.txt" 2>/dev/null || true
adb -s "$SERIAL" shell dumpsys battery | tr -d '\r' > "$OUT_DIR/battery-after-reconnect.txt" 2>&1 || true
adb -s "$SERIAL" shell dumpsys wifi > "$OUT_DIR/wifi-after-reconnect.txt" 2>&1 || true
adb -s "$SERIAL" shell dumpsys deviceidle > "$OUT_DIR/deviceidle-after-reconnect.txt" 2>&1 || true
adb -s "$SERIAL" shell settings get system isClear > "$OUT_DIR/isClear-after-reconnect.txt" 2>&1 || true
adb -s "$SERIAL" shell run-as "$PACKAGE" sh -c \
  "'if [ -d files/battery-camera ]; then find files/battery-camera -type f | wc -l; du -sk files/battery-camera; ls -lh files/battery-camera | head -20; ls -lh files/battery-camera | tail -20; fi'" \
  > "$OUT_DIR/camera-files.txt" 2>/dev/null || true

if [[ "${PULL_AUDIO:-0}" == "1" ]]; then
  adb -s "$SERIAL" exec-out run-as "$PACKAGE" cat files/battery-mic-aac.m4a \
    > "$OUT_DIR/battery-mic-aac.m4a" 2>/dev/null || true
  adb -s "$SERIAL" exec-out run-as "$PACKAGE" cat files/battery-mic-input-0.pcm \
    > "$OUT_DIR/battery-mic-input-0.pcm" 2>/dev/null || true
  adb -s "$SERIAL" exec-out run-as "$PACKAGE" cat files/battery-mic-input-1.pcm \
    > "$OUT_DIR/battery-mic-input-1.pcm" 2>/dev/null || true
fi

if [[ "${PULL_CAMERA:-0}" == "1" ]]; then
  mkdir -p "$OUT_DIR/battery-camera"
  adb -s "$SERIAL" shell run-as "$PACKAGE" sh -c \
    "'cd files/battery-camera 2>/dev/null && tar cf - .'" \
    | tar -C "$OUT_DIR/battery-camera" -xf - 2>/dev/null || true
fi

csv="$OUT_DIR/battery-trial-samples.csv"
if [[ -s "$csv" ]] && (( "$(wc -l < "$csv")" >= 2 )); then
  first_level="$(awk -F, 'NR==2 {print $3; exit}' "$csv")"
  last_level="$(awk -F, 'END {print $3}' "$csv")"
  first_voltage="$(awk -F, 'NR==2 {print $5; exit}' "$csv")"
  last_voltage="$(awk -F, 'END {print $5}' "$csv")"
  elapsed_last="$(awk -F, 'END {print $2}' "$csv")"
  plugged_samples="$(awk -F, 'NR>1 && $7 != 0 {count++} END {print count + 0}' "$csv")"
  network_connected_samples="$(awk -F, 'NR>1 && $9 == "true" {count++} END {print count + 0}' "$csv")"
  active_wifi_samples="$(awk -F, 'NR>1 && $10 == 1 {count++} END {print count + 0}' "$csv")"
  wifi_transport_samples="$(awk -F, 'NR>1 && $11 == "true" {count++} END {print count + 0}' "$csv")"
  camera_file_count="$(awk 'NR==1 {print $1; exit}' "$OUT_DIR/camera-files.txt" 2>/dev/null || true)"
  camera_kb="$(awk 'NR==2 {print $1; exit}' "$OUT_DIR/camera-files.txt" 2>/dev/null || true)"
else
  first_level=""
  last_level=""
  first_voltage=""
  last_voltage=""
  elapsed_last=""
  plugged_samples=""
  network_connected_samples=""
  active_wifi_samples=""
  wifi_transport_samples=""
  camera_file_count=""
  camera_kb=""
fi

{
  echo "run_id=$run_id"
  echo "serial=$SERIAL"
  echo "mode=$mode"
  echo "elapsed_sampled_seconds=$elapsed_last"
  echo "level_start=$first_level"
  echo "level_end=$last_level"
  if [[ "$first_level" =~ ^-?[0-9]+$ && "$last_level" =~ ^-?[0-9]+$ ]]; then
    echo "level_delta=$(( last_level - first_level ))"
  fi
  echo "voltage_start_mv=$first_voltage"
  echo "voltage_end_mv=$last_voltage"
  if [[ "$first_voltage" =~ ^-?[0-9]+$ && "$last_voltage" =~ ^-?[0-9]+$ ]]; then
    echo "voltage_delta_mv=$(( last_voltage - first_voltage ))"
  fi
  echo "plugged_sample_count=$plugged_samples"
  echo "network_connected_sample_count=$network_connected_samples"
  echo "active_wifi_sample_count=$active_wifi_samples"
  echo "wifi_transport_sample_count=$wifi_transport_samples"
  echo "camera_file_count=$camera_file_count"
  echo "camera_kb=$camera_kb"
  echo "isClear=$(tr -d '\r' < "$OUT_DIR/isClear-after-reconnect.txt" 2>/dev/null || true)"
  echo "out_dir=$OUT_DIR"
} | tee "$OUT_DIR/summary.txt"

if [[ "${plugged_samples:-0}" =~ ^[0-9]+$ ]] && (( plugged_samples > 0 )); then
  echo "WARNING: sampled plugged!=0 during the fixed trial window; treat this run as compromised." >&2
fi

if ! printf "%s\n" "$evidence" | grep -q '^completed='; then
  echo "WARNING: service evidence does not contain completed=; the trial may still be running or may have stopped early." >&2
fi

echo "--- service evidence ---"
cat "$OUT_DIR/evidence.txt"

echo "--- app files ---"
cat "$OUT_DIR/app-files-ls.txt"

if [[ -s "$OUT_DIR/camera-files.txt" ]]; then
  echo "--- camera files ---"
  cat "$OUT_DIR/camera-files.txt"
fi
