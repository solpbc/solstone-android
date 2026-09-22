#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Read release-binding facts straight out of an APK, with no Android SDK.

The release origin publisher runs where `wrangler` is authenticated, which is
not where the Android SDK lives. `aapt2`/`apksigner` would tie the publish gate
to the build box; this reads the two structures the gate actually needs —
the binary `AndroidManifest.xml` and the APK Signing Block — out of the zip
itself.

Emits JSON on stdout:

    {"package": ..., "version_name": ..., "version_code": ...,
     "min_sdk": ..., "target_sdk": ...,
     "signer_cert_sha256": [...], "permissions": [...]}

`permissions` is the MERGED set the artifact actually declares, which is the
only set an owner is ever asked about — library manifests contribute to it and
the app's own source manifest does not list them.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import zipfile


# --- binary XML (AXML) ---------------------------------------------------

RES_STRING_POOL_TYPE = 0x0001
RES_XML_START_ELEMENT_TYPE = 0x0102

UTF8_FLAG = 1 << 8

TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11

ANDROID_NS = "http://schemas.android.com/apk/res/android"


class AxmlError(Exception):
    pass


def _u16(buf: bytes, off: int) -> int:
    return struct.unpack_from("<H", buf, off)[0]


def _u32(buf: bytes, off: int) -> int:
    return struct.unpack_from("<I", buf, off)[0]


def _decode_length(buf: bytes, off: int, utf8: bool):
    """Return (length, bytes consumed). High bit marks a two-unit length."""
    if utf8:
        first = buf[off]
        if first & 0x80:
            return ((first & 0x7F) << 8) | buf[off + 1], 2
        return first, 1
    first = _u16(buf, off)
    if first & 0x8000:
        return ((first & 0x7FFF) << 16) | _u16(buf, off + 2), 4
    return first, 2


def _parse_string_pool(buf: bytes, start: int) -> list:
    chunk_size = _u32(buf, start + 4)
    string_count = _u32(buf, start + 8)
    flags = _u32(buf, start + 16)
    strings_start = _u32(buf, start + 20)
    utf8 = bool(flags & UTF8_FLAG)

    offsets_at = start + 28
    strings_at = start + strings_start
    end = start + chunk_size

    out = []
    for index in range(string_count):
        offset = _u32(buf, offsets_at + index * 4)
        at = strings_at + offset
        if at >= end:
            raise AxmlError("string pool entry points past its chunk")
        if utf8:
            # UTF-8 pools carry the char length first, then the byte length.
            _, consumed = _decode_length(buf, at, True)
            at += consumed
            byte_length, consumed = _decode_length(buf, at, True)
            at += consumed
            out.append(buf[at : at + byte_length].decode("utf-8", "replace"))
        else:
            char_length, consumed = _decode_length(buf, at, False)
            at += consumed
            out.append(buf[at : at + char_length * 2].decode("utf-16-le", "replace"))
    return out


def parse_manifest(buf: bytes) -> dict:
    """Pull <manifest> attributes, uses-sdk and every uses-permission name."""
    if len(buf) < 8 or _u16(buf, 0) != 0x0003:
        raise AxmlError("not a binary AndroidManifest.xml")

    header_size = _u16(buf, 2)
    total = _u32(buf, 4)
    if total > len(buf):
        raise AxmlError("AXML chunk size exceeds the file")

    pool = None
    elements = []
    off = header_size
    while off + 8 <= total:
        chunk_type = _u16(buf, off)
        chunk_size = _u32(buf, off + 4)
        if chunk_size < 8:
            raise AxmlError("AXML chunk with an impossible size")
        if chunk_type == RES_STRING_POOL_TYPE:
            pool = _parse_string_pool(buf, off)
        elif chunk_type == RES_XML_START_ELEMENT_TYPE:
            if pool is None:
                raise AxmlError("element chunk before the string pool")
            elements.append(_parse_start_element(buf, off, pool))
        off += chunk_size

    if pool is None:
        raise AxmlError("AXML had no string pool")

    facts = {
        "package": None,
        "version_name": None,
        "version_code": None,
        "min_sdk": None,
        "target_sdk": None,
        "permissions": [],
    }
    for name, attributes in elements:
        if name == "manifest":
            facts["package"] = attributes.get((None, "package"))
            facts["version_name"] = attributes.get((ANDROID_NS, "versionName"))
            facts["version_code"] = attributes.get((ANDROID_NS, "versionCode"))
        elif name == "uses-sdk":
            facts["min_sdk"] = attributes.get((ANDROID_NS, "minSdkVersion"))
            facts["target_sdk"] = attributes.get((ANDROID_NS, "targetSdkVersion"))
        elif name == "uses-permission":
            value = attributes.get((ANDROID_NS, "name"))
            if value is not None:
                facts["permissions"].append(str(value))
    return facts


def _parse_start_element(buf: bytes, off: int, pool: list):
    header_size = _u16(buf, off + 2)
    body = off + header_size
    name_ref = _u32(buf, body + 4)
    attribute_start = _u16(buf, body + 8)
    attribute_size = _u16(buf, body + 10)
    attribute_count = _u16(buf, body + 12)

    def resolve(ref):
        if ref == 0xFFFFFFFF or ref >= len(pool):
            return None
        return pool[ref]

    attributes = {}
    base = body + attribute_start
    for index in range(attribute_count):
        at = base + index * attribute_size
        ns = resolve(_u32(buf, at))
        attribute_name = resolve(_u32(buf, at + 4))
        raw_value = _u32(buf, at + 8)
        data_type = buf[at + 15]
        data = _u32(buf, at + 16)
        if data_type == TYPE_STRING:
            value = resolve(data)
            if value is None:
                value = resolve(raw_value)
        elif data_type in (TYPE_INT_DEC, TYPE_INT_HEX):
            value = data
        else:
            value = data
        attributes[(ns, attribute_name)] = value
    return resolve(name_ref), attributes


