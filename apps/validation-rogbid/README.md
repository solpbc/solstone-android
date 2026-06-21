# Rogbid Validation — Model X ADB smoke spike

Minimal Android app for validating the full Linux build host -> ADB -> Rogbid Model X loop,
then proving foreground access to the watch cameras, microphone, and HTTPS upload path.

The watch validated on 2026-06-11 as Android 9 / API 28, `arm64-v8a`, model `Model_X`,
serial `46734915123233`. This spike deliberately avoids Compose, foreground services,
background capture, and production upload plumbing. Its first job is to prove the app loop on the watch:

1. build an APK on the Linux build host;
2. install it over wired ADB to serial `46734915123233`;
3. launch the Activity;
4. drive a simple button tap;
5. verify UI state via UIAutomator / screenshot / logcat.

Its second job is to prove visible, user-initiated media access:

1. enumerate the camera and audio input surfaces Android exposes;
2. capture one still image from camera `0` and camera `1`;
3. record a three-second `MediaRecorder` sample from the default microphone;
4. record a short raw PCM sample from each enumerated audio input;
5. write evidence and pull artifacts through `run-as`.

Its third job is to prove HTTPS upload over the watch's own Wi-Fi:

1. start a tiny HTTPS receiver on the Linux build host;
2. generate a throwaway self-signed certificate for the host LAN IP;
3. pass the upload URL and SHA-256 certificate fingerprint into the app;
4. POST a JSON bundle containing media evidence plus JPG/M4A/PCM artifacts;
5. verify app-private upload evidence and server-side receipt.

Its fourth job is to prove deferred/retry upload:

1. enqueue a WorkManager upload while the HTTPS receiver is intentionally down;
2. record the first failed attempt as `retrying=true`;
3. start the receiver with the same pinned certificate;
4. verify WorkManager retries and completes the upload;
5. compare the two uploaded PCM artifacts server-side.

Its fifth job is to run fixed-window battery-drain trials without live ADB:

1. arm the trial over wired ADB while the watch is still charging;
2. inspect storage, codec, audio-policy, and Wi-Fi power surfaces;
3. let the app wait for charger disconnect, then start its own fixed timer;
4. recharge, then run the same unplugged screen-off `dual_pcm` mic trial;
5. reconnect wired ADB later and pull battery samples plus local capture file sizes.

Its sixth job is to characterize location hardware and providers:

1. inspect Android feature flags, GNSS services, Google location overlays, Wi-Fi,
   and telephony state through ADB;
2. run a normal app-level `LocationManager` probe with fine/coarse location
   permission;
3. verify whether GPS and/or network location can return fixes without an
   activated cellular plan.

Its seventh job is to validate side-camera QR scanning:

1. open `camera_0` with a live on-screen preview;
2. decode QR frames locally with ZXing core, no network or external scanner app;
3. write first-frame / first-decode evidence for repeatable distance and QR-size
   tests.

Its eighth job is to validate direct LAN private-link pairing:

1. parse a `https://link.solpbc.org/p#...` direct PL QR payload;
2. generate an on-watch ECDSA-P256 keypair and PKCS#10 CSR;
3. connect to the solstone secure listener with TLS 1.3 + PL framing;
4. submit `/app/network/pair`, persist the returned PL bundle, reconnect with the
   client cert, and call `/app/network/api/status` over the tunnel.

## Build

```bash
source ~/android-dev/env.sh
cd ~/projects/solstone-android
./gradlew :apps:validation-rogbid:assembleDebug
```

## Full watch validation

```bash
tools/rogbid/validate-rogbid-adb.sh
```

The button has content description `hello_button`; the status text has content
description `hello_status`. A successful tap changes the visible status from
`Ready on Rogbid Model X` to `Hello tapped 1 time` and writes durable debug
evidence readable with:

```bash
adb -s 46734915123233 shell run-as app.solstone.validation.rogbid cat files/tap-evidence.txt
```

## Media validation

```bash
tools/rogbid/validate-rogbid-media.sh
```

