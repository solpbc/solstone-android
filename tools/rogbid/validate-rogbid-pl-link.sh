#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
set -euo pipefail

SERIAL="${1:-46734915123233}"
PAIR_LINK="${PAIR_LINK:-${2:-}}"
PACKAGE="org.solpbc.rogbidhello"
COMPONENT="$PACKAGE/.QrProbeActivity"

if [[ -z "$PAIR_LINK" ]]; then
  echo "usage: PAIR_LINK='https://link.solpbc.org/p#...' $0 [serial]" >&2
  exit 2
fi

adb -s "$SERIAL" shell am force-stop "$PACKAGE" >/dev/null
adb -s "$SERIAL" shell run-as "$PACKAGE" \
  rm -f files/qr-evidence.txt files/pl-link-evidence.txt >/dev/null 2>&1 || true

adb -s "$SERIAL" shell am start \
  -n "$COMPONENT" \
  --es pl_pair_link "$PAIR_LINK" >/dev/null

for _ in $(seq 1 40); do
  evidence="$(adb -s "$SERIAL" shell run-as "$PACKAGE" \
    cat files/pl-link-evidence.txt 2>/dev/null || true)"
  if grep -qE '(^completed=|^ERROR=)' <<<"$evidence"; then
    break
  fi
  sleep 1
done

echo "==== qr-evidence.txt ===="
adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/qr-evidence.txt
echo "==== pl-link-evidence.txt ===="
adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/pl-link-evidence.txt
