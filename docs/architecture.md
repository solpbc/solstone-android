# Architecture

`solstone-android` is structured as one repo with multiple app artifacts.

Phone is the sole maintained and release-quality Android surface. Watch,
glasses/Rokid, and Rogbid artifacts remain in the source tree as parked
historical/experimental evidence, not routine-maintenance or release targets.

## Shape

- `apps/*` are installable Android apps.
- `core/*` modules hold Android-light domain logic that should be JVM-testable when possible.
- `platform/*` modules wrap Android framework APIs such as camera, audio, location, foreground service, permissions, WorkManager, and power state.
- `formfactor/*` modules hold watch/phone/glasses-specific UI and policy helpers.
- `testing/*` holds fake sensor streams, protocol fixtures, and hardware-independent harnesses.

## Production Direction

The Rogbid validation app is intentionally not production-shaped. It proves hardware and protocol feasibility. Production observer work graduates shared pieces into the reusable layers:

1. PL QR parsing, on-device identity, CSR, Conscrypt TLS 1.3, and mTLS status calls live in `core/pl`, `core/identity`, and the Conscrypt transport adapter.
2. Spool, segment, queue, observer registration, and sync policy live in Android-light `core`, `harness`, and `platform/work` modules where practical.
3. Camera, audio, location, foreground-service, metadata, and power behavior live behind `platform` adapters.
4. Phone-specific UI and policy are maintained in its app module and `formfactor/*`; the watch and glasses-specific sources remain as parked shared-harness evidence.

The RV203 glasses observer has a hardware-validated HOME/capture-mode path; see
[glasses HOME observer milestone](glasses-home-observer-milestone-2026-07-02.md).