The media button has content description `media_button`. A successful run writes
`files/media-evidence.txt` with `camera_count`, `audio_input_count`, JPG byte
counts plus facing/orientation for both camera IDs, the default `mic_m4a` /
`mic_max_amplitude` lines, and per-input PCM byte/max-amplitude lines. The
script pulls artifacts to `/tmp/rogbid-media/` on the Linux build host:

```bash
/tmp/rogbid-media/camera-0.jpg
/tmp/rogbid-media/camera-1.jpg
/tmp/rogbid-media/mic.m4a
/tmp/rogbid-media/mic-input-0.pcm
/tmp/rogbid-media/mic-input-1.pcm
```

## HTTPS upload validation

```bash
tools/rogbid/validate-rogbid-upload.sh
```

The upload button has content description `upload_button`. The script discovers
the watch Wi-Fi IP, picks the host LAN source address for that route, generates a
one-day self-signed certificate with the host LAN IP in the SAN, starts
`tools/rogbid/https_upload_server.py`, and launches the app with:

```bash
--es upload_url https://<host-lan-ip>:8443/upload
--es upload_cert_sha256 <server-cert-sha256>
```

The app pins that certificate fingerprint for this spike. It does not install a
test CA on the watch and it does not blindly trust arbitrary certificates. The
server writes received uploads to `/tmp/rogbid-upload-server/uploads/` on the validation host.

A successful run writes `files/upload-evidence.txt` with `payload_bytes`,
`uploaded_files`, `http_code=200`, and `completed=...`.

## Deferred upload validation

```bash
tools/rogbid/validate-rogbid-deferred-upload.sh
```

The deferred upload button has content description `deferred_upload_button`.
The script runs media capture, taps the deferred button with the server down,
waits for `files/deferred-upload-evidence.txt` to show `attempt=0` plus
`retrying=true`, then starts the server and waits for a retry with
`http_code=200`.

The same script decodes `mic-input-0.pcm` and `mic-input-1.pcm` from the received
JSON and prints byte equality, length equality, SHA-256s, RMS/max levels, and an
overlap correlation. The PCM comparison proves whether the captures are identical
byte streams; it is not a complete physical multi-mic characterization because
the current app records those routes sequentially.

## Battery-drain validation

The primary drain flow does not require ADB over Wi-Fi. Wired ADB is only used to
build/install/arm the app before the run and to pull results after the run. The
app waits until Android reports `plugged=0`, starts the fixed timer, samples
battery locally, stops itself at the requested duration, and keeps the evidence
in app-private storage. During the armed/trial window it holds a partial wake
lock so screen-off measurements keep executing.

The Rogbid firmware also ships `com.wiite.cleantask`, which force-stops
non-system apps shortly after `SCREEN_OFF` when `settings system isClear=1`.
The arm script defaults `ROGBID_CLEAR_MODE=disable`, which sets `isClear=0`
before the trial. This is required for third-party screen-off capture to survive
and should be held constant across baseline and mic trials. Use
`ROGBID_CLEAR_MODE=leave` only when intentionally characterizing stock cleanup
behavior.

Inspect power/storage/media surfaces:

```bash
tools/rogbid/inspect-rogbid-battery-surfaces.sh 46734915123233
```

Arm a baseline trial while the watch is wired and charging:

```bash
tools/rogbid/arm-rogbid-battery-trial.sh 46734915123233 idle 1800 60
```

When the script prints `UNPLUG NOW`, disconnect the watch. The app starts the
trial on unplug and stops after exactly 1800 seconds. Return after at least that
duration plus a small buffer, reconnect the watch, then pull the result:

```bash
tools/rogbid/pull-rogbid-battery-trial.sh 46734915123233
```

Recharge to the same starting condition and repeat for the mic trial:

```bash
tools/rogbid/arm-rogbid-battery-trial.sh 46734915123233 dual_pcm 1800 60
tools/rogbid/pull-rogbid-battery-trial.sh 46734915123233
```

To remove Wi-Fi as a confounder, run both baseline and mic trials with the same
Wi-Fi setting. For the cleanest microphone-vs-idle comparison, disable Wi-Fi
before arming both runs:

