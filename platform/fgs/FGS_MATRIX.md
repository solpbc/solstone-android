# Foreground Service Matrix

The observer uses a visible foreground service with `foregroundServiceType="microphone|location"` and never uses the data sync foreground-service type.

API 28-32:
- Required manifest permissions: `FOREGROUND_SERVICE`, `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`.
- Notification permission is not runtime-gated.
- Start the observer engine only from the visible launcher after microphone and location permissions are granted.

API 33:
- Required manifest permissions: `FOREGROUND_SERVICE`, `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`.
- Notification permission is runtime-gated.
- Start the observer engine only from the visible launcher after microphone and location permissions are granted.

API 34+:
- Required manifest permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_LOCATION`, `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`.
- The service declaration must use `foregroundServiceType="microphone|location"`.
- Microphone and location access are while-in-use; start the service only from a visible activity after runtime permissions are granted.

Location behavior:
- Location permission is requested in the launcher alongside audio.
- Location capture uses passive/network fixes only and never forces continuous GPS.
- A no-fix window seals an honest zero-file location segment with a gap; it never fabricates a position.

Boot behavior:
- `BOOT_COMPLETED` may re-arm a tappable needs-attention notification only when observing was persisted as desired-on.
- The service cancels that boot attention notification after a successful non-stopping foreground-service start.
- Boot must not start the service or begin microphone input.
