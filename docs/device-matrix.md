# Device Matrix

## Rogbid Model X

- Android 9 / API 28
- `arm64-v8a`
- Display: 400x456
- Package used by validation app: `app.solstone.validation.rogbid`
- ADB serial used during spike: `46734915123233`

Validated:

- side-camera QR preview and local ZXing decode,
- two exposed camera IDs,
- microphone via `AudioRecord` and `MediaRecorder`,
- WorkManager deferred upload over Wi-Fi,
- built-in GPS provider without activated cellular service,
- PL QR link pairing over LAN with TLS 1.3 through Conscrypt,
- foreground-service battery trials with the OEM cleaner disabled via `settings system isClear=0`.

Constraints:

- platform TLS provider did not expose TLS 1.3; validation app uses Conscrypt,
- camera code uses legacy `android.hardware.Camera`,
- network location was present but not proven enabled for a normal app,
- background survival depends on foreground service plus device/OEM power settings.

## RV203

- Sensor inventory confirmed 2026-06-26.

Present:

- accelerometer,
- gyroscope,
- linear acceleration,
- game rotation vector.

Absent:

- step counter,
- significant motion,
- activity recognition,
- magnetometer,
- GPS.

Constraints:

- no OS-precomputed motion summary; duty-cycled metadata snapshots are the low-power path,
- no compass/heading; tilt is pitch/roll only.

## Galaxy A36

- Model: `SM-A366E`
- Serial: `RZGL11XCS9D`
- Android 16 / API 36 / One UI 8
- `arm64-v8a`
- Phone validation target.
