# solstone-android

Development guidelines for Android solstone observers, clients, and validation targets.

## Project Overview

`solstone-android` is the Android family repo for solstone surfaces: a watch-focused observer, a full Android phone observer/importer/client, and Android accessories such as smart glasses. The repo also carries hardware validation targets that prove what a device can do before that code graduates into production observer modules.

The repo is public open source. Keep all visible files clean of private operational context, internal paths, personal machine names, and unreleasable implementation history.

## Current Targets

- `:apps:validation-rogbid` is the imported Rogbid Model X validation app. It exercises camera, microphone, GPS, QR preview/scan, PL QR linking, upload/retry, and battery trials on Android 9/API 28 hardware.
- `:apps:watch`, `:apps:phone`, and `:apps:glasses` are installable observer apps over the shared harness. Phone has the beta release channel wired today; watch and glasses are hardware-validation surfaces until their release channels are explicitly added.

## Principles

- **Privacy is architecture.** Android observers write local, owner-controlled data for the owner's journal. Do not add analytics, tracking, telemetry SDKs, crash reporters, or third-party behavioral measurement.
- **One repo, separate artifacts.** Share protocol, identity, spool, queue, power, and hardware adapter code in modules; ship separate app artifacts for watch, phone, and accessories because their manifests, permissions, UI, and distribution rules differ.
- **Keep the observer core Android-light.** Segmenting, spool decisions, queue policy, link state, and protocol parsing should be host-testable where possible. Android framework APIs belong behind platform adapters.
- **Fail gap-honest.** Never render an observing/synced/linked state unless the underlying durable fact is true. Android background survival is best-effort and must surface gaps.
- **Use proven hardware paths.** The Rogbid app validated legacy `Camera`, `AudioRecord`, GPS, ZXing QR, Conscrypt TLS 1.3, and WorkManager retry on the watch. Preserve those facts until production code replaces them with tested equivalents.
- **No GitHub Actions release path.** Builds and releases are operator-driven from known local machines. Local `make` and Gradle automation are encouraged; hosted CI/CD and release credentials in GitHub are not.
- **Use the solstone app namespace.** Installable Android artifacts use `app.solstone.*`; the current validation target is `app.solstone.validation.rogbid`.

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
PAIR_LINK='https://link.solpbc.org/p#...' make validate-rogbid-pl
```

Use `ROGBID_SERIAL=<serial>` to target a different watch.

## CI gates

There are two gates. **`make ci` is the fast gate** — JVM unit tests, lint, and assembles, with **no instrumented tests** — and it must stay fast (it is the inner-loop gate). **`make ci-device` is the slower device gate**: it runs the Gradle Managed Device (`pixel5api35`) instrumented tests for the three modules that carry real `androidTest` coverage (`platform/persistence-room`, `apps/watch`, `apps/phone`), on a headless emulator. Never fold the device gate into `make ci`. Run `make ci-device` directly on a machine with a working headless emulator, or `ANDROID_REMOTE_HOST=host.local make android-host-ci-device` to run it on a remote build host.

**`make ci-device` green is a required ship-stage acceptance criterion** for any lode that touches an on-device surface: `core/spool`, `core/segment`, `core/queue`, Room schema or migrations, any `platform/*` adapter, or any `src/androidTest`. `make ci` structurally cannot catch two defect classes that have already shipped green through it — host-JDK-API-absent-on-Android (e.g. a `core/*` module calling a JDK method missing from the Android runtime) and instrumented-tests-authored-but-never-run. If a lode touches those surfaces, run the device gate green before declaring it shipped.

## Source Layout

```text
apps/validation-rogbid/   Imported Rogbid hardware-probe target
apps/watch/               Watch observer app — installable functional-testing UI over the shared harness
apps/phone/               Phone observer harness app — shares the watch harness; beta distribution target
apps/glasses/             Smart-glasses observer app — RV203 hardware-validation surface
harness/                  Form-factor-agnostic observer UI logic (controller, state, seams, async-load)
core/                     Shared domain/protocol/observer modules
platform/                 Android framework adapters
formfactor/               Form-factor-specific UI/policy helpers (watch, phone)
testing/                  Fake sensor streams and protocol fixtures
tools/rogbid/             Hardware validation scripts
docs/                     Architecture, device notes, and docs/observer-hardware-validation-runbook.md (on-device validation)
```

## Safety Rails

- Do not rename the validation app package or evidence files unless you also update every validation script and re-run the watch checks.
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
