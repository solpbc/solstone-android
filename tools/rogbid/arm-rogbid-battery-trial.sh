#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
set -euo pipefail

SERIAL="${1:-46734915123233}"
MODE="${2:-idle}"
DURATION_SECONDS="${3:-1800}"
INTERVAL_SECONDS="${4:-60}"
PACKAGE="app.solstone.validation.rogbid"
APK="apps/validation-rogbid/build/outputs/apk/debug/validation-rogbid-debug.apk"
WIFI_MODE="${ROGBID_WIFI_MODE:-leave}"
CLEAR_MODE="${ROGBID_CLEAR_MODE:-disable}"
CAMERA_INTERVAL_SECONDS="${ROGBID_CAMERA_INTERVAL_SECONDS:-5}"

if ! [[ "$DURATION_SECONDS" =~ ^[0-9]+$ ]] || (( DURATION_SECONDS < 30 )); then
  echo "DURATION_SECONDS must be an integer >= 30" >&2
  exit 1
fi
if ! [[ "$INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || (( INTERVAL_SECONDS < 5 )); then
  echo "INTERVAL_SECONDS must be an integer >= 5" >&2
  exit 1
fi
if ! [[ "$CAMERA_INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || (( CAMERA_INTERVAL_SECONDS < 1 )); then
  echo "ROGBID_CAMERA_INTERVAL_SECONDS must be an integer >= 1" >&2
  exit 1
fi
case "$MODE" in
  idle|dual_pcm|dual_camera|aac) ;;
  *)
    echo "MODE must be idle, dual_pcm, dual_camera, or aac" >&2
    exit 1
    ;;
esac
case "$WIFI_MODE" in
  leave|disable|enable) ;;
  *)
    echo "ROGBID_WIFI_MODE must be leave, disable, or enable" >&2
    exit 1
    ;;
esac
case "$CLEAR_MODE" in
  leave|disable|enable) ;;
  *)
    echo "ROGBID_CLEAR_MODE must be leave, disable, or enable" >&2
    exit 1
    ;;
esac

./gradlew :apps:validation-rogbid:assembleDebug

adb -s "$SERIAL" get-state >/dev/null
adb -s "$SERIAL" install -r "$APK"
adb -s "$SERIAL" shell pm grant "$PACKAGE" android.permission.CAMERA >/dev/null 2>&1 || true
adb -s "$SERIAL" shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO
adb -s "$SERIAL" shell cmd deviceidle whitelist +"$PACKAGE" >/dev/null 2>&1 || true
adb -s "$SERIAL" shell cmd appops set "$PACKAGE" RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
adb -s "$SERIAL" shell cmd appops set "$PACKAGE" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true

case "$WIFI_MODE" in
  disable)
    adb -s "$SERIAL" shell svc wifi disable >/dev/null
    ;;
  enable)
    adb -s "$SERIAL" shell svc wifi enable >/dev/null
    wifi_connected="false"
    for _ in $(seq 1 120); do
      connectivity_snapshot="$(adb -s "$SERIAL" shell dumpsys connectivity 2>/dev/null | tr -d '\r' || true)"
      if printf "%s\n" "$connectivity_snapshot" \
          | grep -Eq 'NetworkAgentInfo.*WIFI.*CONNECTED/CONNECTED'; then
        wifi_connected="true"
        break
      fi
      sleep 1
    done
    if [[ "$wifi_connected" != "true" && "${ROGBID_ALLOW_WIFI_DISCONNECTED:-0}" != "1" ]]; then
      echo "Wi-Fi was enabled but did not report CONNECTED within 120s. Set ROGBID_ALLOW_WIFI_DISCONNECTED=1 to arm anyway." >&2
      exit 1
    fi
    ;;
esac
wifi_connected="${wifi_connected:-not_checked}"

clear_before="$(adb -s "$SERIAL" shell settings get system isClear 2>/dev/null | tr -d '\r' || true)"
case "$CLEAR_MODE" in
  disable)
    adb -s "$SERIAL" shell settings put system isClear 0 >/dev/null
    ;;
  enable)
    adb -s "$SERIAL" shell settings put system isClear 1 >/dev/null
    ;;
