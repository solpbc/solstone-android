#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Prove a universal APK is the bundle that gets uploaded, not a second build.

Play takes an Android App Bundle, and nobody can install one. The only way to
run the real-hardware gate against what we upload is to gate an APK **derived
from** that bundle — and the failure this script exists to make impossible is
gating a separately-assembled APK instead, so the bytes a human proved usable
and the bytes Play receives are two different builds. The Android release
runbook already carries the same rule for the direct channel ("the `hitl-phone`
target rebuilds the APK, so depending `github-release` on it would replace the
frozen bytes"); this is that rule for the bundle.

The binding is **checked, not trusted.** AGP's `packageRealReleaseUniversalApk`
consumes the intermediary bundle rather than the signed `.aab` file, so the two
artifacts being one build is a property of the build graph, not of the bytes.
What this script does instead is compare the bytes: every executable entry the
bundle carries — dex, native libraries, assets, packaged java resources — is
copied into the universal APK verbatim, and that is directly checkable. The
manifests are not comparable byte for byte (an AAB holds protobuf XML, an APK
binary AXML, and bundletool re-encodes the resource table), so those are
compared fact by fact instead.

Exits 0 and prints a JSON receipt when the binding holds; exits 1 naming every
disagreement when it does not.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import aab_facts  # noqa: E402
import apk_facts  # noqa: E402


PINNED_KEY = pathlib.Path(__file__).resolve().parent / "release-signing-key.sha256"

# Bundle payload directories and where bundletool puts them in a universal APK.
PAYLOAD_PREFIXES = (
    ("base/dex/", ""),
    ("base/lib/", "lib/"),
    ("base/assets/", "assets/"),
    ("base/root/", ""),
    # The baseline profile is bundle metadata rather than module content, and
    # bundletool moves it into the APK where ART reads it. It is carried
    # verbatim, so it binds like any other payload entry.
    ("BUNDLE-METADATA/com.android.tools.build.profiles/", "assets/dexopt/"),
)

# The executable surface, where an entry the bundle does not carry is a finding.
COMPARED_FACTS = ("package", "version_code", "version_name", "min_sdk", "target_sdk", "permissions")


def _digest(archive: zipfile.ZipFile, name: str) -> str:
    return hashlib.sha256(archive.read(name)).hexdigest()


def _file_digest(path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _pinned_key(path) -> str:
    for line in pathlib.Path(path).read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            return line.lower()
    raise ValueError(f"no pinned certificate digest in {path}")


def _payload_map(bundle_names):
    """AAB entry -> universal-APK entry, for every executable entry."""
    mapped = {}
    for name in bundle_names:
        for prefix, replacement in PAYLOAD_PREFIXES:
            if name.startswith(prefix) and not name.endswith("/"):
                mapped[name] = replacement + name[len(prefix) :]
                break
    return mapped


def _executable_entries(names):
    return {
        name
        for name in names
        if not name.endswith("/")
        and (
            (name.startswith("classes") and name.endswith(".dex"))
            or name.startswith("lib/")
            or name.startswith("assets/")
        )
    }


def verify(aab_path, apk_path, pinned_key_path=PINNED_KEY) -> tuple:
    """Return (findings, receipt). An empty findings list is a passing binding."""
    findings = []

    bundle = aab_facts.aab_facts(str(aab_path))
    artifact = apk_facts.apk_facts(str(apk_path))

    if bundle["modules"] != ["base"]:
        findings.append(
            "bundle carries modules "
            + ", ".join(bundle["modules"])
            + " — this check maps only a single `base` module into the universal APK. "
            "A feature module needs its own mapping before the binding can be claimed."
        )

    for field in COMPARED_FACTS:
        if bundle.get(field) != artifact.get(field):
            findings.append(
                f"{field} disagrees: bundle {bundle.get(field)!r} != universal APK {artifact.get(field)!r}"
            )

    with zipfile.ZipFile(aab_path) as bundle_zip, zipfile.ZipFile(apk_path) as apk_zip:
        apk_names = set(apk_zip.namelist())
        mapping = _payload_map(bundle_zip.namelist())

        missing = 0
        differing = 0
        for bundle_entry, apk_entry in sorted(mapping.items()):
            if apk_entry not in apk_names:
                missing += 1
                if missing <= 10:
                    findings.append(f"universal APK is missing {apk_entry} (bundle {bundle_entry})")
                continue
            if _digest(bundle_zip, bundle_entry) != _digest(apk_zip, apk_entry):
                differing += 1
                if differing <= 10:
                    findings.append(
                        f"{apk_entry} differs from the bundle's {bundle_entry} — "
                        "the universal APK is not this bundle"
                    )
        if missing > 10:
            findings.append(f"... and {missing - 10} more missing entries")
        if differing > 10:
            findings.append(f"... and {differing - 10} more differing entries")

        unaccounted = sorted(_executable_entries(apk_names) - set(mapping.values()))
        for entry in unaccounted[:10]:
            findings.append(f"universal APK carries {entry}, which the bundle does not")
        if len(unaccounted) > 10:
            findings.append(f"... and {len(unaccounted) - 10} more unaccounted executable entries")

        certificates = apk_facts.signer_certificates(str(apk_path))
        if len(certificates) != 1:
            findings.append(f"universal APK has {len(certificates)} signers; expected exactly 1")
        pinned = _pinned_key(pinned_key_path)
        digests = [hashlib.sha256(certificate).hexdigest() for certificate in certificates]
        if digests != [pinned]:
            findings.append(
                f"universal APK signer certificate {digests} is not the pinned release key {pinned}"
            )

        signature_blocks = [
            name
            for name in bundle_zip.namelist()
            if name.startswith("META-INF/") and name.upper().endswith((".RSA", ".DSA", ".EC"))
        ]
        if not signature_blocks:
            findings.append("bundle carries no JAR signature block — it is unsigned")
        for block in signature_blocks:
            payload = bundle_zip.read(block)
            if not any(certificate in payload for certificate in certificates):
                findings.append(
                    f"bundle signature {block} does not carry the universal APK's certificate — "
                    "the two artifacts were signed by different keys"
                )

    receipt = {
        "aab": str(aab_path),
        "aab_sha256": _file_digest(aab_path),
        "universal_apk": str(apk_path),
        "universal_apk_sha256": _file_digest(apk_path),
        "bound_entries": len(mapping),
        "facts": {field: bundle.get(field) for field in COMPARED_FACTS},
        "signer_cert_sha256": digests,
        "modules": bundle["modules"],
        "bundle_signature_entries": bundle["signature_entries"],
    }
    return findings, receipt


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aab", required=True, help="the bundle that gets uploaded to Play")
    parser.add_argument("--apk", required=True, help="the universal APK derived from that bundle")
    parser.add_argument("--pinned-key", default=str(PINNED_KEY))
    arguments = parser.parse_args()

    try:
        findings, receipt = verify(arguments.aab, arguments.apk, arguments.pinned_key)
    except (aab_facts.ProtoError, apk_facts.AxmlError, apk_facts.SigningBlockError,
            zipfile.BadZipFile, OSError, ValueError) as error:
        print(f"verify-bundle-binding: {error}", file=sys.stderr)
        return 1

    if findings:
        print("BUNDLE BINDING FAILED — the universal APK is not this bundle:", file=sys.stderr)
        for finding in findings:
            print(f"  - {finding}", file=sys.stderr)
        return 1

    print(json.dumps(receipt, indent=2, sort_keys=True))
    print(
        f"BUNDLE BINDING HELD — {receipt['bound_entries']} executable entries byte-identical, "
        f"{receipt['facts']['package']} {receipt['facts']['version_name']} "
        f"({receipt['facts']['version_code']})",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
