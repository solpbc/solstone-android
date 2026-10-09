#!/usr/bin/env bash
# Say, at the end of every fast gate, whether the slow device gate is owed for this change.
#
# 🔴 WHY THIS EXISTS, measured rather than assumed. The device gate was a written rule in two
# places and nothing executed it. Four commits reached `main` carrying a red device gate, and all
# four were found only when a release finally ran it. Three of the four already MATCHED the rule's
# own path list, so the list was never the problem: two of the four came through the automated
# build pipeline, whose ship stage did not evaluate the device gate at all (one says so in its
# own commit message), and two were hand-landed, where no ship stage exists to evaluate anything.
#
# ⛔ Do not restate that as a ratio. An earlier phrasing here counted it against "the six most
# recent feature commits" and that population does not reconstruct from the log.
#
# ⛔ So this is deliberately not a fourth copy of the rule. It is the fast gate naming the slow
# gate's debt, in the one output every session running the fast gate already reads.
#
# ⛔ And it never fails the build. `make ci` is also the automated pipeline's ship-stage gate;
# turning a twenty minute emulator run into a hard precondition for every product edit would get
# routed around, which is how the rule got ignored the first time.
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

base="${DEVICE_GATE_BASE:-}"
for candidate in origin/main main HEAD~1; do
  [ -n "$base" ] && break
  if git rev-parse --verify --quiet "$candidate" >/dev/null 2>&1; then
    base=$(git merge-base "$candidate" HEAD 2>/dev/null) && [ -n "$base" ] && break
  fi
done
if [ -z "$base" ]; then
  echo "device gate: cannot tell from here — no base revision to diff against."
  exit 0
fi

# 🔴 Once a change is pushed, the merge base with origin/main IS HEAD, so the diff is empty and
# "not owed" would be a confident wrong answer for a change that owed both gates.
if [ -z "${DEVICE_GATE_BASE:-}" ] && [ "$base" = "$(git rev-parse HEAD)" ] \
  && [ -z "$(git status --porcelain --untracked-files=normal)" ]; then
  echo "device gate: cannot tell from here — HEAD is already on origin/main, so there is no"
  echo "             local change to read. Name the revision before the change:"
  echo "             DEVICE_GATE_BASE=<rev> sh tools/gate/device-gate-owed.sh"
  exit 0
fi

changed=$( { git diff --name-only "$base" HEAD; git diff --name-only HEAD; git ls-files --others --exclude-standard; } | sort -u )
owed=$(printf '%s\n' "$changed" | grep -E "$gated_re" || true)

# 🔴 The paired send: a real phone pairs with a real disposable journal and a segment over the
# 1 MiB send credit has to arrive. Nothing in this repo's gates sends a segment, so 2.1.13 passed
# all of them and sent nothing, and every release before 2.1.9 refused real-sized segments on the
# direct door. It is operator-run, against a bench phone, so this only names the debt — for any
# change on the path a segment travels, or to what the app is allowed to do.
send_re='^core/(model|sources|segment|spool|queue|pl|identity|observer)/|^platform/(pl-transport-conscrypt|identity-file|work|persistence-room|audio)/|^apps/phone/(build\.gradle\.kts|src/main/AndroidManifest\.xml)$|^apps/phone/src/main/.*(Pair|Sync|Journal|Transport|Ingest)[A-Za-z]*\.kt$'
send_owed=$(printf '%s\n' "$changed" | grep -E "$send_re" || true)
paired_send_report() {
  [ -n "$send_owed" ] || return 0
  echo ""
  echo "=============================================================================="
  echo " PAIRED SEND OWED — this change touches the path a segment travels:"
  printf '%s\n' "$send_owed" | head -5 | sed 's/^/   /'
  echo " Before it ships, an operator runs the paired-send gate on a bench phone: pair to"
  echo " a disposable journal, direct and through the relay, and prove a segment over"
  echo " 1 MiB from this exact build arrives. No gate in this repo sends one."
  echo "=============================================================================="
}

if [ -z "$owed" ]; then
  echo "device gate: not owed — this change touches no product Kotlin."
  paired_send_report
  exit 0
fi

receipt="artifacts/ci-device/$(git rev-parse HEAD)"
if [ -f "$receipt" ] && [ -z "$(git status --porcelain --untracked-files=normal)" ]; then
  echo "device gate: already green at this revision ($receipt)."
  paired_send_report
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
echo " A green 'make ci' is not evidence the instrumented suites pass: it never runs"
echo " them, so an instrumented test written but never run, and any behaviour only the"
echo " Android runtime shows, ship green through this gate. (A host-JDK API that Android"
echo " lacks is now caught here, by checkAndroidApiSurface.)"
echo ""
echo "   ANDROID_REMOTE_HOST=<build-host> make android-host-ci-device   (from a checkout)"
echo "   make ci-device                                                (on the build box)"
echo "=============================================================================="
paired_send_report
echo ""
exit 0
