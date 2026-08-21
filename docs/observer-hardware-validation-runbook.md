# Observer hardware validation runbook

End-to-end on-device validation procedure for the installable Solstone observer apps
(`:apps:watch`, `:apps:phone`) on real hardware, plus the recorded results of the Wave-3 validation
pass. Instrumented/JVM tests gate logic in CI; this runbook covers what CI structurally cannot —
the real-runtime, real-sensor, real-journal behavior that only surfaces on a device.

## Why this exists

`make ci` runs JVM unit tests + the core-purity and privacy-dependency gates + APK/androidTest
assembly. It does **not** run instrumented (GMD) tests and never touches a physical device. Several
defect classes only appear on the Android runtime or on real silicon:

- host-JDK APIs absent on the Android runtime (`make ci`'s JVM tests pass; the device crashes),
- main-thread database access (Room's main-thread assertion only fires at runtime),
- real-sensor behavior (audio signal, camera capture, location fixes),
- the live mTLS pair → PL-status → mTLS authorization → ingest → reconcile round-trip against a journal.

So every observer app change is validated on-device after it lands, on both bracketing targets.

## Targets

| Role | Device | API | Camera path | TLS |
|------|--------|-----|-------------|-----|
| Watch | Rogbid Model X (`adb -s 46734915123233`) | 28 (Android 9) | legacy `android.hardware.Camera` | Conscrypt (TLS 1.3) |
| Phone | Galaxy A36 / `SM-A366E` (`adb -s RZGL11XCS9D`) | 36 (One UI 8) | Camera2 | platform |

Both reach the validation journal over Wi-Fi LAN. They bracket the supported SDK range
(`minSdk 26` … `compileSdk 36`) so the platform-adapter seam is exercised on real silicon at both
ends.

> Maestro tap-injection is unreliable on the Rogbid — drive its screens with `adb shell input` +
> `uiautomator dump` / `screencap`, or by hand. Maestro is fine on the A36.

## Build the APKs

From a host with the Android toolchain:

```bash
make assemble-watch        # or: ./gradlew :apps:watch:assembleRealDebug
make assemble-phone        # or: ./gradlew :apps:phone:assembleRealDebug
```

Real-flavor APKs:
- `apps/watch/build/outputs/apk/real/debug/watch-real-debug.apk`
- `apps/phone/build/outputs/apk/real/debug/phone-real-debug.apk`

## Procedure (per device)

Substitute `$DEV` with the device serial and `$APP` with `app.solstone.observer.watch`
(watch) or `app.solstone.observer.phone` (phone).

### 1. Install + launch

```bash
adb -s $DEV install -r -d <app>-real-debug.apk
adb -s $DEV shell monkey -p $APP -c android.intent.category.LAUNCHER 1
# crash check — must be empty:
adb -s $DEV logcat -d -t 200 | grep -iE "FATAL|AndroidRuntime|IllegalStateException|main thread"
```

The app must reach a rendered home menu with six entries (Permissions, Scan pair QR, PL status
probe, Start/stop observing, Status + queue/sync, Evidence + export) and **must not** auto-start
observing on launch.

### 2. Grant permissions

```bash
for p in RECORD_AUDIO CAMERA ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
  adb -s $DEV shell pm grant $APP android.permission.$p
done
# API 29+ only (A36): ACCESS_BACKGROUND_LOCATION ; API 33+ only (A36): POST_NOTIFICATIONS
```

On API 28 (Rogbid) `ACCESS_BACKGROUND_LOCATION` and `POST_NOTIFICATIONS` do not exist; the
permission model treats them as non-applicable / non-gating there. The Permissions screen reflects
each permission's grant state.

### 3. Pair

Mint a pair link on the journal host (single-homed host emits a v04 link the parser accepts):

```bash
curl -s -X POST http://127.0.0.1:5015/app/network/pair-start \
  -H 'Content-Type: application/json' \
  -d '{"device_label":"<label>","role":"observer"}'
# -> { "pair_link": "https://go.solstone.app/p#<blob>", "nonce", "ca_fingerprint", "expires_in": 300, ... }
```

On the device, open **Scan pair QR** and scan the rendered pair-link QR (the scanner shares the
single camera lock with still-capture, so a scan and active observing never contend for the camera).
The screen reports pair success + the paired home label. Links expire in 300s — mint fresh per scan.

> For an automated/headless protocol e2e that bypasses the camera, the
> `LiveObserverDriverTest` instrumented test drives pair → PL-status → mTLS authorization → ingest →
> reconcile (+ mTLS-after-process-death) against the live journal directly from the minted link —
> see "Automated protocol e2e" below.

### 4. PL status probe

Open **PL status probe** — it opens the authenticated PL client to the paired home and reports
`NOT_PAIRED` / `PAIRED_BUT_UNREACHABLE` / `REACHABLE` (with HTTP status). `REACHABLE` gates
readiness to observe.

### 5. Start observing → verify the control center + capture

Open **Start/stop observing** → **Start**. Verify:

```bash
# foreground service + ongoing control notification:
adb -s $DEV shell dumpsys notification --noredact | grep -A2 "channel=solstone_observer"
#   -> id=101, text "Observer — On", flags ONGOING|FOREGROUND_SERVICE
# multi-source capture into the spool (observer stream = audio + camera; location stream separate):
adb -s $DEV shell run-as $APP ls -R files/spool
#   -> files/spool/<day>/observer/<seg>/{audio.m4a, camera-*.jpg, manifest}
#   -> files/spool/<day>/location/<seg>/{location.jsonl, manifest}
```

The ongoing FGS notification is the always-on control center; the status surface must never read
"On" over a dead service (it binds the honest-state reducer, fed the real FGS heartbeat-freshness).

### 6. Status + queue/sync, evidence + export

- **Status + queue/sync** shows the reduced observer state + reason and the Room-backed queue
  (pending count, last success/failure). Reads load off the main thread; a failed read shows a
  visible error state distinct from an empty queue.
- **Evidence + export** lists sealed segments with per-file provenance (source id, name, media type,
  sha256, size). Export copies a selected bundle into the app's external files area.
- Location rows appear as ordinary `observer`-stream evidence and sync with the rest of the
  observer segment.

### 7. Background sync + journal-side visibility

With the device paired + reachable, the sync worker drains sealed `observer`-stream segments to the
journal. Confirm journal-side visibility by reconciling the day (the journal lists the uploaded
segment + its file shas). Then **Stop** observing; the foreground service stops cleanly.

### 8. Pull evidence

```bash
adb -s $DEV shell run-as $APP tar -czf - files/spool > spool-$DEV.tgz
adb -s $DEV shell run-as $APP cat files/spool/<day>/observer/<seg>/manifest
```

## Automated protocol e2e (`LiveObserverDriverTest`)

The proven, repeatable pair → PL-status → mTLS authorization → ingest → reconcile round-trip against a live journal,
without a camera scan:

```bash
./gradlew :platform:pl-transport-conscrypt:assembleDebugAndroidTest
adb -s $DEV install -r -t platform/pl-transport-conscrypt/build/outputs/apk/androidTest/debug/pl-transport-conscrypt-debug-androidTest.apk
# mint a fresh pair link (step 3), then:
adb -s $DEV shell am instrument -w \
  -e pairLink '<pair_link>' \
  -e fixturePath '/data/local/tmp/solstone-validation.wav' \
  -e day 'YYYYMMDD' -e segment 'HHMMSS_LEN' \
  -e class app.solstone.platform.pl.transport.conscrypt.LiveObserverDriverTest \
  app.solstone.platform.pl.transport.conscrypt.test/androidx.test.runner.AndroidJUnitRunner
#   -> OK (5 tests)
```

The library androidTest module sets `testOptions.targetSdk = 35` so the test APK installs on API 36,
and declares INTERNET in its androidTest manifest for the live socket.
`fixturePath`, `day`, and `segment` are required for t3: the file must be a readable WAV on the
target device. The driver does not synthesize a payload or derive a segment from byte length.
Optional direct-driver arguments are `deviceLabel` (default `android-validation`), `platform`
(default `android`), `hostname` (default `android-validation`), `streamType` (default `watch`),
and `version` (default `0.1-validation`). `hostname` and `platform` also populate the ingest
envelope metadata.

## Phone realDebug SPL integration gate (G1–G5)

This is the coordinator-owned contract-v1 live-relay gate, not part of `make ci-device`. Invoke it
through `android_gate_coordinator.py`; the sole operator option is its absolute clean Android
checkout. `GateAction` in `core/gate/src/main/kotlin/app/solstone/core/gate/GateAction.kt` is the
Android action truth source. The coordinator supplies its exact wire names and sequences.

The fixed instrumentation identity is:

```bash
APP=app.solstone.observer.phone
TEST_APP=app.solstone.observer.phone.test
CLASS=app.solstone.observer.phone.SplIntegrationGateDriverTest
COMPONENT=app.solstone.observer.phone.test/androidx.test.runner.AndroidJUnitRunner
```

The coordinator atomically installs the mode-0600 G1 authority at the exact target-app path below.
It contains exact contract-v1 correlation keys and the pair authority. The driver bounded-reads it
once and deletes it before parsing or network work. Pair authority never appears in argv:

```bash
files/solstone-android-gate/v1/pair-authority.json
```

Every invocation uses only `class`, `gate_contract_version`, `gate_action`, `gate_run_nonce`, and
`gate_action_sequence`, plus the action-specific non-secret commitments defined by `GateAction` and
the coordinator contract. The command shape is:

```bash
adb shell am instrument -w \
  -e class "$CLASS" \
  -e gate_contract_version 1 \
  -e gate_action "$ACTION" \
  -e gate_run_nonce "$RUN_NONCE" \
  -e gate_action_sequence "$SEQUENCE" \
  "$COMPONENT"
```

G1 additionally supplies `gate_fixture_path`, `gate_observer_day`, `gate_segment`,
`gate_expected_round_trip_bytes`, and `gate_expected_round_trip_sha256`. The path is a readable
target-device WAV fixture; the two commitment arguments are its exact byte length and lowercase
SHA-256. G2 and G3 additionally supply `gate_observer_day`,
`gate_expected_body_bytes`, `gate_expected_body_sha256`, and
`gate_expected_semantics_sha256`. All are independently derived by the coordinator's
`gate_identity.py` and host snapshots. No pair link, observer handle, token, credential, origin,
package, or host is an argument.

For a run nonce matching `YYYYMMDDTHHMMSSZ-<16 lowercase hex>`, hostname derivation is exact:

- `suffix` is characters `17:33`.
- `observer_hostname` is `android-gate-<suffix>.test`.
- G1 receives `gate_observer_day` and `gate_segment` explicitly and uses the supplied
  `gate_fixture_path` bytes without a generated fallback.
- `gate_expected_round_trip_bytes` and `gate_expected_round_trip_sha256` commit to that fixture.

The driver derives the hostname independently and validates the supplied G1 fixture length and
digest before consuming pair authority, constructing stores, probing status, or opening any socket.
It uses the explicit day, segment, and fixture bytes for production ingest/reconciliation.

G3 uses the exact atomic progress path below. The coordinator applies package denial only after
`partial_response_consumed`, restores it only after `degraded_status_recorded`, and permits recovery
after `network_restore_observed`. The Android driver does not toggle device radios or infer package
policy from device-wide network validation: request failure is the cut observation, the denied
production status probe must be named unreachable, and a bounded production status probe must
return HTTP 200 before restoration is recorded:

```bash
files/solstone-android-gate/v1/action-progress.json
```

The result is atomically replaced at:

```bash
adb exec-out run-as "$APP" \
  cat files/solstone-android-gate/v1/action-result.json > action-result.json
```

Accept only the exact snake_case schema with contract version 1, current run nonce and action, and
the exact next sequence. Malformed, stale, duplicate, future, or out-of-order evidence fails closed
regardless of timestamps. The coordinator owns package-policy restoration and verifies both
connectivity getters during cleanup. The app and instrumentation APKs must both contain the exact
`assets/solstone-android-gate-build-receipt.json` bound to the clean checkout HEAD.

For G1, `facts.pre_pair` proves empty persisted pairing state, including
`observer_handle_absent`; `facts.pair` records relay pairing; and
`facts.authenticated_status` records post-pair status. `facts.round_trip` is the local
fixture-integrity check: the coordinator commitment is in `expected_bytes`/`expected_sha256` and
the bytes read from `gate_fixture_path` are in `actual_bytes`/`actual_sha256`.
`facts.reconciliation` is the separate server-derived proof with exactly `server_segment`,
`server_name`, `submitted_name`, `matched_name`, `size`, `sha256`, and `status`; it is read from
the parsed journal collection response.

## Cleanup

- Device side: `adb -s $DEV uninstall $APP` (and the androidTest package) when done.
- Journal side: revoke the test observer/client pairings created during validation. Do this in a
  quiet window with no concurrent validation sessions writing the journal's authorized-clients state.

## Recorded results — Wave 3 (watch + phone functional apps)

Tree at validation: `:apps:watch` over the shared agnostic harness + the off-main-thread Room fix;
`:apps:phone` adopting the same shared harness (phone-vs-watch delta confined to the app shell, the
phone formfactor module, and the selected camera adapter — the shared contracts are not forked).

**GMD instrumented suite (managed `google_apis` emulator, `-gpu host`):**
- `:apps:watch` runtime tests — **4/4 pass** (launch/render/nav, FGS foreground + ongoing
  notification, shared-camera-lock arbitration, evidence screen real-UI render + error-vs-empty).
- `:apps:phone` runtime tests — **4/4 pass** (same coverage).

**Rogbid (API 28) — watch app, real flavor:**
- Installed + launched with no crash on the Android runtime (off-main-thread Room verified on real
  API-28 silicon).
- All six functional screens render legibly on the 400×456 display.
- Start observing → foreground service live with the ongoing control notification ("Observer — On",
  ONGOING|FOREGROUND_SERVICE); multi-source capture into the spool: legacy-camera stills @15s +
  passive location, correct `observer`/`location` stream split.
- Status + queue/sync renders the honest reduced state + the Room-backed queue (off-main load) on
  real silicon; Stop stops the service cleanly.
- `LiveObserverDriverTest` against the live journal — **OK (5 tests)** (pair → PL-status → mTLS authorization →
  ingest → reconcile + mTLS-after-process-death).

**Galaxy A36 (API 36) — phone app, real flavor:**
- Installed + launched with no crash on the Android runtime (off-main-thread Room verified on real
  API-36 silicon).
- Home menu renders, scrollable, on the 1080×2340 display; all six permissions granted (incl.
  background-location + notifications, which exist on API 36).
- Start observing → foreground service live with the ongoing control notification (id 101,
  ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE); multi-source capture into the spool: **Camera2** stills
  @15s + passive location, same pipeline as the watch through the same shared core/harness.
- `LiveObserverDriverTest` against the live journal — **OK (5 tests)**.

Net: the multi-target seam is proven on real silicon — the watch (API 28, legacy camera, Conscrypt)
and the phone (API 36, Camera2, platform TLS) run the **same** `core` + harness contracts, differing
only in the selected platform adapters and the per-form-factor app + UI.
