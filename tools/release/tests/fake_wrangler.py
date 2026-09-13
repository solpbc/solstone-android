#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

"""Fake `wrangler r2 object` for tools/release/tests/test_publish_origin.py.

Objects live as files under $WRANGLER_STATE; every call is appended to
$WRANGLER_LOG so a test can assert ordering (latest must never advance before
the artifact lands). $WRANGLER_FAIL_GET / $WRANGLER_FAIL_PUT make a key return
a transport error rather than a clean miss, and $WRANGLER_CORRUPT_PUT stores
bytes that differ from what was handed over, so the readback has something to
catch.
"""

from __future__ import annotations

import os
import pathlib
import sys


def key_path(state: str, key: str) -> pathlib.Path:
    path = pathlib.Path(state) / key.replace("/", "__")
    return path


def main() -> int:
    log = os.environ["WRANGLER_LOG"]
    state = os.environ["WRANGLER_STATE"]
    args = sys.argv[1:]
    with open(log, "a", encoding="utf-8") as handle:
        handle.write("wrangler " + " ".join(args) + "\n")

    if args[:3] != ["r2", "object", "get"] and args[:3] != ["r2", "object", "put"]:
        print("unexpected wrangler " + " ".join(args), file=sys.stderr)
        return 99

    action = args[2]
    target = args[3]
    bucket, _, key = target.partition("/")
    if bucket != os.environ.get("WRANGLER_BUCKET", "solstone-updates"):
        print(f"unknown bucket {bucket}", file=sys.stderr)
        return 99

    file_argument = ""
    for index, value in enumerate(args):
        if value == "--file":
            file_argument = args[index + 1]

    stored = key_path(state, key)

    if action == "get":
        if os.environ.get("WRANGLER_FAIL_GET") == key:
            print("A request to the Cloudflare API failed.", file=sys.stderr)
            return 1
        if not stored.exists():
            print(f"The specified key does not exist: {key}", file=sys.stderr)
            return 1
        pathlib.Path(file_argument).write_bytes(stored.read_bytes())
        return 0

    if os.environ.get("WRANGLER_FAIL_PUT") == key:
        print("A request to the Cloudflare API failed.", file=sys.stderr)
        return 1
    payload = pathlib.Path(file_argument).read_bytes()
    if os.environ.get("WRANGLER_CORRUPT_PUT") == key:
        payload = payload + b"corrupted-in-flight"
    stored.parent.mkdir(parents=True, exist_ok=True)
    stored.write_bytes(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
