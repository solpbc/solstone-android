#!/bin/sh
# Build every app's launcher icon ladder from the brand app-icon masters.
#
# Usage:  BRAND_DIR=/path/to/brand sh scripts/build-launcher-icons.sh
#
# Three families per app, each rendered straight from the vector at its exact
# pixel size — never downsampled from one larger raster:
#
#   ic_launcher.png             the legacy square icon, from the cream master
#   ic_launcher_round.png       the same master clipped to a circle, for the
#                               pre-API-26 launchers that ask for a round icon
#   ic_launcher_foreground.png  the adaptive foreground layer, from the
#                               transparent master; the background layer is the
#                               ic_launcher_background colour, not an image
#
# The adaptive foreground is drawn at 76dp inside the 108dp canvas — a hair over
# the 72dp safe zone, so a circular mask crops only ray tips. That ratio is the
# one the shipped icon set uses; changing it re-frames every launcher icon on
# every device, so it is a design decision rather than a build knob.
#
# Apps are discovered by the adaptive-icon descriptor they declare, so adding an
# app with a mipmap-anydpi-v26/ic_launcher.xml picks it up with no edit here.
#
# Output is deterministic: the same brand source and the same librsvg version
# produce byte-identical PNGs, so re-running on an unchanged source leaves the
# tree unchanged.
#
# Requires:
#   rsvg-convert (librsvg)   apt: librsvg2-bin   brew: librsvg

set -eu

# density : legacy px : adaptive canvas px
DENSITIES="mdpi:48:108 hdpi:72:162 xhdpi:96:216 xxhdpi:144:324 xxxhdpi:192:432"

# numerator/denominator of the foreground mark's share of the adaptive canvas
FG_MARK_DP=76
FG_CANVAS_DP=108

CREAM_MASTER="app-icon/app-icon-cream.svg"
TRANSPARENT_MASTER="app-icon/app-icon-transparent.svg"

if [ -z "${BRAND_DIR:-}" ]; then
    echo "brand: BRAND_DIR is required — point it at your brand asset directory" >&2
    exit 1
fi
if [ ! -d "$BRAND_DIR" ]; then
    echo "brand: BRAND_DIR=$BRAND_DIR not found" >&2
    exit 1
fi
if ! command -v rsvg-convert >/dev/null 2>&1; then
    echo "brand: rsvg-convert (librsvg) not found — apt install librsvg2-bin, or brew install librsvg" >&2
    exit 1
fi
for master in "$CREAM_MASTER" "$TRANSPARENT_MASTER"; do
    if [ ! -f "$BRAND_DIR/$master" ]; then
        echo "brand: missing source $BRAND_DIR/$master" >&2
        exit 1
    fi
done

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM

# librsvg resolves an <image href> relative to the referring file and refuses to
# climb out of its directory, so the round mask is assembled beside a copy of
# the master rather than pointed at the brand tree.
cp "$BRAND_DIR/$CREAM_MASTER" "$work/cream.svg"
cat > "$work/cream-round.svg" <<'ROUND'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
  <defs><clipPath id="round"><circle cx="512" cy="512" r="512"/></clipPath></defs>
  <g clip-path="url(#round)">
    <image href="cream.svg" x="0" y="0" width="1024" height="1024"/>
  </g>
</svg>
ROUND

apps=""
for descriptor in apps/*/src/main/res/mipmap-anydpi-v26/ic_launcher.xml; do
    [ -f "$descriptor" ] || continue
    res=${descriptor%/mipmap-anydpi-v26/ic_launcher.xml}
    apps="$apps $res"
done
if [ -z "$apps" ]; then
    echo "brand: no adaptive-icon descriptors found under apps/*/src/main/res" >&2
    exit 1
fi

for res in $apps; do
    app=${res#apps/}
    app=${app%%/*}
    for entry in $DENSITIES; do
        density=$(echo "$entry" | cut -d: -f1)
        legacy=$(echo "$entry" | cut -d: -f2)
        canvas=$(echo "$entry" | cut -d: -f3)
        dir="$res/mipmap-$density"
        mkdir -p "$dir"

        rsvg-convert -w "$legacy" -h "$legacy" "$work/cream.svg" \
            -o "$dir/ic_launcher.png"
        rsvg-convert -w "$legacy" -h "$legacy" "$work/cream-round.svg" \
            -o "$dir/ic_launcher_round.png"

        mark=$(( canvas * FG_MARK_DP / FG_CANVAS_DP ))
        inset=$(( (canvas - mark) / 2 ))
        rsvg-convert -w "$mark" -h "$mark" \
            --page-width "$canvas" --page-height "$canvas" \
            --left "$inset" --top "$inset" \
            "$BRAND_DIR/$TRANSPARENT_MASTER" \
            -o "$dir/ic_launcher_foreground.png"
    done
    echo "  launcher: $app  (${legacy}px legacy, ${canvas}px adaptive, 5 densities)"
done
