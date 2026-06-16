# solstone-android

Development guidelines for Android solstone observers, clients, and validation targets.

## Project Overview

`solstone-android` is the Android family repo for solstone surfaces: a watch-focused observer, a full Android phone observer/importer/client, and future Android accessories such as smart glasses. The repo also carries hardware validation targets that prove what a device can do before that code graduates into production observer modules.

The repo is private during bootstrap but is on the open-source product path. Keep all visible files clean of private operational context, internal paths, personal machine names, and unreleasable implementation history.

## Current Targets

- `:apps:validation-rogbid` is the imported Rogbid Model X validation app. It exercises camera, microphone, GPS, QR preview/scan, PL QR linking, upload/retry, and battery trials on Android 9/API 28 hardware.
- Production `watch`, `phone`, and `glasses` apps are not implemented yet. Keep placeholders light until a real scope needs them.

## Principles

- **Privacy is architecture.** Android observers write local, owner-controlled data for the owner's journal. Do not add analytics, tracking, telemetry SDKs, crash reporters, or third-party behavioral measurement.
- **One repo, separate artifacts.** Share protocol, identity, spool, queue, power, and hardware adapter code in modules; ship separate app artifacts for watch, phone, and accessories because their manifests, permissions, UI, and distribution rules differ.
- **Keep the observer core Android-light.** Segmenting, spool decisions, queue policy, link state, and protocol parsing should be host-testable where possible. Android framework APIs belong behind platform adapters.
- **Fail gap-honest.** Never render an observing/synced/linked state unless the underlying durable fact is true. Android background survival is best-effort and must surface gaps.
- **Use proven hardware paths.** The Rogbid app validated legacy `Camera`, `AudioRecord`, GPS, ZXing QR, Conscrypt TLS 1.3, and WorkManager retry on the watch. Preserve those facts until production code replaces them with tested equivalents.
- **No GitHub Actions release path.** Builds and releases are operator-driven from known local machines. Local `make` and Gradle automation are encouraged; hosted CI/CD and release credentials in GitHub are not.

## Commands

```bash
make install
make test
make ci
make format
make clean
make assemble-validation-rogbid
make validate-rogbid-adb
make validate-rogbid-media
make validate-rogbid-qr
PAIR_LINK='https://link.solpbc.org/p#...' make validate-rogbid-pl
```

Use `ROGBID_SERIAL=<serial>` to target a different watch.

## Source Layout

```text
apps/validation-rogbid/   Current hardware validation target
apps/watch/               Future watch app
apps/phone/               Future phone app
apps/glasses/             Future Android glasses/accessory app
core/                     Shared domain/protocol/observer modules
platform/                 Android framework adapters
formfactor/               Form-factor-specific UI/policy helpers
testing/                  Fake sensor streams and protocol fixtures
tools/rogbid/             Hardware validation scripts
docs/                     Architecture and device notes
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

