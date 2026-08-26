# solstone-android

Development guidelines for Android solstone observers, clients, and validation targets.

## Project Overview

`solstone-android` is the Android family repo for the maintained solstone phone app plus retained Android hardware research and validation evidence.

The repo is public open source. Keep all visible files clean of private operational context, internal paths, personal machine names, and unreleasable implementation history.

## Maintained Target and Parked Sources

- In this repository, `:apps:phone` is the sole maintained Android surface; its [build configuration](apps/phone/build.gradle.kts) declares `targetSdk` 36.
- `:apps:validation-rogbid`, `:apps:watch`, and `:apps:glasses` are parked source and hardware evidence. They are retained for historical/experimental reference, not routine-maintenance or release targets.

## Principles

- **Privacy is architecture.** Android observers write local, owner-controlled data for the owner's journal. Do not add analytics, tracking, telemetry SDKs, crash reporters, or third-party behavioral measurement.
- **One repo, separate artifacts.** Share protocol, identity, spool, queue, power, and hardware adapter code in modules; ship separate app artifacts for watch, phone, and accessories because their manifests, permissions, UI, and distribution rules differ.
- **Keep the observer core Android-light.** Segmenting, spool decisions, queue policy, link state, and protocol parsing should be host-testable where possible. Android framework APIs belong behind platform adapters.
- **Fail gap-honest.** Never render an observing/synced/linked state unless the underlying durable fact is true. Android background survival is best-effort and must surface gaps.
- **Preserve proven hardware evidence.** Retain documented Rogbid hardware findings as historical evidence without treating its parked hardware lane as a maintenance target.
- **No GitHub Actions release path.** Builds and releases are operator-driven from known local machines. Local `make` and Gradle automation are encouraged; hosted CI/CD and release credentials in GitHub are not.
- **Use the solstone app namespace.** Installable Android artifacts use `app.solstone.*`; `app.solstone.validation.rogbid` is a retained parked evidence package.

## Commands

```bash
make install
make test
make ci
make ci-device
make format
make clean
ANDROID_REMOTE_HOST=host.local make sync-android-host
ANDROID_REMOTE_HOST=host.local make android-host-ci
ANDROID_REMOTE_HOST=host.local make android-host-ci-device
ANDROID_REMOTE_HOST=host.local make android-host-assemble-validation-rogbid
make assemble-validation-rogbid
make validate-rogbid-adb
make validate-rogbid-media
make validate-rogbid-qr
PAIR_LINK='https://go.solstone.app/p#...' make validate-rogbid-pl
```

The Rogbid commands and `ROGBID_SERIAL=<serial>` are retained for historical/manual investigation only; they are not a routine validation or release path.

## CI gates

There are two maintained gates. **`make ci` is the fast gate.** It runs JVM unit tests, lint, root guards, and assembles, with **no instrumented tests**, and it must stay fast. **`make ci-device` is the slower phone device gate**: it runs the Gradle Managed Device (`pixel5api35`) instrumented tests for `platform/persistence-room`, `platform/pl-transport-conscrypt`, `formfactor/phone`, and the phone mock flavor, plus one narrowly-gated real-flavor phone test. Never fold the device gate into `make ci`. Run `make ci-device` directly on a machine with a working headless emulator, or `ANDROID_REMOTE_HOST=host.local make android-host-ci-device` to run it on a remote build host. `ci-device-experimental` is a parked manual command, not a maintained gate.

**Run `make ci-device` manually before declaring an on-device change shipped**: `core/spool`, `core/segment`, `core/queue`, Room schema or migrations, any `platform/*` adapter, or any `src/androidTest`. `dist-phone` does not run this gate. `make ci` does not run Android instrumented tests and cannot exercise host-JDK APIs against the Android runtime.

## Source Layout

```text
apps/validation-rogbid/   Parked Rogbid hardware-probe evidence
apps/observer-scaffold/   Shared phone/watch app scaffold (application, activity, container, capture setup; real/mock flavors)
apps/watch/               Parked watch observer source: retained historical/experimental evidence
apps/phone/               Phone observer app: spec + Application over the shared scaffold; beta distribution target
apps/glasses/             Parked smart-glasses/RV203 observer source: retained evidence
harness/                  Form-factor-agnostic observer UI logic (controller, state, seams, async-load)
core/                     Shared domain/protocol/observer modules
platform/                 Android framework adapters
formfactor/               Form-factor UI helpers - shared QR/pairing/harness UI (shared), glasses-specific views (glasses)
testing/                  Fake sensor streams and protocol fixtures
tools/rogbid/             Parked hardware-evidence scripts
docs/                     Architecture, device notes, and docs/observer-hardware-validation-runbook.md (on-device validation)
```

## Safety Rails

- Do not rename the validation app package or evidence files unless you also update every retained validation script; do not restart parked watch validation as routine maintenance.
- Do not commit `.env`, keystores, private keys, pairing links, captured media, local evidence artifacts, or device screenshots.
- Do not add owner-visible copy that uses surveillance verbs such as watch, monitor, track, or collect. Code identifiers may keep Android/framework terms where they are technical names.
- Do not make phone/watch/glasses share one app manifest. Share modules, not installable artifacts.
- Do not broaden permissions in production app modules without an owner-visible reason and a test that verifies honest capability state.

## Source Headers

New Java, Kotlin, shell, and Python source files should carry:

```text
SPDX-License-Identifier: AGPL-3.0-only
Copyright (c) 2026 sol pbc
```

Use the comment syntax native to the file type. Do not add headers to generated files, Gradle wrapper files, docs, or configuration.

## License

AGPL-3.0-only.