```bash
ROGBID_WIFI_MODE=disable tools/rogbid/arm-rogbid-battery-trial.sh 46734915123233 idle 1800 60
ROGBID_WIFI_MODE=disable tools/rogbid/arm-rogbid-battery-trial.sh 46734915123233 dual_pcm 1800 60
```

Use `ROGBID_WIFI_MODE=enable` for a Wi-Fi-on condition, or leave it unset to
preserve the current watch setting. The pull script warns if any sample inside
the fixed trial window reports `plugged!=0`, which means the watch was
reconnected too early for that run. The pull script also records `isClear` so
each result shows whether the vendor cleaner was disabled.

Trial modes:

- `idle`: foreground service only, no microphone.
- `dual_pcm`: two `AudioRecord` streams pinned to the first two exposed input routes, writing local PCM.
- `dual_camera`: captures still JPEGs from camera `0` and camera `1` each cycle, writing local files under `files/battery-camera/`. Default camera interval is 5 seconds; override with `ROGBID_CAMERA_INTERVAL_SECONDS`.
- `aac`: one `MediaRecorder` AAC/M4A capture from the default mic for compression/storage comparison.

Camera soak, Wi-Fi off, no PCM:

```bash
ROGBID_WIFI_MODE=disable ROGBID_CAMERA_INTERVAL_SECONDS=5 \
  tools/rogbid/arm-rogbid-battery-trial.sh 46734915123233 dual_camera 1800 60
tools/rogbid/pull-rogbid-battery-trial.sh 46734915123233
```

The pull script records camera file count and total KB. Set `PULL_CAMERA=1` when
pulling if the full JPEG set is needed locally.

Result note: [battery-results-2026-06-11.md](docs/battery-results-2026-06-11.md)
captures the first valid idle-vs-PCM comparison and the vendor-cleaner failure
mode discovered while running it.

Location result note: [location-results-2026-06-14.md](docs/location-results-2026-06-14.md)
records the app-level GPS/network-provider probe. The watch advertises
`android.hardware.location.gps`, returned a real GPS fix to the spike app, and
does not require an activated 4G/cellular link for GPS. Its `network` provider
exists but stayed disabled at the third-party app layer in this firmware state.

## QR preview / scan validation

The installed debug build includes a `QR scan` button on the main screen. Tap it
to open `QrProbeActivity`, which keeps the screen awake only while the scanner is
open, starts `camera_0` (the back/side camera), shows a live preview, and decodes
QR frames locally with ZXing. It writes evidence to app-private
`files/qr-evidence.txt`.

Morning manual test:

1. Wake the watch. The main `Rogbid media` screen should already be open.
2. Tap `QR scan`.
3. Point the side camera at a bright, high-contrast QR code. Start with a large
   QR on a phone/laptop screen at about 15-25 cm.
4. Watch for status text changing from `Preview live - point side camera at QR`
   to `Decoded: ...`.
5. If it does not decode, try 10 cm, 15 cm, 20 cm, and 30 cm. The camera HAL
   reports `focus-mode=fixed`, so distance is the real unknown.

ADB evidence pull:

```bash
adb -s 46734915123233 shell run-as app.solstone.validation.rogbid cat files/qr-evidence.txt
```

Smoke result from the build installed 2026-06-14:

```text
camera_opened=2026-06-14 23:59:37
facing=back
orientation=90
preview_size=640x480
zoom=0
first_frame_ms=1024
```

Physical QR validation: **PASS** (manual watch test morning 2026-06-15). The
side-camera preview + local ZXing decode flow worked and was easy to use. No
distance/lighting matrix was captured in that manual check, so fixed-focus range
characterization remains optional follow-up rather than a feasibility blocker.

## PL QR link validation

The same `QR scan` activity now recognizes direct solstone PL pair links. When a
valid direct pair QR is decoded, it closes the camera, generates a local
ECDSA-P256 keypair/CSR, pairs through the secure listener, stores the returned
bundle under app-private `files/pl-link/`, reconnects with the client
certificate, and calls `/app/network/api/status`. Evidence is written to
`files/pl-link-evidence.txt`.

