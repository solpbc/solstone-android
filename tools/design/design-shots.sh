#!/usr/bin/env bash
# One screenshot per SURFACE of the phone shell, at one appearance.
#
# The sibling of the HITL Maestro flow, which asserts reachability across one journey. This one
# asserts nothing about behaviour: it opens each surface directly and photographs it, so a design
# pass can look at every screen in the app in one run. Six of the nine defects the iOS shell pass
# found were invisible in source and obvious in a screenshot.
#
# Reaching a surface by synthetic taps is what makes a capture harness lie: a tap that misses lands
# on the previous surface and the capture still succeeds. So the shell takes the surface by name
# (a debuggable-build-only intent extra, see PhoneShellActivity.captureSurfaceFromIntent) and this
# script ASSERTS the surface it asked for before keeping the shot.
#
# Usage:
#   tools/design/design-shots.sh --out DIR [--serial SERIAL] [--night yes|no]
#                                [--font-scale N] [--size WxH --density D] [--only NAME]
#
# Every flag has a default; with none it captures the whole app on the bench Galaxy A36 in whatever
# appearance the device is already in.

set -euo pipefail

SERIAL="${ANDROID_HITL_SERIAL:-RZGL11XCS9D}"
PKG="app.solstone.observer.phone"
ACTIVITY="$PKG/$PKG.PhoneShellActivity"
OUT=""
NIGHT=""
FONT_SCALE=""
WM_SIZE=""
WM_DENSITY=""
ONLY=""
SETTLE="${DESIGN_SHOTS_SETTLE:-2.5}"

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --night) NIGHT="$2"; shift 2 ;;
    --font-scale) FONT_SCALE="$2"; shift 2 ;;
    --size) WM_SIZE="$2"; shift 2 ;;
    --density) WM_DENSITY="$2"; shift 2 ;;
    --only) ONLY="$2"; shift 2 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
done

[ -n "$OUT" ] || { echo "design-shots: --out DIR is required" >&2; exit 2; }

# `</dev/null` is load-bearing, not tidiness: `adb shell` reads stdin, and inside the capture loop
# that stdin is the surface list — so without it the first `adb shell` eats every remaining surface
# and the run reports a clean success having captured exactly one screen.
adb() { command adb -s "$SERIAL" "$@" </dev/null; }

# Match the serial LITERALLY. The iOS instrument interpolated a device name into an awk regex and
# `iPad Pro 13-inch (M4)` had its `(M4)` read as a capture group, so a present device was reported
# absent. `-F` + a full-field compare cannot do that.
if ! command adb devices | awk -F'\t' -v s="$SERIAL" '$1 == s && $2 == "device" { found = 1 } END { exit !found }'; then
  echo "design-shots: device $SERIAL is not attached and ready." >&2
  command adb devices -l >&2
  exit 1
fi

mkdir -p "$OUT"

# name | route key (empty = home) | extra flags | assertion
#
# An assertion is text that must appear in the UI tree, OR `window:NAME` to require a
# window of that name owned by this app. The status pane needs the second form: it is a
# non-focusable Popup, which lives in its own window, and `uiautomator dump` only ever
# dumps the FOCUSED window — so a correctly-rendered pane reports as absent. That is the
# instrument being wrong, not the app, and it is the shape worth naming: this assertion
# failed for three straight rebuilds while the screenshot showed the pane drawing fine.
SURFACES=$(cat <<'EOF'
home||--ez solstone.design.shelf false|good
status||--ez solstone.design.status true|window:Pop-Up Window
shelf||--ez solstone.design.shelf true|settings
import|import||import
add-more|add-more||add more
source-audio|sd/audio||audio
source-location|sd/location||location
source-camera|sd/camera||camera
your-journal|your-journal||your journal
this-device|this-device||this device
notifications|notifications||notifications
help|help||help
about-solstone|about-solstone||about solstone
licences|licences||licenses
EOF
)

restore() {
  [ -n "$FONT_SCALE" ] && adb shell settings put system font_scale 1.0 >/dev/null 2>&1 || true
  [ -n "$WM_SIZE" ] && adb shell wm size reset >/dev/null 2>&1 || true
  [ -n "$WM_DENSITY" ] && adb shell wm density reset >/dev/null 2>&1 || true
  return 0
}
trap restore EXIT

[ -n "$NIGHT" ] && adb shell cmd uimode night "$NIGHT" >/dev/null
[ -n "$FONT_SCALE" ] && adb shell settings put system font_scale "$FONT_SCALE" >/dev/null
[ -n "$WM_SIZE" ] && adb shell wm size "$WM_SIZE" >/dev/null
[ -n "$WM_DENSITY" ] && adb shell wm density "$WM_DENSITY" >/dev/null

adb shell input keyevent KEYCODE_WAKEUP >/dev/null
adb shell settings put system screen_off_timeout 1800000 >/dev/null

failed=0
captured=0
while IFS='|' read -r name route extra expect; do
  [ -n "$name" ] || continue
  if [ -n "$ONLY" ] && [ "$ONLY" != "$name" ]; then continue; fi

  # Force-stop first, every time. `am start` against an already-running task FOREGROUNDS it — the
  # capture then silently shows the previous surface. An iOS accessibility pass "of home" returned
  # the help pane before this was caught.
  adb shell am force-stop "$PKG" >/dev/null
  route_extra=""
  [ -n "$route" ] && route_extra="--es solstone.design.route $route"
  # shellcheck disable=SC2086
  adb shell am start -W -n "$ACTIVITY" $route_extra $extra >/dev/null
  sleep "$SETTLE"

  # Assert the surface, so a capture that silently landed somewhere else fails loudly.
  case "$expect" in
    window:*)
      want="${expect#window:}"
      tree=$(adb shell dumpsys window windows 2>/dev/null || true)
      # The window must belong to THIS app: a system pop-up of the same name would
      # otherwise satisfy the check while the app showed nothing.
      if ! printf '%s' "$tree" | grep -A2 -F -- "$want" | grep -qF -- "$PKG"; then
        echo "design-shots: FAILED to reach '$name' — no '$want' window owned by $PKG" >&2
        printf '%s' "$tree" > "$OUT/$name.FAILED.txt"
        failed=$((failed + 1))
        continue
      fi
      ;;
    *)
      adb shell uiautomator dump /sdcard/design-shot.xml >/dev/null 2>&1 || true
      tree=$(adb shell cat /sdcard/design-shot.xml 2>/dev/null || true)
      if ! printf '%s' "$tree" | grep -qiF -- "$expect"; then
        echo "design-shots: FAILED to reach '$name' — expected text '$expect' is not on screen" >&2
        printf '%s' "$tree" > "$OUT/$name.FAILED.xml"
        failed=$((failed + 1))
        continue
      fi
      ;;
  esac

  adb shell screencap -p /sdcard/design-shot.png
  adb pull /sdcard/design-shot.png "$OUT/$name.png" >/dev/null
  captured=$((captured + 1))
  echo "  ✓ $name"
done <<< "$SURFACES"

adb shell am force-stop "$PKG" >/dev/null

echo "design-shots: $captured captured into $OUT, $failed failed"
[ "$failed" -eq 0 ]