# --- APK Signing Block ---------------------------------------------------

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
APK_SIGNATURE_SCHEME_V2_ID = 0x7109871A
APK_SIGNATURE_SCHEME_V3_ID = 0xF05368C0


class SigningBlockError(Exception):
    pass


def _find_eocd(data: bytes) -> int:
    # The comment field is at most 0xFFFF, so the EOCD starts no earlier than
    # 22 + 0xFFFF bytes from the end. Scan backwards for the real one.
    minimum = max(0, len(data) - (22 + 0xFFFF))
    for at in range(len(data) - 22, minimum - 1, -1):
        if data[at : at + 4] == b"\x50\x4b\x05\x06":
            return at
    raise SigningBlockError("no end-of-central-directory record")


def _length_prefixed(buf: bytes):
    """Walk a sequence of u32-length-prefixed elements."""
    at = 0
    while at + 4 <= len(buf):
        length = struct.unpack_from("<I", buf, at)[0]
        at += 4
        if at + length > len(buf):
            raise SigningBlockError("length-prefixed element runs past its parent")
        yield buf[at : at + length]
        at += length


def signer_certificates(path: str) -> list:
    """Every signer's leading certificate, as DER bytes, v3 preferred over v2."""
    with open(path, "rb") as handle:
        data = handle.read()

    eocd = _find_eocd(data)
    central_directory_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    if central_directory_offset < 24:
        raise SigningBlockError("central directory offset leaves no signing block")

    footer = central_directory_offset - 24
    if data[footer + 8 : footer + 24] != APK_SIG_BLOCK_MAGIC:
        raise SigningBlockError("apk is not signed with an APK Signing Block (v1-only or unsigned)")
    block_size = struct.unpack_from("<Q", data, footer)[0]
    block_start = central_directory_offset - block_size - 8
    if block_start < 0:
        raise SigningBlockError("signing block size is impossible")
    if struct.unpack_from("<Q", data, block_start)[0] != block_size:
        raise SigningBlockError("signing block size headers disagree")

    pairs = {}
    at = block_start + 8
    end = footer
    while at + 12 <= end:
        pair_length = struct.unpack_from("<Q", data, at)[0]
        if pair_length < 4 or at + 8 + pair_length > end + 8:
            break
        pair_id = struct.unpack_from("<I", data, at + 8)[0]
        pairs[pair_id] = data[at + 12 : at + 8 + pair_length]
        at += 8 + pair_length

    for scheme_id in (APK_SIGNATURE_SCHEME_V3_ID, APK_SIGNATURE_SCHEME_V2_ID):
        value = pairs.get(scheme_id)
        if value is None:
            continue
        found = []
        for signers in _length_prefixed(value):
            for signer in _length_prefixed(signers):
                signed_data = next(_length_prefixed(signer), None)
                if signed_data is None:
                    continue
                # ⛔ Never walk signed data to its end. v2 signed data is three
                # length-prefixed sequences, but v3 puts two RAW u32s (minSdk,
                # maxSdk) between the certificates and the attributes — a full
                # walk reads minSdk as a length and overruns the message. Only
                # the first two elements are ever needed, and both schemes agree
                # on them: digests, then certificates. Reading them lazily stops
                # before the raw fields exist. A v3-signed APK is not exotic:
                # bundletool signs the universal APK v2+v3 while AGP's
                # assembleRealRelease signs v2 only, so the bundle rail hits this
                # and the direct APK rail never did.
                elements = _length_prefixed(signed_data)
                if next(elements, None) is None:
                    continue
                certificates = next(elements, None)
                if certificates is None:
                    continue
                certificate = next(_length_prefixed(certificates), None)
                if certificate is None:
                    continue
                found.append(certificate)
        if found:
            return found
    raise SigningBlockError("no v2 or v3 signature scheme block found")


def signer_cert_sha256(path: str) -> list:
    """SHA-256 of every signer's leading certificate, v3 preferred over v2.

    apksigner prints exactly this digest as "certificate SHA-256 digest", so a
    pin captured from apksigner is directly comparable.
    """
    return [hashlib.sha256(certificate).hexdigest() for certificate in signer_certificates(path)]


# --- entry point ---------------------------------------------------------


def apk_facts(path: str) -> dict:
    with zipfile.ZipFile(path) as archive:
        try:
            manifest = archive.read("AndroidManifest.xml")
        except KeyError as error:
            raise AxmlError("apk has no AndroidManifest.xml") from error
    facts = parse_manifest(manifest)
    facts["signer_cert_sha256"] = signer_cert_sha256(path)
    facts["permissions"] = sorted(facts["permissions"])
    return facts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    parser.add_argument(
        "--field",
        help="print one field as plain text instead of the whole JSON document",
    )
    arguments = parser.parse_args()

    try:
        facts = apk_facts(arguments.apk)
    except (AxmlError, SigningBlockError, OSError, zipfile.BadZipFile) as error:
        print(f"apk-facts: {error}", file=sys.stderr)
        return 1

    if arguments.field:
        if arguments.field not in facts:
            print(f"apk-facts: unknown field {arguments.field}", file=sys.stderr)
            return 2
        value = facts[arguments.field]
        if isinstance(value, list):
            for entry in value:
                print(entry)
        else:
            print("" if value is None else value)
        return 0

    json.dump(facts, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
