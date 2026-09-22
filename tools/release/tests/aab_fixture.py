#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Build a test bundle and its universal APK out of captured 2.1.5 pieces.

The two manifests and the two signature structures are real bytes from a
`bundleRealRelease` + `packageRealReleaseUniversalApk` build (see
fixtures/README.md); only the zip wrapper and the payload entries are built
here, because an 18 MB bundle does not belong in a repository.

The payload contents are deliberately arbitrary. `verify-bundle-binding.py`
compares payload entries **byte for byte** and never parses them, so a real dex
would test nothing a four-byte string does not — while the manifests and the
signatures **are** parsed, and those are real.
"""

from __future__ import annotations

import pathlib
import zipfile

import apk_fixture


FIXTURES = pathlib.Path(__file__).resolve().parent / "fixtures"
AAB_MANIFEST = FIXTURES / "v2.1.5-aab-base-AndroidManifest.pb"
AAB_SIGNATURE = FIXTURES / "v2.1.5-aab-UPLOAD.RSA"
UNIVERSAL_MANIFEST = FIXTURES / "v2.1.5-universal-AndroidManifest.xml"
UNIVERSAL_SIGNING_BLOCK = FIXTURES / "v2.1.5-universal-apk-signing-block.bin"

# Ground truth, as aapt2 and apksigner reported it for the real 2.1.5 bundle and
# universal APK on 2026-09-21.
PACKAGE = "app.solstone.observer.phone"
VERSION_NAME = "2.1.5"
VERSION_CODE = 14
MIN_SDK = 26
TARGET_SDK = 36
SIGNER_CERT_SHA256 = "12dfe32f91f7182590092737917ed719337f8f9b1163245b3bdc3479a6be2660"
PERMISSIONS = list(apk_fixture.PERMISSIONS)

# The executable payload, keyed by its path inside the bundle's base module.
PAYLOAD = {
    "dex/classes.dex": b"dex-one",
    "dex/classes2.dex": b"dex-two",
    "lib/arm64-v8a/libsolstone.so": b"native-arm64",
    "lib/x86_64/libsolstone.so": b"native-x86_64",
    "assets/schema/journal.json": b"an asset",
    "root/kotlin/kotlin.kotlin_builtins": b"a packaged java resource",
}

# Bundle metadata bundletool moves into the APK, where ART reads it.
PROFILE = {
    "baseline.prof": b"a baseline profile",
    "baseline.profm": b"its metadata",
}

PROFILE_PREFIX = "BUNDLE-METADATA/com.android.tools.build.profiles/"


def universal_entry(bundle_relative: str) -> str:
    """Where bundletool puts a base-module entry in the universal APK."""
    for prefix, replacement in (("dex/", ""), ("lib/", "lib/"), ("assets/", "assets/"), ("root/", "")):
        if bundle_relative.startswith(prefix):
            return replacement + bundle_relative[len(prefix) :]
    raise AssertionError(f"no universal-APK mapping for {bundle_relative}")


def make_aab(
    path,
    *,
    manifest=None,
    payload=None,
    profile=None,
    signature=None,
    modules=("base",),
):
    """Write a bundle: one module per name in `modules`, payload in the first."""
    path = pathlib.Path(path)
    payload = PAYLOAD if payload is None else payload
    profile = PROFILE if profile is None else profile
    manifest_bytes = AAB_MANIFEST.read_bytes() if manifest is None else manifest
    signature_bytes = AAB_SIGNATURE.read_bytes() if signature is None else signature

    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as archive:
        archive.writestr("BundleConfig.pb", b"")
        for module in modules:
            archive.writestr(f"{module}/manifest/AndroidManifest.xml", manifest_bytes)
        for name, blob in payload.items():
            archive.writestr(f"{modules[0]}/{name}", blob)
        for name, blob in profile.items():
            archive.writestr(PROFILE_PREFIX + name, blob)
        archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
        archive.writestr("META-INF/UPLOAD.SF", b"Signature-Version: 1.0\n")
        if signature_bytes is not None:
            archive.writestr("META-INF/UPLOAD.RSA", signature_bytes)
    return path


def make_universal_apk(
    path,
    *,
    manifest=None,
    payload=None,
    profile=None,
    signing_block=True,
    extra=None,
):
    """Write the universal APK bundletool would derive from `make_aab`."""
    payload = PAYLOAD if payload is None else payload
    profile = PROFILE if profile is None else profile
    entries = {universal_entry(name): blob for name, blob in payload.items()}
    entries.update({f"assets/dexopt/{name}": blob for name, blob in profile.items()})
    # Re-encoded by bundletool, so never byte-comparable with the bundle's.
    entries["resources.arsc"] = b"a rebuilt resource table"
    entries.update(extra or {})
    return apk_fixture.make_apk(
        path,
        manifest=UNIVERSAL_MANIFEST.read_bytes() if manifest is None else manifest,
        signing_block=signing_block,
        block=UNIVERSAL_SIGNING_BLOCK.read_bytes(),
        extra=entries,
    )
