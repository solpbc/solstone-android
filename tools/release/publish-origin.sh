#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
#
# Publish an exact signed APK to the release origin,
# https://updates.solstone.app/solstone-android/{lane}/{version}/, writing the
# {lane}/latest pointer only after the artifact has landed and been read back.
#
# The origin is where owners fetch bytes. GitHub is an optional mirror: this
# script never contacts GitHub and never requires gh, so a GitHub outage cannot
# stop or delay an origin publish.
#
# It carries the same candidate binding as tools/release/github-release.sh —
# an explicit full-SHA candidate, a clean tree, ancestry on the release branch,
# a remote annotated tag that peels to the candidate, and a digest read back
# from the published copy — plus two bindings the mirror does not have and the
# origin needs, because the origin publishes bytes rather than a tag: the APK's
# own versionName/versionCode must equal what the candidate commit declares,
# and its signer certificate must equal the pinned release key.
#
# One check of github-release.sh's is deliberately NOT carried over, and it is
# named here so the absence does not read as an oversight: HEAD == CANDIDATE.
# That check exists there because that script mints the tag out of the local
# repository. This script mints no ref at all; it requires the remote tag to
# already name the candidate, which is a stronger statement about the release
# than where a worktree happens to be standing. Requiring it here would also
# make it impossible to publish a release cut before this script existed, which
# is exactly the 2.1.0 case. The clean-tree check stays.
#
# Usage:
#   tools/release/publish-origin.sh \
#     --version 2.1.0 \
#     --candidate <full 40-hex sha> \
#     --apk artifacts/phone-real-release.apk \
#     [--lane release|staging|dev] [--dry-run]

set -euo pipefail

umask 077
export LC_ALL=C

PRODUCT="solstone-android"
BUCKET="${SOLSTONE_ORIGIN_BUCKET:-solstone-updates}"
ORIGIN_URL="${SOLSTONE_ORIGIN_URL:-https://updates.solstone.app}"

GIT_BIN="${GIT_BIN:-git}"
WRANGLER_BIN="${WRANGLER_BIN:-wrangler}"
GIT_REMOTE="${GIT_REMOTE:-origin}"
RELEASE_BRANCH="${RELEASE_BRANCH:-main}"
PYTHON_BIN="${PYTHON_BIN:-python3}"

die() {
    printf 'release origin publisher: %s\n' "$1" >&2
    exit 1
}

usage() {
    echo "usage: tools/release/publish-origin.sh --version x.y.z --candidate <full 40-hex sha> --apk <apk> [--lane release|staging|dev] [--dry-run]" >&2
    exit 2
}

