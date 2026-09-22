#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Tests for tools/release/verify-bundle-binding.py.

The failure this gate exists to refuse is gating one build and uploading
another, so every test here is a way those two can come apart: different
payload, a missing or extra entry, a different version, a different key. The
happy path is one test; the refusals are the rest.
"""

from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import aab_fixture  # noqa: E402
import apk_fixture  # noqa: E402


REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
CHECKER = REPO_ROOT / "tools" / "release" / "verify-bundle-binding.py"

_spec = importlib.util.spec_from_file_location("verify_bundle_binding", CHECKER)
binding = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(binding)


class BundleBindingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.aab = aab_fixture.make_aab(self.root / "phone-real-release.aab")
        self.apk = aab_fixture.make_universal_apk(
            self.root / "phone-real-release-universal.apk"
        )

    def verify(self, aab=None, apk=None, pinned_key=binding.PINNED_KEY):
        return binding.verify(aab or self.aab, apk or self.apk, pinned_key)

    # --- the binding holds ------------------------------------------------

    def test_a_universal_apk_from_the_bundle_binds(self):
        findings, receipt = self.verify()
        self.assertEqual(findings, [])
        self.assertEqual(
            receipt["bound_entries"], len(aab_fixture.PAYLOAD) + len(aab_fixture.PROFILE)
        )
        self.assertEqual(receipt["facts"]["version_code"], aab_fixture.VERSION_CODE)
        self.assertEqual(receipt["signer_cert_sha256"], [aab_fixture.SIGNER_CERT_SHA256])

    def test_the_receipt_carries_both_artifacts_digests(self):
        _, receipt = self.verify()
        self.assertEqual(receipt["aab_sha256"], binding._file_digest(self.aab))
        self.assertEqual(receipt["universal_apk_sha256"], binding._file_digest(self.apk))

    def test_a_rebuilt_resource_table_is_not_a_finding(self):
        """Bundle resources are protobuf; bundletool re-encodes them. Expected."""
        findings, _ = self.verify()
        self.assertNotIn("resources.arsc", " ".join(findings))

    # --- the binding is broken --------------------------------------------

    def test_a_different_dex_is_refused(self):
        payload = dict(aab_fixture.PAYLOAD)
        payload["dex/classes.dex"] = b"a second build's dex"
        apk = aab_fixture.make_universal_apk(self.root / "other.apk", payload=payload)
        findings, _ = self.verify(apk=apk)
        self.assertTrue(any("classes.dex differs" in finding for finding in findings), findings)

    def test_a_missing_native_library_is_refused(self):
        payload = {
            name: blob
            for name, blob in aab_fixture.PAYLOAD.items()
            if name != "lib/x86_64/libsolstone.so"
        }
        apk = aab_fixture.make_universal_apk(self.root / "thin.apk", payload=payload)
        findings, _ = self.verify(apk=apk)
        self.assertTrue(
            any("missing lib/x86_64/libsolstone.so" in finding for finding in findings), findings
        )

    def test_code_the_bundle_does_not_carry_is_refused(self):
        apk = aab_fixture.make_universal_apk(
            self.root / "extra.apk", extra={"classes9.dex": b"smuggled"}
        )
        findings, _ = self.verify(apk=apk)
        self.assertTrue(
            any("carries classes9.dex" in finding for finding in findings), findings
        )

    def test_a_missing_baseline_profile_is_refused(self):
        apk = aab_fixture.make_universal_apk(self.root / "noprofile.apk", profile={})
        findings, _ = self.verify(apk=apk)
        self.assertTrue(
            any("assets/dexopt/baseline.prof" in finding for finding in findings), findings
        )

    def test_a_different_version_is_refused(self):
        """The v2.1.0 APK against the 2.1.5 bundle — the real mix-up shape."""
        apk = aab_fixture.make_universal_apk(
            self.root / "old.apk", manifest=apk_fixture.MANIFEST.read_bytes()
        )
        findings, _ = self.verify(apk=apk)
        self.assertTrue(any("version_code disagrees" in finding for finding in findings), findings)
        self.assertTrue(any("version_name disagrees" in finding for finding in findings), findings)

    def test_a_feature_module_is_refused_rather_than_silently_unchecked(self):
        aab = aab_fixture.make_aab(self.root / "features.aab", modules=("base", "importer"))
        findings, _ = self.verify(aab=aab)
        self.assertTrue(any("carries modules" in finding for finding in findings), findings)

    def test_a_signer_that_is_not_the_pinned_release_key_is_refused(self):
        other = self.root / "other-key.sha256"
        other.write_text("# not our key\n" + "00" * 32 + "\n")
        findings, _ = self.verify(pinned_key=other)
        self.assertTrue(
            any("is not the pinned release key" in finding for finding in findings), findings
        )

    def test_a_bundle_signed_by_a_different_key_is_refused(self):
        aab = aab_fixture.make_aab(self.root / "elsewhere.aab", signature=b"a different signer")
        findings, _ = self.verify(aab=aab)
        self.assertTrue(
            any("signed by different keys" in finding for finding in findings), findings
        )

    def test_an_unsigned_bundle_is_refused(self):
        aab = aab_fixture.make_aab(self.root / "unsigned.aab", signature=None)
        # `signature=None` means "use the captured one"; drop the entry instead.
        import zipfile

        stripped = self.root / "stripped.aab"
        with zipfile.ZipFile(aab) as source, zipfile.ZipFile(stripped, "w") as target:
            for item in source.infolist():
                if item.filename.endswith(".RSA"):
                    continue
                target.writestr(item, source.read(item.filename))
        findings, _ = self.verify(aab=stripped)
        self.assertTrue(any("it is unsigned" in finding for finding in findings), findings)

    # --- the command line -------------------------------------------------

    def test_cli_exits_zero_and_prints_the_receipt(self):
        result = subprocess.run(
            [sys.executable, str(CHECKER), "--aab", str(self.aab), "--apk", str(self.apk)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("BUNDLE BINDING HELD", result.stderr)
        self.assertIn(aab_fixture.VERSION_NAME, result.stdout)

    def test_cli_exits_one_and_names_the_disagreement(self):
        apk = aab_fixture.make_universal_apk(
            self.root / "old.apk", manifest=apk_fixture.MANIFEST.read_bytes()
        )
        result = subprocess.run(
            [sys.executable, str(CHECKER), "--aab", str(self.aab), "--apk", str(apk)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("BUNDLE BINDING FAILED", result.stderr)
        self.assertIn("version_code disagrees", result.stderr)

    def test_cli_reports_a_bad_artifact_without_a_traceback(self):
        broken = self.root / "broken.aab"
        broken.write_bytes(b"not a zip at all")
        result = subprocess.run(
            [sys.executable, str(CHECKER), "--aab", str(broken), "--apk", str(self.apk)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 1)
        self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
