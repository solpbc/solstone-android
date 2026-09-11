#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Refusal and binding tests for tools/release/github-release.sh.

Uses a real git repository plus a fake `gh` so annotated vs lightweight
peeling, ancestry, and dirty-tree checks exercise the actual git objects.
"""

from __future__ import annotations

import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import textwrap
import unittest


REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / "tools" / "release" / "github-release.sh"
MAKEFILE = REPO_ROOT / "Makefile"


def run(cmd, **kwargs):
    kwargs.setdefault("check", True)
    kwargs.setdefault("capture_output", True)
    kwargs.setdefault("text", True)
    return subprocess.run(cmd, **kwargs)


class GithubReleaseScriptTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.remote = self.root / "remote.git"
        self.src = self.root / "src"
        self.log = self.root / "calls.log"
        self.gh_state = self.root / "gh-state"
        self.gh_state.mkdir()
        run(["git", "init", "--bare", str(self.remote)])
        run(["git", "clone", str(self.remote), str(self.src)])
        run(["git", "-C", str(self.src), "config", "user.email", "release-test@example.com"])
        run(["git", "-C", str(self.src), "config", "user.name", "Release Test"])
        (self.src / ".gitignore").write_text("artifacts/\n", encoding="utf-8")
        run(["git", "-C", str(self.src), "add", ".gitignore"])
        run(["git", "-C", str(self.src), "commit", "-m", "ignore artifacts"])
        self._commit("initial", "hello")
        run(["git", "-C", str(self.src), "branch", "-M", "main"])
        run(["git", "-C", str(self.src), "push", "-u", "origin", "main"])
        self.candidate = self._rev("HEAD")
        self.apk = self.src / "artifacts" / "phone-real-release.apk"
        self.apk.parent.mkdir(parents=True)
        self.apk.write_bytes(b"fake-apk-bytes")
        self.notes = self.src / "artifacts" / "notes-1.2.3.md"
        self.notes.write_text("## [1.2.3] - 2026-09-11\n\n### Fixed\n- bind the tag\n", encoding="utf-8")
        self.gh_bin = None

    def _commit(self, message, content):
        (self.src / "file.txt").write_text(content, encoding="utf-8")
        run(["git", "-C", str(self.src), "add", "file.txt"])
        run(["git", "-C", str(self.src), "commit", "-m", message])

    def _rev(self, spec):
        return run(["git", "-C", str(self.src), "rev-parse", spec]).stdout.strip()

    def _restore_artifacts(self):
        self.apk.parent.mkdir(parents=True, exist_ok=True)
        self.apk.write_bytes(b"fake-apk-bytes")
        self.notes.write_text("## [1.2.3] - 2026-09-11\n\n### Fixed\n- bind the tag\n", encoding="utf-8")

    def _rewrite_fake_gh(self):
        helper = pathlib.Path(__file__).resolve().parent / "fake_gh.py"
        path = self.root / "fake-gh"
        path.write_text(
            textwrap.dedent(
                f"""\
                #!/usr/bin/env bash
                exec python3 {helper} "$@"
                """
            ),
            encoding="utf-8",
        )
        path.chmod(0o755)
        self.gh_bin = path

    def run_script(self, *, candidate=None, extra_env=None):
        self._rewrite_fake_gh()
        env = os.environ.copy()
        env.update(
            {
                "GIT_BIN": "git",
                "GH_BIN": str(self.gh_bin),
                "GIT_REMOTE": "origin",
                "GH_REPO": "solpbc/solstone-android",
                "RELEASE_BRANCH": "main",
                "GH_LOG": str(self.log),
                "GH_STATE": str(self.gh_state),
                "GH_APK": str(self.apk),
            }
        )
        if extra_env:
            env.update(extra_env)
        cand = candidate if candidate is not None else self.candidate
        return subprocess.run(
            [
                "bash",
                str(SCRIPT),
                "--version",
                "1.2.3",
                "--candidate",
                cand,
                "--apk",
                str(self.apk),
                "--notes",
                str(self.notes),
            ],
            cwd=self.src,
            env=env,
            capture_output=True,
            text=True,
        )

    def calls(self):
        if not self.log.exists():
            return ""
        return self.log.read_text(encoding="utf-8")

    def remote_peel(self):
        output = run(
            ["git", "-C", str(self.src), "ls-remote", "--tags", "origin", "refs/tags/v1.2.3", "refs/tags/v1.2.3^{}"]
        ).stdout
        peeled = ""
        direct = ""
        for line in output.splitlines():
            sha, ref = line.split("\t", 1)
            if ref == "refs/tags/v1.2.3^{}":
                peeled = sha
            elif ref == "refs/tags/v1.2.3":
                direct = sha
        return peeled or direct

    def test_refuses_without_candidate(self):
        proc = subprocess.run(
            ["bash", str(SCRIPT), "--version", "1.2.3", "--apk", str(self.apk), "--notes", str(self.notes)],
            cwd=self.src,
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 2)
        self.assertIn("usage:", proc.stderr)
        self.assertFalse(self.log.exists())

    def test_refuses_short_candidate(self):
        proc = self.run_script(candidate=self.candidate[:12])
        self.assertEqual(proc.returncode, 2)
        self.assertIn("full 40-hex", proc.stderr)
        self.assertNotIn("gh ", self.calls())

    def test_refuses_when_head_is_not_candidate(self):
        self._commit("later", "moved")
        run(["git", "-C", str(self.src), "push", "origin", "main"])
        proc = self.run_script(candidate=self.candidate)
        self.assertEqual(proc.returncode, 2)
        self.assertIn("is not HEAD", proc.stderr)
        self.assertNotIn("gh ", self.calls())

    def test_refuses_dirty_tree(self):
        (self.src / "dirt.txt").write_text("nope", encoding="utf-8")
        proc = self.run_script()
        self.assertEqual(proc.returncode, 2)
        self.assertIn("dirty source tree", proc.stderr)
        self.assertNotIn("gh ", self.calls())

    def test_refuses_when_candidate_is_not_ancestor(self):
        run(["git", "-C", str(self.src), "checkout", "--orphan", "other"])
        for leftover in self.src.iterdir():
            if leftover.name == ".git":
                continue
            if leftover.is_dir():
                shutil.rmtree(leftover)
            else:
                leftover.unlink()
        (self.src / "other.txt").write_text("orphan", encoding="utf-8")
        run(["git", "-C", str(self.src), "add", "other.txt"])
        run(["git", "-C", str(self.src), "commit", "-m", "orphan"])
        run(["git", "-C", str(self.src), "push", "--force", "origin", "HEAD:main"])
        run(["git", "-C", str(self.src), "checkout", "-B", "main", self.candidate])
        run(["git", "-C", str(self.src), "reset", "--hard", self.candidate])
        run(["git", "-C", str(self.src), "clean", "-fd"])
        self._restore_artifacts()
        proc = self.run_script(candidate=self.candidate)
        self.assertEqual(proc.returncode, 2)
        self.assertIn("not an ancestor", proc.stderr)
        self.assertNotIn("gh ", self.calls())

    def test_absent_tag_push_failure_refuses_without_github_inventing_one(self):
        hook = self.remote / "hooks" / "update"
        hook.write_text("#!/bin/bash\necho tags-refused >&2\nexit 1\n", encoding="utf-8")
        hook.chmod(0o755)
        proc = self.run_script()
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn("gh ", self.calls())
        self.assertEqual(self.remote_peel(), "")

    def test_preexisting_tag_on_other_commit_refuses_without_force(self):
        self._commit("other", "other")
        other = self._rev("HEAD")
        run(["git", "-C", str(self.src), "tag", "-a", "v1.2.3", other, "-m", "wrong"])
        run(["git", "-C", str(self.src), "push", "origin", "refs/tags/v1.2.3"])
        run(["git", "-C", str(self.src), "reset", "--hard", self.candidate])
        proc = self.run_script()
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("refusing to force-update or publish", proc.stderr)
        self.assertNotIn("gh ", self.calls())
        self.assertNotIn("git tag -d", self.calls())
        self.assertEqual(self.remote_peel(), other)

    def test_concurrent_tip_publishes_at_candidate_not_new_tip(self):
        self._commit("later", "moved")
        run(["git", "-C", str(self.src), "push", "origin", "main"])
        later = self._rev("HEAD")
        run(["git", "-C", str(self.src), "reset", "--hard", self.candidate])
        proc = self.run_script()
        self.assertEqual(proc.returncode, 0, proc.stderr)
        calls = self.calls()
        self.assertIn("--verify-tag", calls)
        self.assertIn(f"--target {self.candidate}", calls)
        self.assertNotIn(f"--target {later}", calls)
        self.assertNotIn("--target main", calls)
        self.assertEqual(self.remote_peel(), self.candidate)
        self.assertNotEqual(self.remote_peel(), later)
        release = json.loads((self.gh_state / "release.json").read_text(encoding="utf-8"))
        self.assertEqual(release["targetCommitish"], self.candidate)
        self.assertFalse(release["isDraft"])

    def test_happy_path_pushes_annotated_tag_then_verifies_bytes(self):
        proc = self.run_script()
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("published v1.2.3", proc.stdout)
        self.assertIn("apk sha256", proc.stdout)
        peeled = self.remote_peel()
        self.assertEqual(peeled, self.candidate)
        # Annotated tags expose both the tag object and the peeled commit.
        listing = run(
            ["git", "-C", str(self.src), "ls-remote", "--tags", "origin", "refs/tags/v1.2.3", "refs/tags/v1.2.3^{}"]
        ).stdout
        self.assertIn("refs/tags/v1.2.3^{}", listing)
        tag_object = [
            line.split("\t", 1)[0]
            for line in listing.splitlines()
            if line.endswith("refs/tags/v1.2.3")
        ][0]
        self.assertNotEqual(tag_object, self.candidate)
        calls = self.calls()
        self.assertIn("gh release create", calls)
        self.assertIn("--verify-tag", calls)
        self.assertIn("gh release edit", calls)
        self.assertLess(calls.index("gh release create"), calls.index("gh release edit"))

    def test_lightweight_tag_on_other_commit_is_compared_as_commit(self):
        self._commit("other", "other")
        other = self._rev("HEAD")
        run(["git", "-C", str(self.src), "tag", "v1.2.3", other])
        run(["git", "-C", str(self.src), "push", "origin", "refs/tags/v1.2.3"])
        run(["git", "-C", str(self.src), "reset", "--hard", self.candidate])
        proc = self.run_script()
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("refusing to force-update or publish", proc.stderr)
        self.assertEqual(self.remote_peel(), other)

    def test_digest_mismatch_refuses_without_publishing(self):
        self._rewrite_fake_gh()
        env = os.environ.copy()
        env.update(
            {
                "GH_BIN": str(self.gh_bin),
                "GH_LOG": str(self.log),
                "GH_STATE": str(self.gh_state),
                "GH_APK": str(self.apk),
            }
        )
        # Seed a draft whose stored bytes will not match the local APK.
        (self.gh_state / "release.json").write_text(
            json.dumps(
                {
                    "tagName": "v1.2.3",
                    "name": "solstone for Android v1.2.3",
                    "targetCommitish": self.candidate,
                    "isDraft": True,
                    "assets": [{"name": "phone-real-release.apk", "size": 4}],
                }
            ),
            encoding="utf-8",
        )
        (self.gh_state / "phone-real-release.apk").write_bytes(b"nope")
        run(["git", "-C", str(self.src), "tag", "-a", "v1.2.3", self.candidate, "-m", "ok"])
        run(["git", "-C", str(self.src), "push", "origin", "refs/tags/v1.2.3"])
        proc = self.run_script()
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("published apk sha256", proc.stderr)
        self.assertNotIn("gh release edit", self.calls())
        self.assertTrue(json.loads((self.gh_state / "release.json").read_text())["isDraft"])

    def test_make_refuses_without_candidate(self):
        proc = subprocess.run(
            ["make", "-f", str(MAKEFILE), "github-release", "VERSION=1.2.3"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("CANDIDATE", proc.stderr)
        self.assertIn("never infers", proc.stderr)


if __name__ == "__main__":
    unittest.main()
