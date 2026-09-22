# release-test fixtures

Every file here is **captured from a real artifact**, not hand-written. A parser
of tool output is tested against real output or it is tested against its
author's idea of the format.

## the direct channel — published `v2.1.0`

From the published `v2.1.0` APK (`phone-real-release.apk`, sha256
`e1a8dc023a85c099e051fdee1c2cf0d291fb75c540191f4c7d96a3c985e06fcd`).

| file | what it is | captured how |
|---|---|---|
| `v2.1.0-AndroidManifest.xml` | the merged binary AXML manifest | the `AndroidManifest.xml` zip entry, verbatim |
| `v2.1.0-apk-signing-block.bin` | the APK Signing Block, **v2 only** | the bytes from the block's leading size field through the `APK Sig Block 42` magic, verbatim |

`make_apk()` in `apk_fixture.py` splices them into a minimal zip so the reader
sees a real manifest and a real signing block. Ground truth for every assertion
is what `aapt2 dump badging`, `apkanalyzer manifest permissions` and `apksigner
verify --print-certs` reported for that same APK on 2026-09-13.

## the bundle rail — `2.1.5` at `ca3fdb6`

From one `bundleRealRelease` + `packageRealReleaseUniversalApk` build of
`origin/main` `ca3fdb6` on 2026-09-21 — the bundle (sha256
`942d92fc415abf5ab987c49d66e504fd9b414dd6ee44c887595604f04ee8e344`) and the
universal APK derived from it (sha256
`38d48a923728e8d3ae4d777d477e0b6999cf5393f97a7455a0bf1a9111617f76`). Not a
published release: nothing has been cut for a store.

| file | what it is | captured how |
|---|---|---|
| `v2.1.5-aab-base-AndroidManifest.pb` | the base module's manifest, **aapt2 protobuf**, not AXML | the `base/manifest/AndroidManifest.xml` bundle entry, verbatim |
| `v2.1.5-aab-UPLOAD.RSA` | the bundle's JAR signature block | the `META-INF/UPLOAD.RSA` bundle entry, verbatim |
| `v2.1.5-universal-AndroidManifest.xml` | the universal APK's binary AXML manifest | the `AndroidManifest.xml` zip entry, verbatim |
| `v2.1.5-universal-apk-signing-block.bin` | the universal APK's APK Signing Block, **v2 + v3** | same capture as the `v2.1.0` block |

Ground truth is what `aapt2 dump badging`, `aapt2 dump permissions` and
`apksigner verify --print-certs` reported for that universal APK on 2026-09-21:
`app.solstone.observer.phone`, versionName `2.1.5`, versionCode `14`, minSdk
`26`, targetSdk `36`, 14 merged permissions, one signer with certificate SHA-256
`12dfe32f…2660`.

🔴 **Keep the two signing blocks.** AGP's `assembleRealRelease` signs v2 only;
bundletool signs the universal APK v2 **and** v3, and v3 signed data carries two
raw `u32`s that a reader walking the whole message reads as lengths. That
difference is the whole reason `apk_facts.py` could read every direct-channel
APK and not one bundle-rail one.

## what these fixtures are not

The fixture artifacts are deliberately **not** valid signatures over their own
bytes — they are real signature structures around different content. That is
fine and is the point: the readers read signer identity out of those structures,
they do not verify a signature. `apksigner verify` on the build box remains the
authoritative signature check.

Payload entries in `aab_fixture.py` (dex, native libraries, assets) are
synthetic on purpose. `verify-bundle-binding.py` compares them **byte for byte**
and never parses them, so a real dex would test nothing a four-byte string does
not — while the manifests and the signatures **are** parsed, and those are real.
