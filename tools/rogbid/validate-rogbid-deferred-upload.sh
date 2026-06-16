#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
set -euo pipefail

SERIAL="${1:-46734915123233}"
PACKAGE="app.solstone.validation.rogbid"
APK="apps/validation-rogbid/build/outputs/apk/debug/validation-rogbid-debug.apk"
PORT="${ROGBID_UPLOAD_PORT:-8443}"
BASE_DIR="/tmp/rogbid-deferred-upload-server"
CERT="$BASE_DIR/server.crt"
KEY="$BASE_DIR/server.key"
OPENSSL_CONF="$BASE_DIR/openssl.cnf"
UPLOAD_DIR="$BASE_DIR/uploads"
SERVER_LOG="$BASE_DIR/server.log"

if ! [[ "$PORT" =~ ^[0-9]+$ ]] || (( PORT < 1 || PORT > 65535 )); then
  echo "Invalid ROGBID_UPLOAD_PORT: $PORT" >&2
  exit 1
fi

watch_ip="$(adb -s "$SERIAL" shell ip -4 addr show wlan0 \
  | tr -d '\r' \
  | awk '/inet / { sub(/\/.*/, "", $2); print $2; exit }')"
if [[ -z "$watch_ip" ]]; then
  echo "Unable to determine watch Wi-Fi IP from wlan0" >&2
  exit 1
