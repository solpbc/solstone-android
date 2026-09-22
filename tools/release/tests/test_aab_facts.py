#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Tests for tools/release/aab_facts.py, against captured 2.1.5 bundle bytes.

Ground truth is what aapt2 reported for the universal APK built from that same
bundle; see fixtures/README.md. The point of the reader is that a bundle and
the APK derived from it must declare the same thing, so testing it against the
Android tools' reading of its own twin is the right oracle.
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import aab_facts  # noqa: E402
import aab_fixture  # noqa: E402


REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
READER = REPO_ROOT / "tools" / "release" / "aab_facts.py"


class AabFactsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.aab = aab_fixture.make_aab(self.root / "fixture.aab")

    def test_reads_the_facts_the_android_tools_report(self):
        facts = aab_facts.aab_facts(str(self.aab))
        self.assertEqual(facts["package"], aab_fixture.PACKAGE)
        self.assertEqual(facts["version_name"], aab_fixture.VERSION_NAME)
        self.assertEqual(facts["version_code"], aab_fixture.VERSION_CODE)
        self.assertEqual(facts["min_sdk"], aab_fixture.MIN_SDK)
        self.assertEqual(facts["target_sdk"], aab_fixture.TARGET_SDK)
        self.assertEqual(facts["permissions"], aab_fixture.PERMISSIONS)

    def test_version_code_is_an_integer_not_the_string_aapt2_prints(self):
        """apk_facts returns an int, and the two are compared field by field."""
        self.assertIsInstance(aab_facts.aab_facts(str(self.aab))["version_code"], int)

    def test_modules_are_listed_with_base_first(self):
        many = aab_fixture.make_aab(
            self.root / "features.aab", modules=("base", "importer")
        )
        self.assertEqual(aab_facts.aab_facts(str(many))["modules"], ["base", "importer"])

    def test_signature_entries_name_the_jar_signature(self):
        facts = aab_facts.aab_facts(str(self.aab))
        self.assertEqual(
            facts["signature_entries"], ["META-INF/UPLOAD.RSA", "META-INF/UPLOAD.SF"]
        )

    def test_missing_base_manifest_is_refused(self):
        empty = self.root / "empty.aab"
        with zipfile.ZipFile(empty, "w") as archive:
            archive.writestr("base/dex/classes.dex", b"not a manifest")
        with self.assertRaises(aab_facts.ProtoError):
            aab_facts.aab_facts(str(empty))

    def test_binary_axml_manifest_is_refused(self):
        """An APK's manifest in a bundle's slot is the mix-up worth catching."""
        axml = aab_fixture.UNIVERSAL_MANIFEST.read_bytes()
        aab = aab_fixture.make_aab(self.root / "axml.aab", manifest=axml)
        with self.assertRaises(aab_facts.ProtoError):
            aab_facts.aab_facts(str(aab))

    def test_truncated_manifest_is_refused(self):
        truncated = aab_fixture.AAB_MANIFEST.read_bytes()[:64]
        aab = aab_fixture.make_aab(self.root / "truncated.aab", manifest=truncated)
        with self.assertRaises(aab_facts.ProtoError):
            aab_facts.aab_facts(str(aab))

    def test_cli_emits_json(self):
        result = subprocess.run(
            [sys.executable, str(READER), str(self.aab)],
            capture_output=True,
            text=True,
            check=True,
        )
        facts = json.loads(result.stdout)
        self.assertEqual(facts["version_name"], aab_fixture.VERSION_NAME)

    def test_cli_field_prints_one_value_per_line(self):
        result = subprocess.run(
            [sys.executable, str(READER), str(self.aab), "--field", "permissions"],
            capture_output=True,
            text=True,
            check=True,
        )
        self.assertEqual(result.stdout.strip().splitlines(), aab_fixture.PERMISSIONS)

    def test_cli_refuses_an_unknown_field(self):
        result = subprocess.run(
            [sys.executable, str(READER), str(self.aab), "--field", "nope"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 2)

    def test_cli_reports_a_bad_bundle_without_a_traceback(self):
        broken = self.root / "broken.aab"
        broken.write_bytes(b"not a zip at all")
        result = subprocess.run(
            [sys.executable, str(READER), str(broken)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 1)
        self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
