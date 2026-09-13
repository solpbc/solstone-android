#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Refusal and ordering tests for tools/release/publish-origin.sh.

Uses a real git repository (so tag peeling, ancestry and dirty-tree checks
exercise real git objects), a real APK built from captured v2.1.0 bytes, and a
fake `wrangler` whose call log makes publication ORDER assertable: `latest`
must never advance before the artifact has landed and been read back.
"""

from __future__ import annotations

import hashlib
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import apk_fixture  # noqa: E402


REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / "tools" / "release" / "publish-origin.sh"
READER = REPO_ROOT / "tools" / "release" / "apk_facts.py"
KEY_PIN = REPO_ROOT / "tools" / "release" / "release-signing-key.sha256"

GRADLE = """\
android {{
    defaultConfig {{
        versionCode = {code}
        versionName = "{name}"
    }}
}}
"""


def run(cmd, **kwargs):
    kwargs.setdefault("check", True)
    kwargs.setdefault("capture_output", True)
    kwargs.setdefault("text", True)
    return subprocess.run(cmd, **kwargs)


class PublishOriginTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.remote = self.root / "remote.git"
        self.src = self.root / "src"
        self.log = self.root / "wrangler.log"
        self.state = self.root / "objects"
        self.state.mkdir()

        run(["git", "init", "--bare", str(self.remote)])
        run(["git", "clone", str(self.remote), str(self.src)])
        run(["git", "-C", str(self.src), "config", "user.email", "release-test@example.com"])
        run(["git", "-C", str(self.src), "config", "user.name", "Release Test"])

        (self.src / ".gitignore").write_text("artifacts/\n", encoding="utf-8")
        tools = self.src / "tools" / "release"
        tools.mkdir(parents=True)
        shutil.copy(READER, tools / "apk_facts.py")
        shutil.copy(KEY_PIN, tools / "release-signing-key.sha256")
        gradle = self.src / "apps" / "phone"
        gradle.mkdir(parents=True)
        (gradle / "build.gradle.kts").write_text(
            GRADLE.format(code=apk_fixture.VERSION_CODE, name=apk_fixture.VERSION_NAME),
            encoding="utf-8",
        )
        run(["git", "-C", str(self.src), "add", "-A"])
        run(["git", "-C", str(self.src), "commit", "-m", "release scaffolding"])
        run(["git", "-C", str(self.src), "branch", "-M", "main"])
        run(["git", "-C", str(self.src), "push", "-u", "origin", "main"])

        self.candidate = self._rev("HEAD")
        self.apk = self.src / "artifacts" / "phone-real-release.apk"
        self.apk.parent.mkdir(parents=True)
        apk_fixture.make_apk(self.apk)
        self.apk_digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self._tag(apk_fixture.VERSION_NAME, self.candidate)

    # --- helpers ---------------------------------------------------------

    def _rev(self, spec):
        return run(["git", "-C", str(self.src), "rev-parse", spec]).stdout.strip()

    def _tag(self, version, commit, annotated=True):
        name = f"v{version}"
        args = ["git", "-C", str(self.src), "tag"]
        args += ["-a", name, commit, "-m", name] if annotated else [name, commit]
        run(args)
        run(["git", "-C", str(self.src), "push", "origin", f"refs/tags/{name}"])

    def _commit(self, message):
        (self.src / "file.txt").write_text(message, encoding="utf-8")
        run(["git", "-C", str(self.src), "add", "file.txt"])
        run(["git", "-C", str(self.src), "commit", "-m", message])
        return self._rev("HEAD")

    def _fake_wrangler(self):
        helper = pathlib.Path(__file__).resolve().parent / "fake_wrangler.py"
        path = self.root / "fake-wrangler"
        path.write_text(
            textwrap.dedent(
                f"""\
                #!/usr/bin/env bash
                exec {sys.executable} {helper} "$@"
                """
            ),
            encoding="utf-8",
        )
        path.chmod(0o755)
        return path

    def publish(self, *, version=None, candidate=None, lane=None, apk=None, extra_env=None, extra_args=()):
        env = os.environ.copy()
        env.update(
            {
                "WRANGLER_BIN": str(self._fake_wrangler()),
                "WRANGLER_LOG": str(self.log),
                "WRANGLER_STATE": str(self.state),
                "WRANGLER_BUCKET": "solstone-updates",
                "GIT_REMOTE": "origin",
                "RELEASE_BRANCH": "main",
            }
        )
        if extra_env:
            env.update(extra_env)
        args = [
            "bash",
            str(SCRIPT),
            "--version",
            apk_fixture.VERSION_NAME if version is None else version,
            "--candidate",
            self.candidate if candidate is None else candidate,
            "--apk",
            str(self.apk if apk is None else apk),
        ]
        if lane:
            args += ["--lane", lane]
        args += list(extra_args)
        return subprocess.run(args, cwd=self.src, env=env, capture_output=True, text=True)

    def calls(self):
        if not self.log.exists():
            return []
        return [line for line in self.log.read_text(encoding="utf-8").splitlines() if line]

    def puts(self):
        return [line for line in self.calls() if " r2 object put " in line]

    def stored(self, key):
        path = self.state / key.replace("/", "__")
        return path.read_bytes() if path.exists() else None

    def assertRefused(self, result, marker):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(marker, result.stdout + result.stderr)
        self.assertEqual(self.puts(), [], "a refusal must never write to the origin")

    # --- argument refusals ------------------------------------------------

    def test_short_candidate_is_refused(self):
        self.assertRefused(self.publish(candidate=self.candidate[:12]), "candidate-invalid")

    def test_branch_name_as_candidate_is_refused(self):
        self.assertRefused(self.publish(candidate="main"), "candidate-invalid")

    def test_non_semver_version_is_refused(self):
        self.assertRefused(self.publish(version="2.1"), "version-invalid")

    def test_unknown_lane_is_refused(self):
        self.assertRefused(self.publish(lane="production"), "lane-invalid")

    def test_missing_apk_is_refused(self):
        self.assertRefused(self.publish(apk=self.src / "artifacts" / "absent.apk"), "apk-missing")

    # --- source binding ---------------------------------------------------

    def test_dirty_tree_is_refused(self):
        (self.src / "apps" / "phone" / "scratch.txt").write_text("dirty", encoding="utf-8")
        self.assertRefused(self.publish(), "source-unbound")

    def test_candidate_not_on_the_release_branch_is_refused(self):
        run(["git", "-C", str(self.src), "checkout", "-b", "side"])
        orphan = self._commit("never pushed")
        run(["git", "-C", str(self.src), "checkout", "main"])
        self.assertRefused(self.publish(candidate=orphan), "source-unbound")

    def test_release_lane_requires_an_existing_remote_tag(self):
        run(["git", "-C", str(self.src), "push", "origin", ":refs/tags/v2.1.0"])
        self.assertRefused(self.publish(), "remote tag v2.1.0 does not exist")

    def test_release_lane_refuses_a_tag_naming_another_commit(self):
        run(["git", "-C", str(self.src), "push", "origin", ":refs/tags/v2.1.0"])
        run(["git", "-C", str(self.src), "tag", "-d", "v2.1.0"])
        other = self._commit("a later commit")
        run(["git", "-C", str(self.src), "push", "origin", "main"])
        self._tag(apk_fixture.VERSION_NAME, other)
        self.assertRefused(self.publish(), "source-unbound")

    def test_annotated_tag_is_peeled_not_compared_raw(self):
        """An annotated tag advertises a tag OBJECT; comparing it raw never matches."""
        tag_object = self._rev("refs/tags/v2.1.0")
        self.assertNotEqual(tag_object, self.candidate)
        self.assertEqual(self.publish().returncode, 0)

    def test_staging_lane_needs_no_tag(self):
        run(["git", "-C", str(self.src), "push", "origin", ":refs/tags/v2.1.0"])
        result = self.publish(lane="staging")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    # --- artifact binding -------------------------------------------------

    def test_version_the_candidate_does_not_declare_is_refused(self):
        # Staging, so the tag gate (which fires first, and correctly) is not
        # the thing under test here.
        self.assertRefused(self.publish(version="9.9.9", lane="staging"), "version-mismatch")

    def test_apk_version_disagreeing_with_the_candidate_is_refused(self):
        (self.src / "apps" / "phone" / "build.gradle.kts").write_text(
            GRADLE.format(code=99, name=apk_fixture.VERSION_NAME), encoding="utf-8"
        )
        run(["git", "-C", str(self.src), "commit", "-am", "bump versionCode only"])
        run(["git", "-C", str(self.src), "push", "origin", "main"])
        moved = self._rev("HEAD")
        run(["git", "-C", str(self.src), "push", "origin", ":refs/tags/v2.1.0"])
        run(["git", "-C", str(self.src), "tag", "-d", "v2.1.0"])
        self._tag(apk_fixture.VERSION_NAME, moved)
        self.assertRefused(self.publish(candidate=moved), "artifact-mismatch")

    def test_unsigned_apk_is_refused(self):
        unsigned = self.src / "artifacts" / "unsigned.apk"
        apk_fixture.make_apk(unsigned, signing_block=False)
        self.assertRefused(self.publish(apk=unsigned), "artifact-unreadable")

    def test_apk_signed_by_another_key_is_refused(self):
        pin = self.src / "tools" / "release" / "release-signing-key.sha256"
        pin.write_text("a" * 64 + "\n", encoding="utf-8")
        run(["git", "-C", str(self.src), "commit", "-am", "rotate the pin"])
        self.assertRefused(self.publish(), "signature-invalid")

    def test_missing_key_pin_is_refused(self):
        (self.src / "tools" / "release" / "release-signing-key.sha256").unlink()
        run(["git", "-C", str(self.src), "commit", "-am", "drop the pin"])
        self.assertRefused(self.publish(), "release signing key pin is missing")

    # --- publication ------------------------------------------------------

    def test_publishes_artifact_and_checksums_before_latest(self):
        result = self.publish()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

        puts = self.puts()
        artifact = "solstone-android/release/2.1.0/solstone-android-2.1.0.apk"
        checksums = "solstone-android/release/2.1.0/SHA256SUMS"
        latest = "solstone-android/release/latest"
        order = [next(i for i, line in enumerate(puts) if key in line) for key in (artifact, checksums, latest)]
        self.assertEqual(order, sorted(order), "latest must advance last")

        self.assertEqual(self.stored(artifact), self.apk.read_bytes())
        self.assertEqual(
            self.stored(checksums).decode(),
            f"{self.apk_digest}  solstone-android-2.1.0.apk\n",
        )
        self.assertEqual(self.stored(latest).decode(), "version=2.1.0\n")

    def test_published_object_is_read_back_and_rehashed(self):
        artifact = "solstone-android/release/2.1.0/solstone-android-2.1.0.apk"
        result = self.publish(extra_env={"WRANGLER_CORRUPT_PUT": artifact})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("digest-mismatch", result.stdout + result.stderr)
        self.assertIsNone(self.stored("solstone-android/release/latest"))

    def test_republishing_identical_bytes_is_a_no_op(self):
        self.assertEqual(self.publish().returncode, 0)
        self.log.write_text("", encoding="utf-8")
        result = self.publish()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("present", result.stdout)
        artifact = "solstone-android/release/2.1.0/solstone-android-2.1.0.apk"
        self.assertFalse([line for line in self.puts() if artifact in line])

    def test_differing_bytes_under_an_existing_release_version_are_refused(self):
        self.assertEqual(self.publish().returncode, 0)
        apk_fixture.make_apk(self.apk, extra={"padding.txt": b"different bytes"})
        self.log.write_text("", encoding="utf-8")
        result = self.publish()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("object-immutable", result.stdout + result.stderr)

    def test_dev_lane_replaces_rather_than_refusing(self):
        self.assertEqual(self.publish(lane="dev").returncode, 0)
        apk_fixture.make_apk(self.apk, extra={"padding.txt": b"different bytes"})
        result = self.publish(lane="dev")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(
            self.stored("solstone-android/dev/2.1.0/solstone-android-2.1.0.apk"),
            self.apk.read_bytes(),
        )

    def test_release_objects_are_immutable_cached_and_latest_is_not(self):
        self.assertEqual(self.publish().returncode, 0)
        artifact = [line for line in self.puts() if line.endswith("--remote") and "2.1.0/solstone-android-2.1.0.apk" in line]
        self.assertTrue(artifact)
        self.assertIn("public, max-age=31536000, immutable", artifact[0])
        self.assertIn("application/vnd.android.package-archive", artifact[0])
        latest = [line for line in self.puts() if "release/latest" in line]
        self.assertIn("no-cache", latest[0])

    def test_an_unreachable_origin_never_reads_as_an_empty_one(self):
        artifact = "solstone-android/release/2.1.0/solstone-android-2.1.0.apk"
        result = self.publish(extra_env={"WRANGLER_FAIL_GET": artifact})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("origin-unreachable", result.stdout + result.stderr)
        self.assertIsNone(self.stored("solstone-android/release/latest"))

    def test_latest_is_held_when_an_older_version_publishes(self):
        self.assertEqual(self.publish().returncode, 0)
        older = self.src / "artifacts" / "older.apk"
        # A 2.0.9 candidate: the gradle file and the tag both have to say so.
        (self.src / "apps" / "phone" / "build.gradle.kts").write_text(
            GRADLE.format(code=8, name="2.0.9"), encoding="utf-8"
        )
        run(["git", "-C", str(self.src), "commit", "-am", "an older line"])
        run(["git", "-C", str(self.src), "push", "origin", "main"])
        older_candidate = self._rev("HEAD")
        self._tag("2.0.9", older_candidate)
        apk_fixture.make_apk(
            older,
            manifest=apk_fixture.MANIFEST.read_bytes(),
        )
        # The fixture manifest says 2.1.0, so an older publish is refused on the
        # artifact binding before it could ever move latest. That refusal IS the
        # protection; assert it rather than pretending we can forge an old APK.
        result = self.publish(version="2.0.9", candidate=older_candidate, apk=older)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("artifact-mismatch", result.stdout + result.stderr)
        self.assertEqual(self.stored("solstone-android/release/latest").decode(), "version=2.1.0\n")

    def test_dry_run_writes_nothing(self):
        result = self.publish(extra_args=("--dry-run",))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.puts(), [])
        self.assertIn("would publish", result.stdout)


if __name__ == "__main__":
    unittest.main()
