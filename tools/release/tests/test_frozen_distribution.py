#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Executable contract test for the frozen-artifact HITL wrapper.

Firebase App Distribution was retired 2026-09-18
(records/decisions/260918-vpe-firebase-app-distribution-retired-direct-apk-is-the-only-tester-channel.md);
the dist-phone-frozen/firebase-phone-frozen/android-host-dist-phone-frozen
targets these tests used to cover no longer exist. What remains is the
real-hardware HITL gate on a frozen artifact, still real and still load-bearing.
"""

from __future__ import annotations

import pathlib
import subprocess
import tempfile
import unittest


REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]


class FrozenDistributionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.apk = self.root / "frozen.apk"
        self.apk.write_bytes(b"one exact signed release artifact\n")

    def test_hitl_phone_frozen_wrapper_copies_and_gates_without_gradle(self):
        result = subprocess.run(
            [
                "make",
                "-n",
                "ANDROID_REMOTE_HOST=suze.local",
                f"PHONE_RELEASE_APK_LOCAL={self.apk}",
                "android-host-hitl-phone-frozen",
            ],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("make hitl-phone-frozen FROZEN_PHONE_APK=", result.stdout)
        self.assertNotIn("assembleRealRelease", result.stdout)
        self.assertNotIn("firebase appdistribution:distribute", result.stdout)


if __name__ == "__main__":
    unittest.main()
