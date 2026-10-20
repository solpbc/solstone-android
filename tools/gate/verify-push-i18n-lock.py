# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

import hashlib
import json
import sys
from pathlib import Path

EXPECTED_MANIFEST_SHA256 = "65f1f8733946a8d8c3c01b40adee69bcd56750ce54e9387b4692b8a8ff70ae00"
EXPECTED_REPO = "solpbc/solstone-journal"
EXPECTED_COMMIT = "eb0b7ffd6f306b9502bf17802b110e34153dac83"
EXPECTED_PATH = "contracts/push-i18n/r1"
EXPECTED_REVISION = 1


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    pin_path = repo_root / "contracts/push-i18n/PIN.json"
    lock_path = repo_root / "contracts/push-i18n/r1/import-lock.json"
    bundle_dir = repo_root / "contracts/push-i18n/r1"

    if not pin_path.is_file():
        print(f"push i18n lock check: missing PIN.json at {pin_path}", file=sys.stderr)
        return 1

    if not lock_path.is_file():
        print(f"push i18n lock check: missing import-lock.json at {lock_path}", file=sys.stderr)
        return 1

    try:
        pin = json.loads(pin_path.read_text(encoding="utf-8"))
    except Exception as err:
        print(f"push i18n lock check: cannot read PIN.json: {err}", file=sys.stderr)
        return 1

    if pin.get("repository") != EXPECTED_REPO:
        print(f"push i18n lock check: repository mismatch in PIN: {pin.get('repository')}", file=sys.stderr)
        return 1
    if pin.get("commit") != EXPECTED_COMMIT:
        print(f"push i18n lock check: commit mismatch in PIN: {pin.get('commit')}", file=sys.stderr)
        return 1
    if pin.get("path") != EXPECTED_PATH:
        print(f"push i18n lock check: path mismatch in PIN: {pin.get('path')}", file=sys.stderr)
        return 1
    if pin.get("manifest_sha256") != EXPECTED_MANIFEST_SHA256:
        print(f"push i18n lock check: manifest_sha256 mismatch in PIN: {pin.get('manifest_sha256')}", file=sys.stderr)
        return 1
    if pin.get("catalog_revision") != EXPECTED_REVISION:
        print(f"push i18n lock check: catalog_revision mismatch in PIN: {pin.get('catalog_revision')}", file=sys.stderr)
        return 1

    try:
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
    except Exception as err:
        print(f"push i18n lock check: cannot read import-lock.json: {err}", file=sys.stderr)
        return 1

    manifest_sha256 = lock.get("manifest_sha256")
    if manifest_sha256 != EXPECTED_MANIFEST_SHA256:
        print(f"push i18n lock check: manifest_sha256 mismatch in lock: {manifest_sha256}", file=sys.stderr)
        return 1
    if manifest_sha256 != pin["manifest_sha256"]:
        print("push i18n lock check: manifest_sha256 in lock differs from PIN.json", file=sys.stderr)
        return 1

    members = lock.get("members", [])
    if not members:
        print("push i18n lock check: empty members in import-lock.json", file=sys.stderr)
        return 1

    # Verify canonical manifest digest
    members_json = json.dumps(members, separators=(',', ':'))
    if hashlib.sha256(members_json.encode('utf-8')).hexdigest() != manifest_sha256:
        print("push i18n lock check: members array digest differs from manifest_sha256", file=sys.stderr)
        return 1

    # Verify each member file digest
    for member in members:
        rel_path = member.get("path")
        expected_hash = member.get("sha256")
        member_file = bundle_dir / rel_path
        if not member_file.is_file():
            print(f"push i18n lock check: missing member file {member_file}", file=sys.stderr)
            return 1
        actual_hash = hashlib.sha256(member_file.read_bytes()).hexdigest()
        if actual_hash != expected_hash:
            print(f"push i18n lock check: hash mismatch for {rel_path}: expected {expected_hash}, got {actual_hash}", file=sys.stderr)
            return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
