#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
#
# Publish a GitHub release for an explicit candidate commit.
#
# The tag is never inferred from HEAD or from the remote default branch.
# An annotated tag is pushed first and peeled from the remote; publication
# then uses --verify-tag so GitHub cannot invent a tag at a concurrent tip.
#
# Usage:
#   tools/release/github-release.sh \
#     --version x.y.z \
#     --candidate <full 40-hex sha> \
#     --apk artifacts/phone-real-release.apk \
#     --notes artifacts/notes-x.y.z.md
set -euo pipefail

GIT_BIN="${GIT_BIN:-git}"
GH_BIN="${GH_BIN:-gh}"
GIT_REMOTE="${GIT_REMOTE:-origin}"
GH_REPO="${GH_REPO:-solpbc/solstone-android}"
RELEASE_BRANCH="${RELEASE_BRANCH:-main}"

VERSION=""
CANDIDATE=""
APK=""
NOTES=""

usage() {
  echo "usage: tools/release/github-release.sh --version x.y.z --candidate <full 40-hex sha> --apk <apk> --notes <file>" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      [[ $# -ge 2 ]] || usage
      VERSION="$2"
      shift 2
      ;;
    --candidate)
      [[ $# -ge 2 ]] || usage
      CANDIDATE="$2"
      shift 2
      ;;
    --apk)
      [[ $# -ge 2 ]] || usage
      APK="$2"
      shift 2
      ;;
    --notes)
      [[ $# -ge 2 ]] || usage
      NOTES="$2"
      shift 2
      ;;
    -*)
      echo "error: unknown option $1" >&2
      usage
      ;;
    *)
      echo "error: unexpected argument $1" >&2
      usage
      ;;
  esac
done

[[ -n "$VERSION" && -n "$CANDIDATE" && -n "$APK" && -n "$NOTES" ]] || usage

# The release names its candidate explicitly. Never accept a short SHA, a
# branch, or a tag name — those are how a tip gets inferred.
if [[ ! "$CANDIDATE" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "error: --candidate must be a full 40-hex commit SHA (got ${CANDIDATE:-empty})" >&2
  exit 2
fi
CANDIDATE="${CANDIDATE,,}"

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z]+)*$ ]]; then
  echo "error: --version must be a dotted version (got ${VERSION})" >&2
  exit 2
fi

REPO_ROOT=$("$GIT_BIN" rev-parse --show-toplevel)
cd "$REPO_ROOT"

# Resolve paths after we know the repo root, so relative Makefile paths work.
if [[ "$APK" != /* ]]; then APK="$REPO_ROOT/$APK"; fi
if [[ "$NOTES" != /* ]]; then NOTES="$REPO_ROOT/$NOTES"; fi

[[ -f "$APK" ]] || { echo "error: apk not found: $APK" >&2; exit 2; }
[[ -f "$NOTES" ]] || { echo "error: notes file not found: $NOTES" >&2; exit 2; }

HEAD=$("$GIT_BIN" rev-parse HEAD)
if [[ "$HEAD" != "$CANDIDATE" ]]; then
  echo "error: candidate ${CANDIDATE} is not HEAD ${HEAD}" >&2
  exit 2
fi

status=$("$GIT_BIN" status --porcelain --untracked-files=normal) || {
  echo "error: could not inspect the source tree" >&2
  exit 2
}
if [[ -n "$status" ]]; then
  echo "error: refusing to publish from a dirty source tree" >&2
  printf '%s\n' "$status" >&2
  exit 2
fi

"$GIT_BIN" fetch "$GIT_REMOTE" "$RELEASE_BRANCH"
if ! "$GIT_BIN" merge-base --is-ancestor "$CANDIDATE" FETCH_HEAD; then
  echo "error: candidate ${CANDIDATE} is not an ancestor of ${GIT_REMOTE}/${RELEASE_BRANCH}" >&2
  exit 2
fi

TAG="v${VERSION}"
TITLE="solstone for Android v${VERSION}"

sha256_file() {
  python3 -c 'import hashlib, sys
p = sys.argv[1]
h = hashlib.sha256()
with open(p, "rb") as handle:
    while True:
        chunk = handle.read(1024 * 1024)
        if not chunk:
            break
        h.update(chunk)
print(h.hexdigest())' "$1"
}

LOCAL_SHA=$(sha256_file "$APK")
echo "apk sha256 ${LOCAL_SHA} (${APK})"

peel_remote_tag() {
  local output peeled direct
  if ! output=$("$GIT_BIN" ls-remote --tags "$GIT_REMOTE" "refs/tags/${TAG}" "refs/tags/${TAG}^{}"); then
    return 2
  fi
  # Annotated tags advertise the tag object at refs/tags/NAME and the commit
  # at refs/tags/NAME^{}. Lightweight tags advertise only the commit. Never
  # compare the unpeeled tag-object SHA to a commit.
  peeled=$(printf '%s\n' "$output" | awk -v ref="refs/tags/${TAG}^{}" '$2 == ref { print $1; exit }')
  direct=$(printf '%s\n' "$output" | awk -v ref="refs/tags/${TAG}" '$2 == ref { print $1; exit }')
  if [[ -n "$peeled" ]]; then
    printf '%s\n' "$peeled"
    return 0
  fi
  if [[ -n "$direct" ]]; then
    printf '%s\n' "$direct"
    return 0
  fi
  return 1
}

REMOTE_TAG_COMMIT=""
REMOTE_TAG_STATUS=0
REMOTE_TAG_COMMIT=$(peel_remote_tag) || REMOTE_TAG_STATUS=$?
if [[ "$REMOTE_TAG_STATUS" == "2" ]]; then
  echo "error: could not read remote tag ${TAG} from ${GIT_REMOTE}" >&2
  exit 1
fi
if [[ "$REMOTE_TAG_STATUS" == "0" && "$REMOTE_TAG_COMMIT" != "$CANDIDATE" ]]; then
  echo "error: remote tag ${TAG} names ${REMOTE_TAG_COMMIT}, not candidate ${CANDIDATE}; refusing to force-update or publish" >&2
  exit 1
fi

if [[ "$REMOTE_TAG_STATUS" != "0" ]]; then
  "$GIT_BIN" tag -a "$TAG" "$CANDIDATE" -m "$TITLE"
  "$GIT_BIN" push "$GIT_REMOTE" "refs/tags/${TAG}"
  REMOTE_TAG_COMMIT=$(peel_remote_tag) || {
    echo "error: tag ${TAG} was not readable from ${GIT_REMOTE} after push" >&2
    exit 1
  }
  if [[ "$REMOTE_TAG_COMMIT" != "$CANDIDATE" ]]; then
    echo "error: pushed tag ${TAG} peeled to ${REMOTE_TAG_COMMIT}, expected ${CANDIDATE}" >&2
    exit 1
  fi
fi

# Publication never proceeds unless the remote tag already names the candidate.
# --verify-tag is the last line of defence so GitHub cannot invent one.
if [[ "$REMOTE_TAG_COMMIT" != "$CANDIDATE" ]]; then
  echo "error: refusing to publish: remote tag ${TAG} does not name candidate ${CANDIDATE}" >&2
  exit 1
fi

ASSET_NAME=$(basename "$APK")
ASSET_DIR=$(mktemp -d)
RELEASE_JSON=$(mktemp)
RELEASE_VIEW_ERR=$(mktemp)
trap 'rm -f "$RELEASE_JSON" "$RELEASE_VIEW_ERR"; rm -rf "$ASSET_DIR"' EXIT

verify_release_json() {
  python3 - "$RELEASE_JSON" "$TAG" "$TITLE" "$ASSET_NAME" "$CANDIDATE" <<'PY'
import json
import sys

path, expected_tag, expected_title, asset_name, candidate = sys.argv[1:]
with open(path, encoding="utf-8") as handle:
    release = json.load(handle)

if release.get("tagName") != expected_tag:
    print(f"tagName {release.get('tagName')!r} != {expected_tag!r}", file=sys.stderr)
    raise SystemExit(1)
if release.get("name") != expected_title:
    print(f"title {release.get('name')!r} != {expected_title!r}", file=sys.stderr)
    raise SystemExit(1)

target = release.get("targetCommitish") or ""
if target in {"main", "master"}:
    print(f"targetCommitish is {target!r}; candidate is {candidate}", file=sys.stderr)
    raise SystemExit(1)
if target not in {candidate, expected_tag}:
    print(f"targetCommitish {target!r} is not candidate {candidate} or tag {expected_tag}", file=sys.stderr)
    raise SystemExit(1)

assets = release.get("assets") or []
matches = [asset for asset in assets if asset.get("name") == asset_name]
if len(matches) != 1:
    print(f"expected exactly one asset named {asset_name}, found {len(matches)}", file=sys.stderr)
    raise SystemExit(1)
PY
}

download_and_match() {
  rm -rf "$ASSET_DIR"
  mkdir -p "$ASSET_DIR"
  "$GH_BIN" release download "$TAG" --repo "$GH_REPO" --pattern "$ASSET_NAME" --dir "$ASSET_DIR"
  local downloaded="$ASSET_DIR/$ASSET_NAME"
  [[ -f "$downloaded" ]] || { echo "error: downloaded asset missing: $downloaded" >&2; exit 1; }
  local remote_sha
  remote_sha=$(sha256_file "$downloaded")
  if [[ "$remote_sha" != "$LOCAL_SHA" ]]; then
    echo "error: published apk sha256 ${remote_sha} != local ${LOCAL_SHA}; no further mutation" >&2
    exit 1
  fi
  echo "verified apk sha256 ${remote_sha}"
}

if "$GH_BIN" release view "$TAG" --repo "$GH_REPO" --json tagName,name,targetCommitish,isDraft,assets >"$RELEASE_JSON" 2>"$RELEASE_VIEW_ERR"; then
  verify_release_json
  download_and_match
  if python3 -c 'import json,sys; print("1" if json.load(open(sys.argv[1])).get("isDraft") else "0")' "$RELEASE_JSON" | grep -qx 1; then
    "$GH_BIN" release edit "$TAG" --repo "$GH_REPO" --draft=false
    "$GH_BIN" release view "$TAG" --repo "$GH_REPO" --json tagName,name,targetCommitish,isDraft,assets >"$RELEASE_JSON"
    verify_release_json
    download_and_match
  fi
  echo "github release ${TAG} already matches candidate ${CANDIDATE}"
  exit 0
fi

if ! grep -qiE 'not found|could not find|no release found' "$RELEASE_VIEW_ERR"; then
  cat "$RELEASE_VIEW_ERR" >&2
  echo "error: could not read GitHub release ${TAG}" >&2
  exit 1
fi

"$GH_BIN" release create "$TAG" "$APK" \
  --repo "$GH_REPO" \
  --title "$TITLE" \
  --notes-file "$NOTES" \
  --target "$CANDIDATE" \
  --verify-tag \
  --draft

"$GH_BIN" release view "$TAG" --repo "$GH_REPO" --json tagName,name,targetCommitish,isDraft,assets >"$RELEASE_JSON"
verify_release_json
download_and_match

"$GH_BIN" release edit "$TAG" --repo "$GH_REPO" --draft=false

"$GH_BIN" release view "$TAG" --repo "$GH_REPO" --json tagName,name,targetCommitish,isDraft,assets >"$RELEASE_JSON"
verify_release_json
if python3 -c 'import json,sys; raise SystemExit(0 if json.load(open(sys.argv[1])).get("isDraft") in (False, None) else 1)' "$RELEASE_JSON"; then
  :
else
  echo "error: release ${TAG} is still a draft after publish" >&2
  exit 1
fi
download_and_match

FINAL_PEEL=$(peel_remote_tag) || {
  echo "error: remote tag ${TAG} unreadable after publication" >&2
  exit 1
}
if [[ "$FINAL_PEEL" != "$CANDIDATE" ]]; then
  echo "error: remote tag ${TAG} peeled to ${FINAL_PEEL} after publication, expected ${CANDIDATE}" >&2
  exit 1
fi

echo "published ${TAG} at candidate ${CANDIDATE} with apk sha256 ${LOCAL_SHA}"
