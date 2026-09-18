#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Executable contract tests for the exact-artifact Firebase release path."""

from __future__ import annotations

import hashlib
import os
import pathlib
import subprocess
import tempfile
import textwrap
import unittest


REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]


class FrozenDistributionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "calls.log"
        self.apk = self.root / "frozen.apk"
        self.apk.write_bytes(b"one exact signed release artifact\n")
        self.original_digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()

        self._tool(
            "adb",
            """
            if [[ "$*" == *" get-state" ]]; then printf 'device\\n'; fi
            printf 'adb %s\\n' "$*" >> "$CALL_LOG"
            """,
        )
        self._tool("maestro", 'printf \'maestro %s\\n\' "$*" >> "$CALL_LOG"')
        self._tool(
            "firebase",
            """
            printf 'firebase %s\\n' "$*" >> "$CALL_LOG"
            if [[ "${MUTATE_APK:-0}" == "1" ]]; then printf 'mutated\\n' >> "$2"; fi
            """,
        )

    def _tool(self, name: str, body: str) -> None:
        path = self.bin / name
        path.write_text(
            "#!/usr/bin/env bash\nset -euo pipefail\n"
            + textwrap.dedent(body).strip()
            + "\n",
            encoding="utf-8",
        )
        path.chmod(0o755)

    def run_target(self, *, mutate: bool = False) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env.update(
            {
                "PATH": f"{self.bin}:{env['PATH']}",
                "CALL_LOG": str(self.log),
                "FIREBASE_APP_ID": "1:test:android:test",
                "GOOGLE_APPLICATION_CREDENTIALS": str(self.root / "credentials.json"),
                "MUTATE_APK": "1" if mutate else "0",
            }
        )
        return subprocess.run(
            [
                "make",
                "dist-phone-frozen",
                f"FROZEN_PHONE_APK={self.apk}",
                f"HITL_ARTIFACTS={self.root / 'hitl'}",
                "RELEASE_NOTES=v9.9.9",
            ],
            cwd=REPO_ROOT,
            env=env,
            capture_output=True,
            text=True,
        )

    def calls(self) -> list[str]:
        return self.log.read_text(encoding="utf-8").splitlines()

    def test_hitl_and_firebase_receive_the_same_frozen_apk(self):
        result = self.run_target()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = self.calls()
        install = next(i for i, line in enumerate(calls) if " install -r -g " in line)
        maestro = next(i for i, line in enumerate(calls) if line.startswith("maestro "))
        firebase = next(i for i, line in enumerate(calls) if line.startswith("firebase "))
        self.assertLess(install, maestro)
        self.assertLess(maestro, firebase)
        self.assertIn(str(self.apk), calls[install])
        self.assertIn(str(self.apk), calls[firebase])
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), self.original_digest)
        self.assertIn(self.original_digest, result.stdout)

    def test_distribution_refuses_if_the_frozen_apk_changes(self):
        result = self.run_target(mutate=True)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Frozen release APK changed during Firebase distribution", result.stderr)

    def test_versioned_host_wrappers_split_prepublish_hitl_from_postpublish_firebase(self):
        common = [
            "make",
            "-n",
            "ANDROID_REMOTE_HOST=suze.local",
            f"PHONE_RELEASE_APK_LOCAL={self.apk}",
        ]
        hitl = subprocess.run(
            [*common, "android-host-hitl-phone-frozen"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(hitl.returncode, 0, hitl.stdout + hitl.stderr)
        self.assertIn("make hitl-phone-frozen FROZEN_PHONE_APK=", hitl.stdout)
        self.assertNotIn("assembleRealRelease", hitl.stdout)
        self.assertNotIn("firebase appdistribution:distribute", hitl.stdout)

        firebase = subprocess.run(
            [*common, "android-host-dist-phone-frozen", "RELEASE_NOTES=v9.9.9"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(firebase.returncode, 0, firebase.stdout + firebase.stderr)
        self.assertIn("make firebase-phone-frozen FROZEN_PHONE_APK=", firebase.stdout)
        self.assertNotIn("make dist-phone-frozen", firebase.stdout)
        self.assertNotIn("assembleRealRelease", firebase.stdout)


if __name__ == "__main__":
    unittest.main()
