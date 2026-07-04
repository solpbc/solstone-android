# Foreground Service Matrix

Regenerated from the app manifests — when a manifest's service declaration or permission set changes, update this file in the same change.

Per-app `foregroundServiceType` (never the data-sync type):
- `apps/phone` and `apps/watch`: `microphone|location|camera`
- `apps/glasses`: `microphone|camera` (the RV203 has no GPS/network location provider)
- `apps/validation-rogbid` (hardware-validation target, not an observer app): `camera|microphone`

API 28-32:
- Required manifest permissions: `FOREGROUND_SERVICE`, `RECORD_AUDIO`, `CAMERA`, plus `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` on phone/watch.
- Notification permission is not runtime-gated.
- Start the observer engine only from the visible launcher after the runtime permissions are granted.

API 33:
- Adds `POST_NOTIFICATIONS` (runtime-gated).
- Start the observer engine only from the visible launcher after the runtime permissions are granted.

API 34+:
- Adds `FOREGROUND_SERVICE_MICROPHONE` and `FOREGROUND_SERVICE_CAMERA` everywhere, plus `FOREGROUND_SERVICE_LOCATION` on phone/watch.
- The service declaration must carry exactly the per-app type list above.
- Microphone, camera, and location access are while-in-use; start the service only from a visible activity after runtime permissions are granted.

Location behavior:
- Location permission is requested in the launcher alongside audio (phone/watch only).
- Location capture uses passive/network fixes only and never forces continuous GPS.
- A no-fix window seals an honest zero-file location segment with a gap; it never fabricates a position.

Boot behavior:
- `BOOT_COMPLETED` may re-arm a tappable needs-attention notification only when observing was persisted as desired-on.
- The service cancels that boot attention notification after a successful non-stopping foreground-service start.
- Boot must not start the service or begin microphone input.
