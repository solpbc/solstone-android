# release-test fixtures

Both files are **captured from the real published `v2.1.0` APK**
(`phone-real-release.apk`, sha256
`e1a8dc023a85c099e051fdee1c2cf0d291fb75c540191f4c7d96a3c985e06fcd`), not
hand-written. A parser of tool output is tested against real output or it is
tested against its author's idea of the format.

| file | what it is | captured how |
|---|---|---|
| `v2.1.0-AndroidManifest.xml` | the merged binary AXML manifest | the `AndroidManifest.xml` zip entry, verbatim |
| `v2.1.0-apk-signing-block.bin` | the APK Signing Block | the bytes from the block's leading size field through the `APK Sig Block 42` magic, verbatim |

`make_fixture_apk()` in `test_apk_facts.py` splices them into a minimal zip so
the reader sees a real manifest and a real v2 signing block. Ground truth for
every assertion is what `aapt2 dump badging`, `apkanalyzer manifest
permissions` and `apksigner verify --print-certs` reported for that same APK on
2026-09-13.

The fixture APK is deliberately **not** a valid signature over its own bytes —
it is a real signing block around different content. That is fine and is the
point: `apk_facts.py` reads signer identity out of the block, it does not
verify the signature. `apksigner verify` on the build box remains the
authoritative signature check.