version=""
candidate=""
apk=""
lane="release"
dry_run=false
while (($# > 0)); do
    case "$1" in
        --version)
            (($# >= 2)) || usage
            version="$2"
            shift 2
            ;;
        --candidate)
            (($# >= 2)) || usage
            candidate="$2"
            shift 2
            ;;
        --apk)
            (($# >= 2)) || usage
            apk="$2"
            shift 2
            ;;
        --lane)
            (($# >= 2)) || usage
            lane="$2"
            shift 2
            ;;
        --dry-run)
            dry_run=true
            shift
            ;;
        *)
            usage
            ;;
    esac
done
[[ -n "$version" && -n "$candidate" && -n "$apk" ]] || usage

# Lane vocabulary is the journal's: exactly release, staging, dev. Only
# `release` is published today (Android's pre-release channel is Firebase App
# Distribution, not this origin), but the vocabulary stays open so a staging
# candidate never needs a script change to reach the origin.
case "$lane" in
    release | staging | dev) ;;
    *) die "lane-invalid: $lane" ;;
esac

# The release names its candidate explicitly. Never accept a short SHA, a
# branch, or a tag name — those are how a tip gets inferred.
[[ "$candidate" =~ ^[0-9a-fA-F]{40}$ ]] ||
    die "candidate-invalid: --candidate must be a full 40-hex commit SHA (got ${candidate:-empty})"
candidate="${candidate,,}"

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    die "version-invalid: --version must be strict SemVer (got $version)"

required_tools=("$GIT_BIN" "$PYTHON_BIN" mktemp realpath rm sha256sum)
$dry_run || required_tools+=("$WRANGLER_BIN")
for tool in "${required_tools[@]}"; do
    command -v "$tool" >/dev/null 2>&1 ||
        die "required release tool is unavailable: $tool"
done

repo_root="$("$GIT_BIN" rev-parse --show-toplevel 2>/dev/null)" ||
    die "current directory is not a Git worktree"
repo_root="$(realpath "$repo_root")"

apk_facts="$repo_root/tools/release/apk_facts.py"
pinned_key_file="$repo_root/tools/release/release-signing-key.sha256"
[[ -f "$apk_facts" && ! -L "$apk_facts" ]] || die "apk-facts reader is missing: $apk_facts"
[[ -f "$pinned_key_file" && ! -L "$pinned_key_file" ]] ||
    die "release signing key pin is missing: $pinned_key_file"

[[ "$apk" == /* ]] || apk="$PWD/$apk"
[[ -f "$apk" && ! -L "$apk" ]] || die "apk-missing: $apk must be a regular file"
apk="$(realpath "$apk")"

# --- the candidate binding -------------------------------------------------

status="$("$GIT_BIN" -C "$repo_root" status --porcelain=v1 --untracked-files=normal)" ||
    die "source-unbound: could not inspect the source tree"
[[ -z "$status" ]] || {
    printf 'release origin publisher: source-unbound: refusing to publish from a dirty source tree\n' >&2
    printf '%s\n' "$status" >&2
    exit 1
}

"$GIT_BIN" -C "$repo_root" fetch "$GIT_REMOTE" "$RELEASE_BRANCH" >/dev/null 2>&1 ||
    die "source-unbound: could not fetch $GIT_REMOTE/$RELEASE_BRANCH"
"$GIT_BIN" -C "$repo_root" merge-base --is-ancestor "$candidate" FETCH_HEAD ||
    die "source-unbound: candidate $candidate is not an ancestor of $GIT_REMOTE/$RELEASE_BRANCH"

tag="v$version"

# Annotated tags advertise the tag object at refs/tags/NAME and the commit at
# refs/tags/NAME^{}. Never compare an unpeeled tag-object SHA to a commit.
peel_remote_tag() {
    local output peeled direct
    output="$("$GIT_BIN" -C "$repo_root" ls-remote --tags "$GIT_REMOTE" \
        "refs/tags/${tag}" "refs/tags/${tag}^{}")" || return 2
    peeled="$(printf '%s\n' "$output" | awk -v ref="refs/tags/${tag}^{}" '$2 == ref { print $1; exit }')"
    direct="$(printf '%s\n' "$output" | awk -v ref="refs/tags/${tag}" '$2 == ref { print $1; exit }')"
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

# The release lane requires the tag to ALREADY name the candidate. This
# publisher never creates, moves or force-updates a tag: the tag is the
# release's identity and github-release.sh is the only thing that mints it.
# The proof lanes deliberately do not require one, which is what lets a
# candidate be staged before it is a release.
if [[ "$lane" == "release" ]]; then
    remote_tag_commit=""
    remote_tag_status=0
    remote_tag_commit="$(peel_remote_tag)" || remote_tag_status=$?
    if ((remote_tag_status == 2)); then
        die "source-unbound: could not read remote tag $tag from $GIT_REMOTE"
    fi
    if ((remote_tag_status != 0)); then
        die "source-unbound: remote tag $tag does not exist; the release lane publishes a tagged release only"
    fi
    [[ "$remote_tag_commit" == "$candidate" ]] ||
        die "source-unbound: remote tag $tag names $remote_tag_commit, not candidate $candidate"
fi

# --- the artifact binding --------------------------------------------------

# What the candidate commit declares. Read from the commit, never the worktree:
# a clean tree proves HEAD is clean, not that HEAD is the candidate.
gradle_at_candidate="$("$GIT_BIN" -C "$repo_root" show "$candidate:apps/phone/build.gradle.kts")" ||
    die "source-unbound: candidate $candidate has no apps/phone/build.gradle.kts"
declared_version_name="$(printf '%s\n' "$gradle_at_candidate" |
    sed -nE 's/.*versionName = "([^"]+)".*/\1/p' | head -1)"
declared_version_code="$(printf '%s\n' "$gradle_at_candidate" |
    sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' | head -1)"
[[ -n "$declared_version_name" && -n "$declared_version_code" ]] ||
    die "source-unbound: candidate $candidate declares no versionName/versionCode"
[[ "$declared_version_name" == "$version" ]] ||
    die "version-mismatch: candidate $candidate declares versionName $declared_version_name, not $version"

# What the artifact itself is. This is the binding the mirror does not have:
# github-release.sh publishes whatever APK it is handed.
facts="$("$PYTHON_BIN" "$apk_facts" "$apk")" ||
    die "artifact-unreadable: could not read release facts from $apk"

fact() {
    printf '%s' "$facts" | "$PYTHON_BIN" -c \
        'import json,sys; value=json.load(sys.stdin)[sys.argv[1]]; print(value if not isinstance(value, list) else "\n".join(value))' "$1"
}

apk_version_name="$(fact version_name)"
apk_version_code="$(fact version_code)"
apk_package="$(fact package)"
apk_certificates="$(fact signer_cert_sha256)"

[[ "$apk_package" == "app.solstone.observer.phone" ]] ||
    die "artifact-mismatch: apk package is $apk_package, not app.solstone.observer.phone"
[[ "$apk_version_name" == "$version" ]] ||
    die "artifact-mismatch: apk versionName is $apk_version_name, not $version"
[[ "$apk_version_code" == "$declared_version_code" ]] ||
    die "artifact-mismatch: apk versionCode is $apk_version_code, but candidate $candidate declares $declared_version_code"

# The signer certificate is the only thing an owner's device actually pins:
# Android refuses an update signed by a different key. A release signed by
# anything but the pinned key is not our release.
#
# ⚠ Scope of this check, stated rather than implied: it reads the certificate
# out of the APK Signing Block and compares its identity. It does NOT verify
# the signature over the archive's own bytes. It therefore catches every
# accident that actually happens here — an unsigned build from a box without
# the keystore env, a debug-signed APK, the wrong key — but a deliberately
# forged block would pass it. `apksigner verify --print-certs` on the build
# box, which does verify, stays the authoritative signature gate; this is the
# gate that can run where wrangler is authenticated.
pinned_key="$(grep -vE '^\s*(#|$)' "$pinned_key_file" | head -1 | tr -d '[:space:]' | tr 'A-F' 'a-f')"
[[ "$pinned_key" =~ ^[0-9a-f]{64}$ ]] ||
    die "key-pin-invalid: $pinned_key_file must hold one 64-hex certificate SHA-256"
certificate_count="$(printf '%s\n' "$apk_certificates" | grep -c .)"
((certificate_count == 1)) ||
    die "signature-invalid: expected exactly one signer certificate, found $certificate_count"
[[ "$apk_certificates" == "$pinned_key" ]] ||
    die "signature-invalid: apk signer certificate $apk_certificates is not the pinned release key $pinned_key"

local_digest="$(sha256sum "$apk" | awk '{print $1}')"

printf 'publishing %s %s (versionCode %s) to the %s lane of %s\n' \
    "$PRODUCT" "$version" "$apk_version_code" "$lane" "$ORIGIN_URL"
printf '  candidate   %s\n' "$candidate"
printf '  signer      %s\n' "$apk_certificates"
printf '  apk sha256  %s\n' "$local_digest"

# --- publication -----------------------------------------------------------

# Owner-facing name. The build output is called phone-real-release.apk, which
# is a build-tree name and not what someone should find in their Downloads.
# The bytes are identical to the mirror's asset; the digest is what binds.
asset_name="$PRODUCT-$version.apk"
checksums_name="SHA256SUMS"

stage_root="$(mktemp -d "${TMPDIR:-/tmp}/$PRODUCT-publish-origin.XXXXXX")"
cleanup() {
    rm -rf -- "$stage_root"
}
trap cleanup EXIT

printf '%s  %s\n' "$local_digest" "$asset_name" >"$stage_root/$checksums_name"
checksums_digest="$(sha256sum "$stage_root/$checksums_name" | awk '{print $1}')"

# Returns 0 when the object is present (bytes land in $2), 1 when it is
# genuinely absent, and fails closed on every other outcome. An unreachable
# origin must never read as an empty one.
remote_get() {
    local key="$1" destination="$2" log status
    log="$stage_root/wrangler.log"
    set +e
    "$WRANGLER_BIN" r2 object get "$BUCKET/$key" --remote --file "$destination" >"$log" 2>&1
    status=$?
    set -e
    if ((status == 0)); then
        return 0
    fi
    if grep -qF 'The specified key does not exist' "$log"; then
        rm -f "$destination"
        return 1
    fi
    cat "$log" >&2
    die "origin-unreachable: could not read $key"
}

remote_put() {
    local key="$1" file="$2" content_type="$3" cache_control="$4"
    "$WRANGLER_BIN" r2 object put "$BUCKET/$key" \
        --file "$file" \
        --content-type "$content_type" \
        --cache-control "$cache_control" \
        --remote >/dev/null ||
        die "origin-unreachable: could not write $key"
}

if [[ "$lane" == "dev" ]]; then
    object_cache_control="no-cache"
else
    object_cache_control="public, max-age=31536000, immutable"
fi

publish_object() {
    local name="$1" file="$2" content_type="$3" expected_digest="$4"
    local key="$PRODUCT/$lane/$version/$name"

    if $dry_run; then
        printf '  would publish %s/%s\n' "$ORIGIN_URL" "$key"
        return 0
    fi

    local remote_file="$stage_root/remote-object"
    if remote_get "$key" "$remote_file"; then
        if cmp -s "$remote_file" "$file"; then
            printf '  present  %s\n' "$key"
            rm -f "$remote_file"
            return 0
        fi
        # release and staging versioned objects are immutable. No R2 bucket
        # lock rule covers this prefix, so the store will not refuse for us and
        # wrangler overwrites silently. The refusal is the publisher's.
        [[ "$lane" == "dev" ]] ||
            die "object-immutable: $key already exists with different bytes"
        rm -f "$remote_file"
    fi

    remote_put "$key" "$file" "$content_type" "$object_cache_control"

    # Read the published copy back and re-hash it. A put that reported success
    # is a claim about wrangler, not about the bytes an owner will fetch.
    local readback="$stage_root/readback"
    remote_get "$key" "$readback" ||
        die "digest-mismatch: $key is absent immediately after publication"
    local published_digest
    published_digest="$(sha256sum "$readback" | awk '{print $1}')"
    rm -f "$readback"
    [[ "$published_digest" == "$expected_digest" ]] ||
        die "digest-mismatch: $key published as $published_digest, expected $expected_digest"
    printf '  put      %s (sha256 %s verified)\n' "$key" "$published_digest"
}

publish_object "$asset_name" "$apk" "application/vnd.android.package-archive" "$local_digest"
publish_object "$checksums_name" "$stage_root/$checksums_name" "text/plain; charset=utf-8" "$checksums_digest"

# Dot-separated numeric segments; non-numeric segments compare as strings.
# Same ordering the journal's publisher uses, so latest advances identically.
version_is_not_older() {
    local left="$1" right="$2"
    local -a left_parts right_parts
    IFS='.' read -r -a left_parts <<<"$left"
    IFS='.' read -r -a right_parts <<<"$right"
    local count=${#left_parts[@]}
    ((${#right_parts[@]} > count)) && count=${#right_parts[@]}
    local index l r
    for ((index = 0; index < count; index++)); do
        l="${left_parts[index]:-}"
        r="${right_parts[index]:-}"
        [[ "$l" == "$r" ]] && continue
        [[ -z "$l" ]] && return 1
        [[ -z "$r" ]] && return 0
        if [[ "$l" =~ ^[0-9]+$ && "$r" =~ ^[0-9]+$ ]]; then
            ((10#$l > 10#$r)) && return 0
            return 1
        fi
        [[ "$l" > "$r" ]] && return 0
        return 1
    done
    return 0
}

latest_key="$PRODUCT/$lane/latest"
if $dry_run; then
    printf '  would advance %s/%s to version=%s\n' "$ORIGIN_URL" "$latest_key" "$version"
    exit 0
fi

latest_file="$stage_root/remote-latest"
advance=true
existing_version=""
if remote_get "$latest_key" "$latest_file"; then
    existing_body="$(cat "$latest_file")"
    [[ "$existing_body" =~ ^version=[^[:space:]/]+$ ]] ||
        die "latest-invalid: $latest_key is not a single version= line"
    existing_version="${existing_body#version=}"
    if ! version_is_not_older "$version" "$existing_version"; then
        advance=false
    fi
fi

if $advance; then
    printf 'version=%s\n' "$version" >"$stage_root/latest"
    remote_put "$latest_key" "$stage_root/latest" "text/plain; charset=utf-8" "no-cache"
    printf '  put      %s (version=%s)\n' "$latest_key" "$version"
else
    printf '  held     %s (already at version=%s)\n' "$latest_key" "$existing_version"
fi

printf 'published %s %s to %s/%s/%s/%s/\n' \
    "$PRODUCT" "$version" "$ORIGIN_URL" "$PRODUCT" "$lane" "$version"
