#!/usr/bin/env bash
# Print one CHANGELOG.md release section verbatim — the "## [<version>] - <date>"
# header line plus its body, up to (not including) the next "## [" header. This is
# the body for the GitHub release notes; the solstone.app releases page strips the
# leading "## [version] - date" heading and renders the rest, so the heading must
# be included here.
#
# Usage: tools/release/changelog-notes.sh <version>
set -euo pipefail

VERSION="${1:?usage: changelog-notes.sh <version>}"
CHANGELOG="${CHANGELOG:-CHANGELOG.md}"
test -f "$CHANGELOG" || { echo "no $CHANGELOG" >&2; exit 1; }

out="$(awk -v ver="$VERSION" '
  /^## \[/ {
    if (inblk) exit                       # next section header -> stop
    if ($0 ~ "^## \\[" ver "\\]") { inblk = 1; print; next }
  }
  inblk { print }
' "$CHANGELOG")"

test -n "$out" || { echo "no section for version $VERSION in $CHANGELOG" >&2; exit 1; }
# Trim leading/trailing blank lines.
printf '%s\n' "$out" | sed -e '/./,$!d' | tac | sed -e '/./,$!d' | tac
