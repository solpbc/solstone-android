# solstone-android

the [solstone](https://solstone.app) app for android, with retained android hardware research and validation evidence.

## Status

The phone app is the android release target. the new Wear OS app is maintained in `apps/wear` and is unreleased. the older `apps/watch`, Rokid/RV203 glasses, and Rogbid sources remain parked historical evidence. See [repository target status](AGENTS.md#maintained-target-and-parked-sources). The phone [build configuration](apps/phone/build.gradle.kts) declares `targetSdk` 36.

## Layout

```text
apps/
  validation-rogbid/   Parked android 9 watch validation evidence
  wear/                Unreleased Wear OS app
  watch/               Parked older watch app source
  phone/               Maintained phone app
  glasses/             Parked smart-glasses/RV203 app source
core/                  Shared app/link/domain modules as they graduate from the validation app
platform/              android framework adapters: camera, audio, location, foreground service, permissions, power
formfactor/            Watch/phone/glasses UI and policy helpers
testing/               Fake sensors, fixtures, and link harnesses
tools/rogbid/          Parked Rogbid hardware-evidence scripts
```

## Install

Prerequisites:

- JDK 17
- android SDK with API 36
- android build tools usable by Gradle
- `adb` on `PATH` for hardware validation

On the android build host, load the existing android environment first:

```bash
source ~/android-dev/env.sh
make install
```

From a development machine with SSH access to an android build host, the root Makefile can sync the tree and run the same gate remotely:

```bash
ANDROID_REMOTE_HOST=host.local make android-host-ci
```

## Build

```bash
./gradlew :apps:phone:assembleRealDebug
```

The broad CI aggregate retains compilation of shared and parked hardware sources as a safeguard; it does not designate them as maintained targets:

```bash
make ci
```

To build on a remote android host from this checkout:

```bash
ANDROID_REMOTE_HOST=host.local make android-host-ci
```

The maintained phone APK is produced at:

```text
apps/phone/build/outputs/apk/real/debug/phone-real-debug.apk
```

## Wear OS development

The Wear OS app requires API 30 or newer. Build it with:

```bash
./gradlew :apps:wear:assembleDebug
```

Use the same application ID and signing key on the watch and phone for Data Layer communication. the watch holds sealed material until the phone acknowledges durable receipt; only the phone connects to the journal. a tile, complication, notification, reconnect, or reboot cannot start the microphone. there is no watch store listing.

## Test

```bash
make test
make ci
```

Phone validation uses `make ci`, `make ci-device`, and [`make hitl-phone`](Makefile). The retained `tools/rogbid/*` scripts document historical hardware evidence and are not a routine validation path.

## Parked Hardware Evidence

The following Rogbid commands remain available only for manual inspection of the historical spike:

```bash
make validate-rogbid-adb
make validate-rogbid-media
make validate-rogbid-qr
PAIR_LINK='https://go.solstone.app/p#...' make validate-rogbid-pl
```

The validation app package is `app.solstone.validation.rogbid`, a retained historical artifact. Installable solstone android artifacts use the `app.solstone.*` namespace.

## Hardware Milestones

- [RV203 glasses HOME app milestone](docs/glasses-home-observer-milestone-2026-07-02.md)

## License

AGPL-3.0-only. See [LICENSE](LICENSE).