The Rogbid Android 9 firmware's platform TLS provider only advertised
TLSv1/TLSv1.1/TLSv1.2 during the first injected test. The PL secure listener
requires TLS 1.3, so this spike packages Conscrypt (`conscrypt-android`) and
forces PL sockets through that provider.

ADB injection helper for a fresh pair link:

```bash
PAIR_LINK='https://link.solpbc.org/p#...' tools/rogbid/validate-rogbid-pl-link.sh
```

Validation on 2026-06-15 passed against the current solstone link code:

```text
pair_http=200
pair_tls_peer_chain_pinned=true
api_status_http=200
pl_link=success
```

The debug injection path is only for repeatable ADB validation. The normal path
is scanning the on-screen solstone QR with the side camera.

## Handoff for next test app

- Watch serial used for all trials: `46734915123233`.
- Android package: `app.solstone.validation.rogbid`.
- Debug APK on the validation host after build:
  `apps/validation-rogbid/build/outputs/apk/debug/validation-rogbid-debug.apk`.
- Build command on the validation host:
  `cd ~/projects/solstone-android && source ~/android-dev/env.sh && ./gradlew :apps:validation-rogbid:assembleDebug`.
- Pulled trial artifacts on the validation host live under `/tmp/rogbid-battery-trials/`,
  including:
  - `20260612T024646Z-idle` - Wi-Fi-off idle baseline.
  - `20260612T034107Z-dual_pcm` - Wi-Fi-off PCM.
  - `20260612T145939Z-idle` - Wi-Fi-on idle.
  - `20260612T161803Z-dual_pcm` - Wi-Fi-on PCM.
  - `20260612T172457Z-dual_camera` - Wi-Fi-off dual-camera stills every 5s.

Useful carry-forward findings:

- The fixed-window harness is the right shape: arm while wired, wait for
  `plugged=0`, run for a fixed duration locally, then pull results after
  reconnect.
- Set `settings system isClear=0` before screen-off trials; the vendor cleaner
  force-stopped the app when left enabled.
- The watch exposes two camera IDs and both worked under screen-off foreground
  service capture: 360 frames per camera over 30 minutes, zero failures, about
  14.2 MiB of JPEG payload.
- The 5-second dual-camera interval was a stress condition. It drained 100% to
  94% over 30 minutes with Wi-Fi off, compared with 100% to 96% for the
  Wi-Fi-off idle baseline.
- The two exposed audio input routes did not validate true concurrent two-mic
  capture. In both 30-minute PCM trials only one `AudioRecord` stream produced
  nonzero PCM, and the active route flipped between runs.
- The watch has built-in GPS. A normal app with fine/coarse location permission
  received a `gps` provider update at about 15 m reported accuracy. Network
  location was present as a feature/overlay but not enabled/requestable through
  the standard provider settings during the probe.
- QR preview/scanning is validated. `camera_0` opened as the side/back camera
  with a 640x480 preview and delivered a first frame in 1024 ms; manual
  physical QR scanning passed morning 2026-06-15. QR decode is local via ZXing.
  A distance sweep remains optional because both cameras report fixed focus, but
  it is no longer a feasibility blocker.
- Direct LAN PL linking is validated against the current solstone link code via
  ADB-injected pair link. The watch needs packaged Conscrypt for TLS 1.3; with
  that in place it paired, stored a PL bundle, reconnected with mTLS, and got a
  200 response from `/app/network/api/status`.

The legacy `tools/rogbid/run-rogbid-battery-trial.sh` still exists for live
ADB-over-Wi-Fi sampling of `dumpsys wifi` / `deviceidle`, but it is no longer the
default path for battery-drain comparison.

## Maestro note

Maestro 2.6 on the Rogbid can launch the app and assert the initial text, but
tap injection was not reliable in the 2026-06-11 run: text, point, and resource-id
tap modes all reported `COMPLETED` while app-private evidence stayed `launched`.
Use the ADB script above as the canonical full-flow validation until that driver
behavior is understood.
