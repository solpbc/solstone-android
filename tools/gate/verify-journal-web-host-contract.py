# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc

import hashlib
import json
import subprocess
import sys
from pathlib import Path


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    provenance_path = repo_root / "apps/phone/host-contract.provenance.json"
    asset_path = repo_root / "apps/phone/src/main/assets/journal-web-host/host-contract.json"

    try:
        provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
        repository = provenance["repository"]
        commit = provenance["commit"]
        contract_path = provenance["path"]
        expected_digest = provenance["sha256"]
        vendored = asset_path.read_bytes()
    except Exception as error:
        print(f"journal web host contract pin: cannot read inputs: {error}", file=sys.stderr)
        return 1

    actual_digest = hashlib.sha256(vendored).hexdigest()
    if actual_digest != expected_digest:
        print("journal web host contract pin: vendored digest differs from provenance", file=sys.stderr)
        return 1

    endpoint = f"repos/{repository}/contents/{contract_path}?ref={commit}"
    try:
        fetched = subprocess.run(
            ["gh", "api", "-H", "Accept: application/vnd.github.raw", endpoint],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        ).stdout
    except Exception as error:
        print(f"journal web host contract pin: fetch failed: {error}", file=sys.stderr)
        return 1

    if fetched != vendored:
        print("journal web host contract pin: fetched bytes differ from vendored asset", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
