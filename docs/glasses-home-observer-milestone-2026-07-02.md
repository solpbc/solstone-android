# RV203 glasses HOME observer milestone - 2026-07-02

This is the known-good hardware milestone for the RV203 glasses observer.

## Source

- App: `:apps:glasses`
- Package: `app.solstone.observer.glasses`
- Behavioral commit: `0cb6f53` (`feat(glasses): advertise MainActivity as HOME handler with regression guard`)
- Milestone tag: `glasses-home-v0.1.0`

## Validated Properties

On RV203 consumer firmware, the observer works as an Android HOME/capture-mode app:

- `MainActivity` advertises both normal launcher and HOME intent filters.
- When the app is selected as the default HOME, Android relaunches Solstone after reboot without a custom boot receiver path.
- The relaunched HOME Activity is top/resumed, which preserves the while-in-use camera and microphone permission shape.
- The observer foreground service runs with camera and microphone foreground-service types while capture is active.
- A worn hardware run sealed 44 five-minute segments, with audio in every segment and 203 camera stills total. One explicit camera capture gap was recorded.
- After the worn run, enabling Wi-Fi while docked allowed sync to drain to the paired journal. Fresh journal segments contained `audio.m4a`, `metadata.jsonl`, `stream.json`, and camera JPEG files.

## Boundaries

These are also part of the known state:

- Pure background capture is not viable on this firmware. Audio and camera access are restricted once the app is only backgrounded.
- A typed camera/microphone foreground service alone is not enough after HOME/backgrounding if no visible Activity remains top/resumed.
- Scheduled resurrection is not reliable here. WorkManager/JobScheduler, boot receivers, and alarm-style wakeups did not relaunch a killed observer process in hardware tests.
- LED control is not available to a normal sideloaded app. The device exposes LED sysfs and light-service surfaces, but ordinary app access is blocked by system ownership and signature/privileged permissions.
- The glasses hardware has no wearer-visible app screen in normal use, so this HOME role should be treated as an appliance/capture-mode shell, not as an owner-facing visual UI.

## Operational Notes

- For extended wear testing, set Solstone as default HOME before disconnecting the glasses.
- After a wear session, dock the glasses, enable Wi-Fi if needed, and allow WorkManager/opportunistic sync to run. If paired and network-connected, the observer should upload sealed segments automatically.
- Segment-level proof lives in the paired journal stream; the expected stream name is `rokid-rg-glasses.glasses`.

## Next Questions

- Root-cause any unexpected device reboot during extended wear.
- Explain and reduce late-run still-count drops or explicit camera capture gaps.
- Decide whether and how the HOME/appliance shell should become a user-supported glasses setup mode.
