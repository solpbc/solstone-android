#!/usr/bin/env bash
# Cut the [Unreleased] section of CHANGELOG.md to a versioned release section.
# Renames "## [Unreleased]" -> "## [<version>] - <date>" and re-adds a fresh empty
# "## [Unreleased]" above it. Keep-a-Changelog style. Idempotent guard: refuses if
# the version section already exists.
#
# Usage: tools/release/changelog-cut.sh <version> [YYYY-MM-DD]
set -euo pipefail

VERSION="${1:?usage: changelog-cut.sh <version> [YYYY-MM-DD]}"
DATE="${2:-$(date +%F)}"
CHANGELOG="${CHANGELOG:-CHANGELOG.md}"

test -f "$CHANGELOG" || { echo "no $CHANGELOG" >&2; exit 1; }
grep -qE '^## \[Unreleased\]' "$CHANGELOG" || { echo "no [Unreleased] section in $CHANGELOG" >&2; exit 1; }
if grep -qE "^## \[${VERSION//./\\.}\]" "$CHANGELOG"; then
  echo "version $VERSION already in $CHANGELOG" >&2; exit 1
fi

awk -v ver="$VERSION" -v date="$DATE" '
  !done && /^## \[Unreleased\]/ {
    print "## [Unreleased]"
    print ""
    print "## [" ver "] - " date
    done = 1
    next
  }
  { print }
' "$CHANGELOG" > "$CHANGELOG.tmp" && mv "$CHANGELOG.tmp" "$CHANGELOG"

echo "cut [Unreleased] -> [$VERSION] - $DATE in $CHANGELOG"
