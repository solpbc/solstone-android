# Architecture

`solstone-android` is structured as one repo with multiple app artifacts.

## Shape

- `apps/*` are installable Android apps.
- `core/*` modules hold Android-light domain logic that should be JVM-testable when possible.
- `platform/*` modules wrap Android framework APIs such as camera, audio, location, foreground service, permissions, WorkManager, and power state.
- `formfactor/*` modules hold watch/phone/glasses-specific UI and policy helpers.
- `testing/*` holds fake sensor streams, protocol fixtures, and hardware-independent harnesses.

## First Production Direction

The Rogbid validation app is intentionally not production-shaped. It proves hardware and protocol feasibility. Production observer work should graduate pieces out of it in this order:

1. PL QR parsing, on-device identity, CSR, Conscrypt TLS 1.3, and mTLS status calls into `core/pl` and `core/identity`.
2. Spool/segment/queue state machines into Android-light `core` modules.
3. Camera, audio, location, and foreground-service behavior into `platform` adapters.
4. Watch-specific UI and duty-cycle policy into the watch app and `formfactor/watch`.

