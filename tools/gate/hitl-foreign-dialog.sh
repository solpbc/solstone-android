#!/usr/bin/env bash
# Is a dialog that is not ours sitting on top of the app the HITL gate is driving?
#
# 🔴 WHY. A Samsung system dialog ("Process android.process.media isn't responding") covered the
# app during a frozen hardware gate and failed a Maestro assertion. The gate reported FAILED,
# which is a claim about the build — and it was false. A gate that cannot tell "the build is
# broken" from "something else was on screen" spends its own credibility the first time the
# second thing happens, because the next red is read as the same noise.
#
# Prints one word: `none`, or the offending package. Exit 0 either way; the caller classifies.
set -u
serial="${1:?usage: hitl-foreign-dialog.sh <serial>}"
app="${2:-app.solstone.observer.phone}"

focus=$(adb -s "$serial" shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' || true)

# An ANR or crash dialog is owned by the system UI, never by the app under test. ⛔ Do not treat
# "focus is not the app" as the test: a permission prompt is also not the app, and the un-granted
# leg drives those on purpose.
case "$focus" in
  *"Application Not Responding"*|*"isn't responding"*|*"has stopped"*|*"keeps stopping"*)
    printf 'system-dialog\n'; exit 0 ;;
esac

anr=$(adb -s "$serial" shell dumpsys activity 2>/dev/null | grep -m1 -iE 'ANR in |mShowingAlertDialog=true' || true)
if [ -n "$anr" ]; then
  case "$anr" in
    *"$app"*) printf 'none\n'; exit 0 ;;   # our own ANR is a real finding, not foreign noise
    *) printf 'system-dialog\n'; exit 0 ;;
  esac
fi

printf 'none\n'
