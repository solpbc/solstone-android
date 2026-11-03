# Third-party assets

Assets vendored into this repository that carry their own license, separate from the
repository's AGPL-3.0-only source license.

## Comfortaa (font)

`formfactor/phone/src/main/res/font/comfortaa_bold.ttf` — the brand display face. Latin
subset of Comfortaa Bold, vendored from the sol pbc brand asset source.

- Designer: Johan Aakerlund
- License: **SIL Open Font License 1.1** — https://openfontlicense.org
- Upstream: https://github.com/googlefonts/comfortaa

The OFL permits bundling in software provided the font is not sold on its own and the
license travels with it. The attribution is surfaced to owners in the app's own
`about solstone › licenses` pane, not only here.

## Journal mark tables

`core/pl/src/main/resources/mark_assets/` — the locked tables a journal's mark is chosen from,
byte-identical to `spl-core`'s, with their own `NOTICE.md`. The app computes a journal's mark from
the key it pinned, so it carries them. Index position is load-bearing: do not reorder, regenerate,
add or remove entries.

- `glyphs.json`: 60 Lucide icons. **ISC License**, and **MIT** for the ones Lucide derives from
  Feather, https://github.com/lucide-icons/lucide. The full text is in
  `formfactor/phone/src/main/res/raw/license_lucide.txt`.
- `words.json`: the EFF long wordlist. **CC BY 3.0 US**, https://www.eff.org/dice.
- `colors.json`: sol pbc's own, no third-party rights.

Both attributions are surfaced to owners in `about solstone › licenses`.
