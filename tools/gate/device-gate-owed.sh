#!/usr/bin/env bash
# Say, at the end of every fast gate, whether the slow device gate is owed for this change.
#
# 🔴 WHY THIS EXISTS, measured rather than assumed. The device gate was a written rule in two
# places and nothing executed it, so four of the six feature commits before 2.1.2 reached `main`
# red on it. Three of those four DID match the rule's own path list; the path list was never the
# problem. The problem was that the rule had no reader on the path most changes take: a direct
# edit runs `make ci` and nothing else, and only a hopper lode's ship stage ever evaluated a
# green-required device AC.
#
# ⛔ So this is deliberately not a fourth copy of the rule. It is the fast gate naming the slow
# gate's debt, in the one output a session running a direct edit always reads.
#
# ⛔ And it never fails the build. `make ci` is also hopper's ship-stage gate; turning a twenty
# minute emulator run into a hard precondition for every product edit would get routed around,
# which is how the rule got ignored the first time.
set -u

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo_root" || exit 0

# 🔴 Never exit silently. The rsync'd build tree this gate usually runs in has no `.git` at all
# (the sync excludes it), so the first live run printed nothing — and nothing is indistinguishable
# from "not owed", which is the exact reading this script exists to prevent.
if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "device gate: cannot tell from here — no git in this tree. Run it if this change touches"
  echo "             product Kotlin: ANDROID_REMOTE_HOST=<host> make android-host-ci-device"
  exit 0
fi

# What the device gate actually covers is `platform/persistence-room`,
# `platform/pl-transport-conscrypt`, `formfactor/phone` and `apps/phone` — plus every module
# those four compile against. ⛔ A narrow path list cannot express that: `bc13bc0` broke the
# instrumented-test compile from `apps/observer-scaffold/src/main`, which no version of the
# written list named. Product Kotlin is the honest boundary.
gated_re='^(apps|formfactor|platform|core|harness|testing)/.*\.(kt|kts)$|/schemas/.*\.json$'

base=""
for candidate in origin/main main HEAD~1; do
  if git rev-parse --verify --quiet "$candidate" >/dev/null 2>&1; then
    base=$(git merge-base "$candidate" HEAD 2>/dev/null) && [ -n "$base" ] && break
  fi
done
if [ -z "$base" ]; then
  echo "device gate: cannot tell from here — no base revision to diff against."
  exit 0
fi

changed=$( { git diff --name-only "$base" HEAD; git diff --name-only HEAD; git ls-files --others --exclude-standard; } | sort -u )
owed=$(printf '%s\n' "$changed" | grep -E "$gated_re" || true)

if [ -z "$owed" ]; then
  echo "device gate: not owed — this change touches no product Kotlin."
  exit 0
fi

receipt="artifacts/ci-device/$(git rev-parse HEAD)"
if [ -f "$receipt" ] && [ -z "$(git status --porcelain --untracked-files=normal)" ]; then
  echo "device gate: already green at this revision ($receipt)."
  exit 0
fi

count=$(printf '%s\n' "$owed" | wc -l | tr -d ' ')
echo ""
echo "=============================================================================="
echo " DEVICE GATE OWED — $count changed file(s) are product Kotlin."
echo ""
printf '%s\n' "$owed" | head -8 | sed 's/^/   /'
[ "$count" -gt 8 ] && echo "   … and $((count - 8)) more"
echo ""
echo " A green 'make ci' is not evidence the instrumented suites pass: core modules"
echo " compile against the host JDK, so a host-JDK API missing on Android, and any"
echo " instrumented test written but never run, both ship green through this gate."
echo ""
echo "   ANDROID_REMOTE_HOST=suze.local make android-host-ci-device   (from a checkout)"
echo "   make ci-device                                              (on the build box)"
echo "=============================================================================="
echo ""
exit 0
