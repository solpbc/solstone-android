#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
set -euo pipefail

SERIAL="${1:-192.168.4.69:5555}"
MODE="${2:-idle}"
DURATION_SECONDS="${3:-1800}"
INTERVAL_SECONDS="${4:-60}"
PACKAGE="org.solpbc.rogbidhello"
OUT_ROOT="${ROGBID_BATTERY_OUT:-/tmp/rogbid-battery-trials}"

if ! [[ "$DURATION_SECONDS" =~ ^[0-9]+$ ]] || (( DURATION_SECONDS < 30 )); then
  echo "DURATION_SECONDS must be an integer >= 30" >&2
  exit 1
fi
if ! [[ "$INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || (( INTERVAL_SECONDS < 5 )); then
  echo "INTERVAL_SECONDS must be an integer >= 5" >&2
  exit 1
fi
case "$MODE" in
  idle|dual_pcm|aac) ;;
  *)
    echo "MODE must be idle, dual_pcm, or aac" >&2
    exit 1
    ;;
esac

run_id="$(date -u +%Y%m%dT%H%M%SZ)-$MODE"
OUT_DIR="$OUT_ROOT/$run_id"
SAMPLES_DIR="$OUT_DIR/samples"
mkdir -p "$SAMPLES_DIR"

adb -s "$SERIAL" get-state >/dev/null

powered_snapshot="$(adb -s "$SERIAL" shell dumpsys battery | tr -d '\r')"
printf "%s\n" "$powered_snapshot" > "$OUT_DIR/battery-before.txt"
if [[ "${ALLOW_PLUGGED:-0}" != "1" ]] && printf "%s\n" "$powered_snapshot" \
    | grep -Eq 'AC powered: true|USB powered: true|Wireless powered: true'; then
  echo "Device reports plugged-in power. Unplug it for a drain trial, or set ALLOW_PLUGGED=1 for a dry run." >&2
  cat "$OUT_DIR/battery-before.txt" >&2
  exit 1
fi

adb -s "$SERIAL" shell dumpsys wifi > "$OUT_DIR/wifi-before.txt" 2>&1 || true
adb -s "$SERIAL" shell settings get global wifi_sleep_policy > "$OUT_DIR/wifi-sleep-policy.txt" 2>&1 || true
adb -s "$SERIAL" shell dumpsys deviceidle > "$OUT_DIR/deviceidle-before.txt" 2>&1 || true

csv="$OUT_DIR/battery.csv"
echo "timestamp_utc,elapsed_seconds,level,scale,voltage_mv,temp_tenths_c,status,ac_powered,usb_powered,wireless_powered" > "$csv"

start_epoch="$(date +%s)"
adb -s "$SERIAL" shell am start-foreground-service \
  -n "$PACKAGE/.BatteryTrialService" \
  -a "$PACKAGE.BATTERY_TRIAL_START" \
  --es mode "$MODE" \
  --ei duration_seconds "$DURATION_SECONDS" >/dev/null \
  || adb -s "$SERIAL" shell am startservice \
    -n "$PACKAGE/.BatteryTrialService" \
    -a "$PACKAGE.BATTERY_TRIAL_START" \
    --es mode "$MODE" \
    --ei duration_seconds "$DURATION_SECONDS" >/dev/null

adb -s "$SERIAL" shell input keyevent 223 >/dev/null 2>&1 || true

sample_battery() {
  local elapsed="$1"
  local sample_path="$SAMPLES_DIR/battery-$elapsed.txt"
  adb -s "$SERIAL" shell dumpsys battery | tr -d '\r' > "$sample_path"
  local level scale voltage temp status ac usb wireless
  level="$(awk -F': ' '/level:/ {print $2; exit}' "$sample_path")"
  scale="$(awk -F': ' '/scale:/ {print $2; exit}' "$sample_path")"
  voltage="$(awk -F': ' '/voltage:/ {print $2; exit}' "$sample_path")"
  temp="$(awk -F': ' '/temperature:/ {print $2; exit}' "$sample_path")"
  status="$(awk -F': ' '/status:/ {print $2; exit}' "$sample_path")"
  ac="$(awk -F': ' '/AC powered:/ {print $2; exit}' "$sample_path")"
  usb="$(awk -F': ' '/USB powered:/ {print $2; exit}' "$sample_path")"
  wireless="$(awk -F': ' '/Wireless powered:/ {print $2; exit}' "$sample_path")"
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ),$elapsed,$level,$scale,$voltage,$temp,$status,$ac,$usb,$wireless" >> "$csv"
}

elapsed=0
while (( elapsed <= DURATION_SECONDS )); do
  sample_battery "$elapsed"
  adb -s "$SERIAL" shell dumpsys wifi > "$SAMPLES_DIR/wifi-$elapsed.txt" 2>&1 || true
  adb -s "$SERIAL" shell dumpsys deviceidle > "$SAMPLES_DIR/deviceidle-$elapsed.txt" 2>&1 || true
  if (( elapsed == DURATION_SECONDS )); then
    break
  fi
  sleep "$INTERVAL_SECONDS"
  now="$(date +%s)"
  elapsed=$(( now - start_epoch ))
done

adb -s "$SERIAL" shell am startservice \
  -n "$PACKAGE/.BatteryTrialService" \
  -a "$PACKAGE.BATTERY_TRIAL_STOP" >/dev/null 2>&1 || true

adb -s "$SERIAL" shell dumpsys battery | tr -d '\r' > "$OUT_DIR/battery-after.txt"
adb -s "$SERIAL" shell dumpsys wifi > "$OUT_DIR/wifi-after.txt" 2>&1 || true
adb -s "$SERIAL" shell dumpsys deviceidle > "$OUT_DIR/deviceidle-after.txt" 2>&1 || true
adb -s "$SERIAL" exec-out run-as "$PACKAGE" cat files/battery-trial-evidence.txt > "$OUT_DIR/evidence.txt" 2>/dev/null || true
adb -s "$SERIAL" shell run-as "$PACKAGE" ls -lh files > "$OUT_DIR/app-files-ls.txt" 2>/dev/null || true

first_level="$(awk -F, 'NR==2 {print $3}' "$csv")"
last_level="$(awk -F, 'END {print $3}' "$csv")"
first_voltage="$(awk -F, 'NR==2 {print $5}' "$csv")"
last_voltage="$(awk -F, 'END {print $5}' "$csv")"
elapsed_last="$(awk -F, 'END {print $2}' "$csv")"

{
  echo "run_id=$run_id"
  echo "serial=$SERIAL"
  echo "mode=$MODE"
  echo "duration_seconds=$DURATION_SECONDS"
  echo "sample_interval_seconds=$INTERVAL_SECONDS"
  echo "elapsed_sampled_seconds=$elapsed_last"
  echo "level_start=$first_level"
  echo "level_end=$last_level"
  echo "level_delta=$(( last_level - first_level ))"
  echo "voltage_start_mv=$first_voltage"
  echo "voltage_end_mv=$last_voltage"
  echo "voltage_delta_mv=$(( last_voltage - first_voltage ))"
  echo "out_dir=$OUT_DIR"
} | tee "$OUT_DIR/summary.txt"

echo "--- service evidence ---"
cat "$OUT_DIR/evidence.txt"

echo "--- app files ---"
cat "$OUT_DIR/app-files-ls.txt"
