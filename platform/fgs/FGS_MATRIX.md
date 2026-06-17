# Foreground Service Matrix

S1 uses a visible foreground service with `foregroundServiceType="microphone"` and never uses the data sync foreground-service type.

API 28-32:
- Required manifest permissions: `FOREGROUND_SERVICE`, `RECORD_AUDIO`.
- Notification permission is not runtime-gated.
- Start the observer engine only from the visible launcher after microphone permission is granted.

API 33:
- Required manifest permissions: `FOREGROUND_SERVICE`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`.
- Notification permission is runtime-gated.
- Start the observer engine only from the visible launcher after microphone permission is granted.

API 34+:
- Required manifest permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`.
- The service declaration must use `foregroundServiceType="microphone"`.
- Microphone access is while-in-use; start the service only from a visible activity after microphone permission is granted.

Boot behavior:
- `BOOT_COMPLETED` may re-arm the notification only.
- Boot must not start the service or begin microphone input.
