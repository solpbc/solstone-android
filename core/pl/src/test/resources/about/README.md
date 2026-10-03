<!-- SPDX-License-Identifier: AGPL-3.0-only -->
<!-- Copyright (c) 2026 sol pbc -->

# Journal About authority bundle

The byte-identical JSON files in `bundle/` are pinned by `manifest.json`, which is the digest
authority for the six files. `adoption.json` records the authority and the input digests copied
from that manifest. Those input digests were recorded at vendor time and are not re-hashed over
the network. Do not add headers or otherwise change the vendored JSON bytes.
