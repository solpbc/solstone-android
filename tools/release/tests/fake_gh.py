#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Fake `gh` for tools/release/tests/test_github_release.py."""

from __future__ import annotations

import json
import os
import shutil
import sys


def main() -> None:
    log = os.environ["GH_LOG"]
    state = os.environ["GH_STATE"]
    apk = os.environ["GH_APK"]
    args = sys.argv[1:]
    with open(log, "a", encoding="utf-8") as handle:
        handle.write("gh " + " ".join(args) + "\n")
    if len(args) < 2:
        raise SystemExit(99)
    action = " ".join(args[:2])
    release_path = os.path.join(state, "release.json")
    stored_apk = os.path.join(state, "phone-real-release.apk")
    if action == "release view":
        if not os.path.exists(release_path):
            print("release not found", file=sys.stderr)
            raise SystemExit(1)
        sys.stdout.write(open(release_path, encoding="utf-8").read())
        return
    if action == "release create":
        tag = args[2]
        target = ""
        verify = False
        title = ""
        i = 3
        while i < len(args):
            if args[i] == "--target":
                target = args[i + 1]
                i += 2
                continue
            if args[i] == "--title":
                title = args[i + 1]
                i += 2
                continue
            if args[i] == "--verify-tag":
                verify = True
                i += 1
                continue
            i += 1
        if not verify:
            print("error: fake gh refused create without --verify-tag", file=sys.stderr)
            raise SystemExit(99)
        if target in {"", "main", "master"}:
            print(f"error: fake gh refused create targeting {target or 'empty'}", file=sys.stderr)
            raise SystemExit(99)
        json.dump(
            {
                "tagName": tag,
                "name": title,
                "targetCommitish": target,
                "isDraft": True,
                "assets": [{"name": "phone-real-release.apk", "size": os.path.getsize(apk)}],
            },
            open(release_path, "w", encoding="utf-8"),
        )
        shutil.copy(apk, stored_apk)
        return
    if action == "release download":
        outdir = ""
        i = 0
        while i < len(args):
            if args[i] in {"--dir", "-D"}:
                outdir = args[i + 1]
                i += 2
                continue
            i += 1
        os.makedirs(outdir, exist_ok=True)
        shutil.copy(stored_apk, os.path.join(outdir, "phone-real-release.apk"))
        return
    if action == "release edit":
        data = json.load(open(release_path, encoding="utf-8"))
        data["isDraft"] = False
        json.dump(data, open(release_path, "w", encoding="utf-8"))
        return
    print("unexpected gh " + " ".join(args), file=sys.stderr)
    raise SystemExit(99)


if __name__ == "__main__":
    main()
