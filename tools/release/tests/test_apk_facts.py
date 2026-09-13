#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Tests for tools/release/apk_facts.py, against captured v2.1.0 bytes.

Ground truth is what aapt2, apkanalyzer and apksigner reported for the real
published APK; see fixtures/README.md.
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import apk_facts  # noqa: E402
import apk_fixture  # noqa: E402


REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
READER = REPO_ROOT / "tools" / "release" / "apk_facts.py"
PHONE_MANIFEST = REPO_ROOT / "apps" / "phone" / "src" / "main" / "AndroidManifest.xml"


class ApkFactsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.apk = apk_fixture.make_apk(self.root / "fixture.apk")

    def test_reads_the_facts_the_android_tools_report(self):
        facts = apk_facts.apk_facts(str(self.apk))
        self.assertEqual(facts["package"], apk_fixture.PACKAGE)
        self.assertEqual(facts["version_name"], apk_fixture.VERSION_NAME)
        self.assertEqual(facts["version_code"], apk_fixture.VERSION_CODE)
        self.assertEqual(facts["min_sdk"], apk_fixture.MIN_SDK)
        self.assertEqual(facts["target_sdk"], apk_fixture.TARGET_SDK)
        self.assertEqual(facts["signer_cert_sha256"], [apk_fixture.SIGNER_CERT_SHA256])
        self.assertEqual(facts["permissions"], apk_fixture.PERMISSIONS)

    def test_merged_permissions_are_wider_than_the_app_manifest(self):
        """The artifact is not the source, and this is the test that says so.

        Library manifests merge permissions in. A page or a review that reads
        apps/phone's own manifest and stops there is describing a set the
        shipped APK does not have.
        """
        source = PHONE_MANIFEST.read_text(encoding="utf-8")
        merged = set(apk_facts.apk_facts(str(self.apk))["permissions"])
        library_contributed = {
            name for name in merged if f'android:name="{name}"' not in source
        }
        self.assertEqual(
            library_contributed,
            {
                "android.permission.WAKE_LOCK",
                "app.solstone.observer.phone.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            },
        )

    def test_unsigned_apk_is_refused(self):
        unsigned = apk_fixture.make_apk(self.root / "unsigned.apk", signing_block=False)
        with self.assertRaises(apk_facts.SigningBlockError):
            apk_facts.apk_facts(str(unsigned))

    def test_missing_manifest_is_refused(self):
        empty = self.root / "empty.apk"
        import zipfile

        with zipfile.ZipFile(empty, "w") as archive:
            archive.writestr("classes.dex", b"not a manifest")
        with self.assertRaises(apk_facts.AxmlError):
            apk_facts.apk_facts(str(empty))

    def test_non_axml_manifest_is_refused(self):
        text = apk_fixture.make_apk(
            self.root / "text.apk", manifest=b"<manifest>plain text</manifest>"
        )
        with self.assertRaises(apk_facts.AxmlError):
            apk_facts.apk_facts(str(text))

    def test_truncated_manifest_is_refused(self):
        truncated = apk_fixture.MANIFEST.read_bytes()[:64]
        apk = apk_fixture.make_apk(self.root / "truncated.apk", manifest=truncated)
        with self.assertRaises(apk_facts.AxmlError):
            apk_facts.apk_facts(str(apk))

    def test_cli_emits_json(self):
        result = subprocess.run(
            [sys.executable, str(READER), str(self.apk)],
            capture_output=True,
            text=True,
            check=True,
        )
        facts = json.loads(result.stdout)
        self.assertEqual(facts["version_name"], apk_fixture.VERSION_NAME)

    def test_cli_field_prints_one_value_per_line(self):
        result = subprocess.run(
            [sys.executable, str(READER), str(self.apk), "--field", "permissions"],
            capture_output=True,
            text=True,
            check=True,
        )
        self.assertEqual(result.stdout.strip().splitlines(), apk_fixture.PERMISSIONS)

    def test_cli_refuses_an_unknown_field(self):
        result = subprocess.run(
            [sys.executable, str(READER), str(self.apk), "--field", "nope"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 2)

    def test_cli_reports_a_bad_apk_without_a_traceback(self):
        broken = self.root / "broken.apk"
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
