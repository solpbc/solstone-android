#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Build a test APK out of the captured v2.1.0 pieces.

The manifest and the APK Signing Block are real bytes from the published
release (see fixtures/README.md); only the zip wrapper around them is built
here, because a 23 MB APK does not belong in a repository.
"""

from __future__ import annotations

import pathlib
import struct
import zipfile


FIXTURES = pathlib.Path(__file__).resolve().parent / "fixtures"
MANIFEST = FIXTURES / "v2.1.0-AndroidManifest.xml"
SIGNING_BLOCK = FIXTURES / "v2.1.0-apk-signing-block.bin"

# Ground truth, as aapt2 / apkanalyzer / apksigner reported it for the real
# v2.1.0 APK on 2026-09-13.
PACKAGE = "app.solstone.observer.phone"
VERSION_NAME = "2.1.0"
VERSION_CODE = 9
MIN_SDK = 26
TARGET_SDK = 36
SIGNER_CERT_SHA256 = "12dfe32f91f7182590092737917ed719337f8f9b1163245b3bdc3479a6be2660"
PERMISSIONS = [
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.CAMERA",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_CAMERA",
    "android.permission.FOREGROUND_SERVICE_LOCATION",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    "android.permission.INTERNET",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.POST_PROMOTED_NOTIFICATIONS",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.RECORD_AUDIO",
    "android.permission.WAKE_LOCK",
    "app.solstone.observer.phone.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
]


def make_apk(path, *, manifest=None, signing_block=True, extra=None):
    """Write a zip with the captured manifest and, optionally, the signing block.

    The block sits between the local file entries and the central directory,
    exactly where a real APK carries it, and the EOCD's central-directory
    offset is rewritten so the reader's EOCD walk lands on the block.
    """
    path = pathlib.Path(path)
    manifest_bytes = MANIFEST.read_bytes() if manifest is None else manifest

    unsigned = path.with_suffix(".unsigned")
    with zipfile.ZipFile(unsigned, "w", zipfile.ZIP_STORED) as archive:
        archive.writestr("AndroidManifest.xml", manifest_bytes)
        for name, payload in (extra or {}).items():
            archive.writestr(name, payload)
    data = bytearray(unsigned.read_bytes())
    unsigned.unlink()

    if not signing_block:
        path.write_bytes(bytes(data))
        return path

    eocd = data.rfind(b"\x50\x4b\x05\x06")
    if eocd < 0:
        raise AssertionError("test zip has no EOCD")
    central_directory_offset = struct.unpack_from("<I", data, eocd + 16)[0]

    block = SIGNING_BLOCK.read_bytes()
    spliced = bytearray()
    spliced += data[:central_directory_offset]
    spliced += block
    spliced += data[central_directory_offset:]
    struct.pack_into("<I", spliced, eocd + len(block) + 16, central_directory_offset + len(block))
    path.write_bytes(bytes(spliced))
    return path