fi
if ! [[ "$watch_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
  echo "Unexpected watch Wi-Fi IP: $watch_ip" >&2
  exit 1
fi

host_ip="${ROGBID_UPLOAD_HOST:-}"
if [[ -z "$host_ip" ]]; then
  host_ip="$(ip -4 route get "$watch_ip" \
    | awk '{ for (i = 1; i <= NF; i++) if ($i == "src") { print $(i + 1); exit } }')"
fi
if [[ -z "$host_ip" ]]; then
  echo "Unable to determine host IP for route to watch $watch_ip" >&2
  exit 1
fi
if ! [[ "$host_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
  echo "Unexpected host IP: $host_ip" >&2
  exit 1
fi

rm -rf "$BASE_DIR"
mkdir -p "$UPLOAD_DIR"

cat > "$OPENSSL_CONF" <<EOF
[req]
distinguished_name=req_distinguished_name
x509_extensions=v3_req
prompt=no

[req_distinguished_name]
CN=$host_ip

[v3_req]
subjectAltName=IP:$host_ip,DNS:android-build-host.local
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
EOF

openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -keyout "$KEY" \
  -out "$CERT" \
  -config "$OPENSSL_CONF" \
  >/dev/null 2>&1

fingerprint="$(openssl x509 -in "$CERT" -noout -fingerprint -sha256 \
  | cut -d= -f2 \
  | tr -d ':' \
  | tr 'A-F' 'a-f')"
upload_url="https://$host_ip:$PORT/upload"
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

start_server() {
  python3 tools/rogbid/https_upload_server.py \
    --host 0.0.0.0 \
    --port "$PORT" \
    --cert "$CERT" \
    --key "$KEY" \
    --out-dir "$UPLOAD_DIR" \
    > "$SERVER_LOG" 2>&1 &
  server_pid="$!"
  for _ in $(seq 1 50); do
    if curl -kfsS "https://$host_ip:$PORT/health" >/dev/null 2>&1; then
      return
    fi
    sleep 0.1
  done
  curl -kfsS "https://$host_ip:$PORT/health" >/dev/null
}

echo "--- deferred upload target ---"
echo "watch_ip=$watch_ip"
echo "host_ip=$host_ip"
echo "upload_url=$upload_url"
echo "cert_sha256=$fingerprint"

./gradlew :apps:validation-rogbid:assembleDebug

adb -s "$SERIAL" install -r "$APK"
adb -s "$SERIAL" shell pm grant "$PACKAGE" android.permission.CAMERA
adb -s "$SERIAL" shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO

adb -s "$SERIAL" shell input keyevent 224 >/dev/null 2>&1 || true
adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb -s "$SERIAL" shell am start -S -W \
  -n "$PACKAGE/.MainActivity" \
  --es upload_url "$upload_url" \
  --es upload_cert_sha256 "$fingerprint"

echo "--- initial UI ---"
adb -s "$SERIAL" shell uiautomator dump /sdcard/rogbid-deferred-window.xml >/dev/null
adb -s "$SERIAL" shell cat /sdcard/rogbid-deferred-window.xml \
  | tr '<>' '\n\n' \
  | grep -E 'Ready on Rogbid|media_button|upload_button|deferred_upload_button|Deferred upload'

adb -s "$SERIAL" shell input tap 200 141

echo "--- waiting for media evidence ---"
for _ in $(seq 1 30); do
  media_evidence="$(adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/media-evidence.txt 2>/dev/null || true)"
  if printf "%s\n" "$media_evidence" | grep -Eq 'completed=|ERROR='; then
    printf "%s\n" "$media_evidence"
    break
  fi
  sleep 1
done
media_evidence="$(adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/media-evidence.txt)"
printf "%s\n" "$media_evidence" | grep -q 'completed='

echo "--- enqueue deferred upload with server down ---"
adb -s "$SERIAL" shell input tap 200 237

first_failure=""
for _ in $(seq 1 60); do
  deferred_evidence="$(adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/deferred-upload-evidence.txt 2>/dev/null || true)"
  if printf "%s\n" "$deferred_evidence" | grep -q 'retrying=true'; then
    first_failure="$deferred_evidence"
    printf "%s\n" "$first_failure"
    break
  fi
  sleep 1
done
if [[ -z "$first_failure" ]]; then
  echo "Deferred upload did not record a retrying failure while server was down" >&2
  adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/deferred-upload-evidence.txt || true
  exit 1
fi
printf "%s\n" "$first_failure" | grep -q 'attempt=0'

echo "--- starting server for WorkManager retry ---"
start_server

final_evidence=""
for _ in $(seq 1 120); do
  deferred_evidence="$(adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/deferred-upload-evidence.txt 2>/dev/null || true)"
  if printf "%s\n" "$deferred_evidence" | grep -q 'completed='; then
    final_evidence="$deferred_evidence"
    printf "%s\n" "$final_evidence"
    break
  fi
  sleep 1
done
if [[ -z "$final_evidence" ]]; then
  echo "Deferred upload did not complete after server came up" >&2
  adb -s "$SERIAL" shell run-as "$PACKAGE" cat files/deferred-upload-evidence.txt || true
  exit 1
fi
printf "%s\n" "$final_evidence" | grep -q 'http_code=200'
printf "%s\n" "$final_evidence" | grep -q 'uploaded_files=5'
printf "%s\n" "$final_evidence" | grep -q 'retrying=false'

upload_file="$(find "$UPLOAD_DIR" -maxdepth 1 -name 'upload-*.json' -type f | sort | tail -n 1)"
if [[ -z "$upload_file" ]]; then
  echo "Server did not receive an upload" >&2
  exit 1
fi

echo "--- server receipt and PCM comparison ---"
python3 - "$upload_file" <<'PY'
import base64
import hashlib
import json
import math
import struct
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text())
files = {item["name"]: item for item in payload.get("files", [])}
print(f"path={path}")
print(f"device={payload.get('device_manufacturer')}/{payload.get('device_model')}")
print(f"files={len(files)}")
for name in sorted(files):
    item = files[name]
    print(f"{name} bytes={item['bytes']} sha256={item['sha256'][:16]}...")
print(f"body_bytes={path.stat().st_size}")

pcm0 = base64.b64decode(files["mic-input-0.pcm"]["base64"])
pcm1 = base64.b64decode(files["mic-input-1.pcm"]["base64"])
same = pcm0 == pcm1
min_len = min(len(pcm0), len(pcm1))
matching_positions = sum(1 for a, b in zip(pcm0[:min_len], pcm1[:min_len]) if a == b)
print(f"pcm_identical={str(same).lower()}")
print(f"pcm_same_length={str(len(pcm0) == len(pcm1)).lower()}")
print(f"pcm0_sha256={hashlib.sha256(pcm0).hexdigest()}")
print(f"pcm1_sha256={hashlib.sha256(pcm1).hexdigest()}")
print(f"pcm_min_len={min_len}")
print(f"pcm_matching_byte_positions={matching_positions}")

sample_count = min_len // 2
if sample_count:
    pcm0_samples = struct.unpack("<" + "h" * sample_count, pcm0[:sample_count * 2])
    pcm1_samples = struct.unpack("<" + "h" * sample_count, pcm1[:sample_count * 2])
    mean0 = sum(pcm0_samples) / sample_count
    mean1 = sum(pcm1_samples) / sample_count
    rms0 = math.sqrt(sum(s * s for s in pcm0_samples) / sample_count)
    rms1 = math.sqrt(sum(s * s for s in pcm1_samples) / sample_count)
    max0 = max(abs(s) for s in pcm0_samples)
    max1 = max(abs(s) for s in pcm1_samples)
    numerator = sum((a - mean0) * (b - mean1) for a, b in zip(pcm0_samples, pcm1_samples))
    denom0 = math.sqrt(sum((a - mean0) ** 2 for a in pcm0_samples))
    denom1 = math.sqrt(sum((b - mean1) ** 2 for b in pcm1_samples))
    corr = numerator / (denom0 * denom1) if denom0 and denom1 else 0.0
    print(f"pcm0_rms={rms0:.2f} pcm0_max_abs={max0}")
    print(f"pcm1_rms={rms1:.2f} pcm1_max_abs={max1}")
    print(f"pcm_pearson_overlap={corr:.4f}")
PY

echo "--- server log ---"
cat "$SERVER_LOG"
