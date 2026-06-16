#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
set -euo pipefail

WIRED_SERIAL="${1:-46734915123233}"
PORT="${ROGBID_ADB_PORT:-5555}"

if ! [[ "$PORT" =~ ^[0-9]+$ ]] || (( PORT < 1 || PORT > 65535 )); then
  echo "Invalid ROGBID_ADB_PORT: $PORT" >&2
  exit 1
fi

watch_ip="$(adb -s "$WIRED_SERIAL" shell ip -4 addr show wlan0 \
  | tr -d '\r' \
  | awk '/inet / { sub(/\/.*/, "", $2); print $2; exit }')"
if [[ -z "$watch_ip" ]]; then
  echo "Unable to determine watch Wi-Fi IP from wlan0" >&2
  exit 1
fi

adb -s "$WIRED_SERIAL" tcpip "$PORT"
sleep 2
adb connect "$watch_ip:$PORT"
adb -s "$watch_ip:$PORT" get-state

echo "wireless_serial=$watch_ip:$PORT"
adb devices -l