esac
clear_after="$(adb -s "$SERIAL" shell settings get system isClear 2>/dev/null | tr -d '\r' || true)"

adb -s "$SERIAL" shell run-as "$PACKAGE" rm -f \
  files/battery-trial-evidence.txt \
  files/battery-trial-samples.csv \
  files/battery-mic-aac.m4a \
  files/battery-mic-input-0.pcm \
  files/battery-mic-input-1.pcm >/dev/null 2>&1 || true
adb -s "$SERIAL" shell run-as "$PACKAGE" rm -rf files/battery-camera >/dev/null 2>&1 || true

battery_snapshot="$(adb -s "$SERIAL" shell dumpsys battery | tr -d '\r')"
printf "%s\n" "$battery_snapshot"
if [[ "${ALLOW_UNPLUGGED_ARM:-0}" != "1" ]] && ! printf "%s\n" "$battery_snapshot" \
    | grep -Eq 'AC powered: true|USB powered: true|Wireless powered: true'; then
  echo "Device does not report plugged-in power. Connect it before arming, or set ALLOW_UNPLUGGED_ARM=1 for a dry run." >&2
  exit 1
fi
adb -s "$SERIAL" shell dumpsys wifi > /tmp/rogbid-battery-wifi-before.txt 2>&1 || true
adb -s "$SERIAL" shell dumpsys deviceidle > /tmp/rogbid-battery-deviceidle-before.txt 2>&1 || true

adb -s "$SERIAL" shell am start-foreground-service \
  -n "$PACKAGE/.BatteryTrialService" \
  -a "$PACKAGE.BATTERY_TRIAL_START" \
  --es mode "$MODE" \
  --ei duration_seconds "$DURATION_SECONDS" \
  --ei sample_interval_seconds "$INTERVAL_SECONDS" \
  --ei camera_interval_seconds "$CAMERA_INTERVAL_SECONDS" \
  --ez wait_for_unplug true >/dev/null \
  || adb -s "$SERIAL" shell am startservice \
    -n "$PACKAGE/.BatteryTrialService" \
    -a "$PACKAGE.BATTERY_TRIAL_START" \
    --es mode "$MODE" \
    --ei duration_seconds "$DURATION_SECONDS" \
    --ei sample_interval_seconds "$INTERVAL_SECONDS" \
    --ei camera_interval_seconds "$CAMERA_INTERVAL_SECONDS" \
    --ez wait_for_unplug true >/dev/null

evidence=""
for _ in $(seq 1 10); do
  evidence="$(adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/battery-trial-evidence.txt 2>/dev/null || true)"
  if printf "%s\n" "$evidence" | grep -q '^armed='; then
    break
  fi
  sleep 1
done

printf "%s\n" "$evidence" | grep -q '^armed='
printf "%s\n" "$evidence" | grep -q '^wait_for_unplug=true'

adb -s "$SERIAL" shell input keyevent 223 >/dev/null 2>&1 || true

echo "--- battery trial armed ---"
printf "%s\n" "$evidence"
cat <<EOF

UNPLUG NOW.

The app will start the fixed ${DURATION_SECONDS}s ${MODE} trial when Android reports the charger is disconnected.
It samples battery locally every ${INTERVAL_SECONDS}s, stops itself at the fixed duration, and stores results on the watch.
For dual_camera mode, it captures both cameras every ${CAMERA_INTERVAL_SECONDS}s.

Come back after at least ${DURATION_SECONDS}s plus a small buffer, plug the watch back in, then run:
  tools/rogbid/pull-rogbid-battery-trial.sh ${SERIAL}

wifi_mode=${WIFI_MODE}
wifi_connected_before_arm=${wifi_connected}
clear_mode=${CLEAR_MODE}
isClear_before=${clear_before}
isClear_after=${clear_after}
camera_interval_seconds=${CAMERA_INTERVAL_SECONDS}
EOF
