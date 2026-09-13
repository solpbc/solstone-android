#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
#
# Stage the exact bytes an already-published GitHub release carries, so the
# release origin can publish a release that was cut before the origin existed —
# and so an origin publish that failed after the mirror already went out has a
# recovery path that cannot rebuild different bytes.
#
# The digest GitHub records for the asset is checked against the bytes that
# actually arrive. A rebuilt APK is not these bytes and must never stand in for
# them: testers are running what this downloads.
#
# Usage: tools/release/pull-released-apk.sh <version> <destination>

set -euo pipefail
export LC_ALL=C

GH_BIN="${GH_BIN:-gh}"
GH_REPO="${GH_REPO:-solpbc/solstone-android}"
ASSET_NAME="${ASSET_NAME:-phone-real-release.apk}"
PYTHON_BIN="${PYTHON_BIN:-python3}"

die() {
    printf 'pull-released-apk: %s\n' "$1" >&2
    exit 1
}

(($# == 2)) || {
    echo "usage: tools/release/pull-released-apk.sh <version> <destination>" >&2
    exit 2
}
version="$1"
destination="$2"

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    die "version-invalid: expected strict SemVer (got $version)"

tag="v$version"

recorded="$("$GH_BIN" release view "$tag" --repo "$GH_REPO" --json assets)" ||
    die "release-unreadable: could not read $tag from $GH_REPO"

recorded_digest="$(printf '%s' "$recorded" | "$PYTHON_BIN" -c '
import json, sys
assets = json.load(sys.stdin)["assets"]
wanted = sys.argv[1]
matches = [a for a in assets if a.get("name") == wanted]
if len(matches) != 1:
    print(f"expected exactly one asset named {wanted}, found {len(matches)}", file=sys.stderr)
    raise SystemExit(1)
digest = matches[0].get("digest") or ""
if not digest.startswith("sha256:"):
    print(f"asset {wanted} records no sha256 digest", file=sys.stderr)
    raise SystemExit(1)
print(digest.split(":", 1)[1])
' "$ASSET_NAME")" || die "release-unreadable: $tag does not record a usable $ASSET_NAME digest"

[[ "$recorded_digest" =~ ^[0-9a-f]{64}$ ]] ||
    die "release-unreadable: recorded digest is not 64-hex ($recorded_digest)"

staging="$(mktemp -d "${TMPDIR:-/tmp}/pull-released-apk.XXXXXX")"
trap 'rm -rf -- "$staging"' EXIT

"$GH_BIN" release download "$tag" --repo "$GH_REPO" --pattern "$ASSET_NAME" --dir "$staging" ||
    die "download-failed: could not download $ASSET_NAME from $tag"

downloaded="$staging/$ASSET_NAME"
[[ -f "$downloaded" ]] || die "download-failed: $ASSET_NAME did not arrive"

actual_digest="$(sha256sum "$downloaded" | awk '{print $1}')"
[[ "$actual_digest" == "$recorded_digest" ]] ||
    die "digest-mismatch: downloaded $actual_digest, release records $recorded_digest"

mkdir -p "$(dirname "$destination")"
if [[ -f "$destination" ]]; then
    existing="$(sha256sum "$destination" | awk '{print $1}')"
    [[ "$existing" == "$actual_digest" ]] ||
        die "destination-occupied: $destination holds different bytes ($existing); move it aside first"
fi
cp -- "$downloaded" "$destination"

printf 'staged %s from %s at %s\n' "$destination" "$tag" "$actual_digest"
