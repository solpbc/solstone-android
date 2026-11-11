# Architecture

`solstone-android` is structured as one repo with multiple app artifacts.

the phone and `apps/wear` are separate maintained release targets. the older `apps/watch`, glasses/Rokid, and Rogbid artifacts remain parked historical evidence.

## Shape

- `apps/*` are installable Android apps.
- `core/*` modules hold Android-light domain logic that should be JVM-testable when possible.
- `platform/*` modules wrap Android framework APIs such as camera, audio, location, foreground service, permissions, WorkManager, and power state.
- `formfactor/*` modules hold watch/phone/glasses-specific UI and policy helpers.
- `testing/*` holds fake sensor streams, protocol fixtures, and hardware-independent harnesses.

## Production Direction

The Rogbid validation app is intentionally not production-shaped. It proves hardware and protocol feasibility. Production observer work graduates shared pieces into the reusable layers:

1. PL QR parsing, on-device identity, CSR, Conscrypt TLS 1.3, and mTLS status calls live in `core/pl`, `core/identity`, and the Conscrypt transport adapter.
2. Spool, segment, queue, observer protocol, and sync policy live in Android-light `core`, `harness`, and `platform/work` modules where practical.
3. Camera, audio, location, foreground-service, metadata, and power behavior live behind `platform` adapters.
4. Phone-specific UI and policy are maintained in its app module and `formfactor/*`; the older watch and glasses sources remain as parked shared-harness evidence.

The RV203 glasses observer has a hardware-validated HOME/capture-mode path; see
[glasses HOME observer milestone](glasses-home-observer-milestone-2026-07-02.md).

## Wear OS courier

`apps/wear` takes in audio the owner shares for their journal, after they start a session on the watch. private storage holds five-minute audio segments; Data Layer channels carry framed bundles to the phone. message payloads carry receipts and contact state; routing paths contain no recording metadata. replicated Data Layer items and assets are unused. manifest rules exclude backup and device transfer.

the phone publishes each received bundle to its spool and commits a receipt in Room before acknowledging it. watch material uses the `watch` stream, source `watch-audio`, and the watch’s recording clock. optional location records declare `wearos`. journal credentials remain on the phone.

`platform/wearable` pins the audited Google Play services versions. `WearTaskPolicy` disables invocation and timing telemetry under the SDK configuration monitor while constructing each API task. await tasks and perform stream I/O outside that monitor. dependency upgrades require checking this boundary again. channel attempts have a deadline; failure retains the bundle for another attempt. a persisted WorkManager recovery task resumes delivery after process loss without starting intake.
