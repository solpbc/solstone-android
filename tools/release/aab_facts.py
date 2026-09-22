#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Read release-binding facts straight out of an Android App Bundle.

The APK twin of this reader is `apk_facts.py`, and the two exist for the same
reason: the publish side runs where the Android SDK does not. An AAB keeps its
manifest as an aapt2 **protobuf** `XmlNode`, not the binary AXML an APK carries,
so nothing in `apk_facts.py` can read it — hence a second reader rather than a
second mode.

Emits JSON on stdout, with the same field names `apk_facts.py` uses so the two
can be compared field by field:

    {"package": ..., "version_name": ..., "version_code": ...,
     "min_sdk": ..., "target_sdk": ..., "permissions": [...],
     "modules": [...], "signature_entries": [...]}

`modules` is every module the bundle carries (`base` plus any feature module).
`signature_entries` names the JAR signature files; this reader deliberately does
**not** claim a signer identity — an AAB is JAR-signed, and proving which key
signed it belongs to the binding check (`verify-bundle-binding.py`), which has
the universal APK's certificate to compare against.
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile


ANDROID_NS = "http://schemas.android.com/apk/res/android"

BASE_MANIFEST = "base/manifest/AndroidManifest.xml"

# aapt2's Resources.proto, the three messages this reader walks.
XML_NODE_ELEMENT = 1
XML_ELEMENT_NAME = 3
XML_ELEMENT_ATTRIBUTE = 4
XML_ELEMENT_CHILD = 5
XML_ATTRIBUTE_NAMESPACE = 1
XML_ATTRIBUTE_NAME = 2
XML_ATTRIBUTE_VALUE = 3


class ProtoError(Exception):
    pass


def _varint(buf: bytes, at: int):
    result = 0
    shift = 0
    while True:
        if at >= len(buf):
            raise ProtoError("varint runs past the end of the message")
        byte = buf[at]
        at += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, at
        shift += 7
        if shift > 63:
            raise ProtoError("varint is longer than 64 bits")


def _fields(buf: bytes):
    """Walk a protobuf message, yielding (field number, wire type, value)."""
    at = 0
    while at < len(buf):
        key, at = _varint(buf, at)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, at = _varint(buf, at)
            yield number, wire, value
        elif wire == 2:
            length, at = _varint(buf, at)
            if at + length > len(buf):
                raise ProtoError("length-delimited field runs past the end of the message")
            yield number, wire, buf[at : at + length]
            at += length
        elif wire == 5:
            yield number, wire, buf[at : at + 4]
            at += 4
        elif wire == 1:
            yield number, wire, buf[at : at + 8]
            at += 8
        else:
            raise ProtoError(f"unsupported protobuf wire type {wire}")


def _first(buf: bytes, number: int, wire: int = 2):
    for found, found_wire, value in _fields(buf):
        if found == number and found_wire == wire:
            return value
    return None


def _all(buf: bytes, number: int, wire: int = 2):
    return [value for found, found_wire, value in _fields(buf) if found == number and found_wire == wire]


def _element(node: bytes):
    """The XmlElement inside an XmlNode, or None for a text node."""
    return _first(node, XML_NODE_ELEMENT)


def _name(element: bytes) -> str:
    raw = _first(element, XML_ELEMENT_NAME)
    return "" if raw is None else raw.decode("utf-8")


def _attributes(element: bytes) -> dict:
    """Attribute values keyed by `name` and by `{namespace}name`.

    aapt2 writes the value's string form into `XmlAttribute.value` for every
    attribute the manifest declares, including the numeric ones — an
    `android:versionCode="14"` arrives here as the string `"14"` — so the
    compiled primitive is never needed to read a manifest fact.
    """
    found = {}
    for attribute in _all(element, XML_ELEMENT_ATTRIBUTE):
        name = _first(attribute, XML_ATTRIBUTE_NAME)
        if name is None:
            continue
        name = name.decode("utf-8")
        value = _first(attribute, XML_ATTRIBUTE_VALUE)
        value = "" if value is None else value.decode("utf-8")
        namespace = _first(attribute, XML_ATTRIBUTE_NAMESPACE)
        namespace = "" if namespace is None else namespace.decode("utf-8")
        found.setdefault(name, value)
        found[f"{{{namespace}}}{name}"] = value
    return found


def _children(element: bytes):
    for node in _all(element, XML_ELEMENT_CHILD):
        child = _element(node)
        if child is not None:
            yield child


def _integer(text, field: str):
    if text in (None, ""):
        return None
    try:
        return int(text, 0)
    except ValueError as error:
        raise ProtoError(f"{field} is not an integer: {text!r}") from error


def parse_proto_manifest(buf: bytes) -> dict:
    root = _element(buf)
    if root is None:
        raise ProtoError("manifest protobuf has no root element")
    if _name(root) != "manifest":
        raise ProtoError(f"manifest root element is {_name(root)!r}, not 'manifest'")

    attributes = _attributes(root)
    facts = {
        "package": attributes.get("package"),
        "version_name": attributes.get(f"{{{ANDROID_NS}}}versionName"),
        "version_code": _integer(attributes.get(f"{{{ANDROID_NS}}}versionCode"), "versionCode"),
        "min_sdk": None,
        "target_sdk": None,
        "permissions": [],
    }

    for child in _children(root):
        tag = _name(child)
        child_attributes = _attributes(child)
        if tag == "uses-sdk":
            facts["min_sdk"] = _integer(
                child_attributes.get(f"{{{ANDROID_NS}}}minSdkVersion"), "minSdkVersion"
            )
            facts["target_sdk"] = _integer(
                child_attributes.get(f"{{{ANDROID_NS}}}targetSdkVersion"), "targetSdkVersion"
            )
        elif tag == "uses-permission":
            name = child_attributes.get(f"{{{ANDROID_NS}}}name")
            if name:
                facts["permissions"].append(name)

    facts["permissions"] = sorted(set(facts["permissions"]))
    return facts


def bundle_modules(names) -> list:
    """Every module directory the bundle carries, `base` first."""
    reserved = {"META-INF", "BUNDLE-METADATA"}
    modules = set()
    for name in names:
        head, _, rest = name.partition("/")
        if rest and head not in reserved:
            modules.add(head)
    return sorted(modules, key=lambda module: (module != "base", module))


def aab_facts(path: str) -> dict:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        try:
            manifest = archive.read(BASE_MANIFEST)
        except KeyError as error:
            raise ProtoError(f"bundle has no {BASE_MANIFEST}") from error
        facts = parse_proto_manifest(manifest)
        facts["modules"] = bundle_modules(names)
        facts["signature_entries"] = sorted(
            name
            for name in names
            if name.startswith("META-INF/") and name.upper().endswith((".RSA", ".DSA", ".EC", ".SF"))
        )
    return facts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aab")
    parser.add_argument(
        "--field",
        help="print one field as plain text instead of the whole JSON document",
    )
    arguments = parser.parse_args()

    try:
        facts = aab_facts(arguments.aab)
    except (ProtoError, zipfile.BadZipFile, OSError) as error:
        print(f"aab-facts: {error}", file=sys.stderr)
        return 1

    if arguments.field:
        if arguments.field not in facts:
            print(f"aab-facts: unknown field {arguments.field}", file=sys.stderr)
            return 2
        value = facts[arguments.field]
        if isinstance(value, list):
            for entry in value:
                print(entry)
        else:
            print("" if value is None else value)
        return 0

    json.dump(facts, sys.stdout, indent=2, sort_keys=True)
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
