# solstone-android

Android observers and clients for [solstone](https://solstone.app): watch, phone, and future Android accessory surfaces that help the owner's journal receive local, owner-controlled context.

## Status

Public bootstrap. The only runnable app target today is the Rogbid Model X validation app imported from the hardware spike. Production observer modules will be added after the Android observer scope is approved.

## Layout

```text
apps/
  validation-rogbid/   Android 9 watch validation app for camera, mic, GPS, QR, PL linking, upload, and battery trials
  watch/               Future watch observer app
  phone/               Future phone observer app
  glasses/             Future glasses/accessory app
core/                  Shared observer/link/domain modules as they graduate from the validation app
platform/              Android framework adapters: camera, audio, location, foreground service, permissions, power
formfactor/            Watch/phone/glasses UI and policy helpers
testing/               Fake sensors, fixtures, and link harnesses
tools/rogbid/          ADB validation scripts for the Rogbid Model X
```

## Install

Prerequisites:

- JDK 17
- Android SDK with API 35
- Android build tools usable by Gradle
- `adb` on `PATH` for hardware validation

On the Android build host, load the existing Android environment first:

```bash
source ~/android-dev/env.sh
make install
```

From a development machine with SSH access to an Android build host, the root Makefile can sync the tree and run the same gate remotely:

```bash
ANDROID_REMOTE_HOST=host.local make android-host-ci
```

## Build

```bash
make assemble-validation-rogbid
```

To build on a remote Android host from this checkout:

```bash
ANDROID_REMOTE_HOST=host.local make android-host-assemble-validation-rogbid
```

The APK is produced at:

```text
apps/validation-rogbid/build/outputs/apk/debug/validation-rogbid-debug.apk
```

## Test

```bash
make test
make ci
```

The validation target is intentionally hardware-heavy, so most confidence still comes from the `tools/rogbid/*` scripts against the physical watch.

## Hardware Validation

Default serial is the Rogbid Model X used during the spike:

```bash
make validate-rogbid-adb
make validate-rogbid-media
make validate-rogbid-qr
PAIR_LINK='https://link.solpbc.org/p#...' make validate-rogbid-pl
```

The validation app package is `app.solstone.validation.rogbid`. Future installable solstone Android artifacts should use the same `app.solstone.*` namespace.

## License

AGPL-3.0-only. See [LICENSE](LICENSE).
