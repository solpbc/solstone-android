<!-- SPDX-License-Identifier: AGPL-3.0-only -->
<!-- Copyright (c) 2026 sol pbc -->

# SPL journal identity conformance corpus

`bundle/` is a read-only, byte-identical copy of the five-file authority bundle from
[`solpbc/spl`](https://github.com/solpbc/spl). `adoption.json` records the adopted authority
selection; `bundle/manifest.json` remains the authority for payload names and digests.

This README supplies SPDX coverage for `bundle/`. Adding headers to the JSON would break
its byte identity, and adding a separate marker to the bundle would violate its exact
five-file inventory.

## Re-vendor

1. Check out the intended `solpbc/spl` commit in a clean worktree outside this repository.
2. Copy unchanged `manifest.json`, `definition.json`, `definition.schema.json`,
   `vectors.json`, and `vectors.schema.json` from `proto/definition/bundle/` into
   `bundle/`.
3. Copy the consumed `proto/identity.md` into `proto-ref/identity.md` unchanged.
4. Update `adoption.json` with the authority commit, manifest metadata, and the four
   manifest payload records.
5. Update the pinned authority constants in `JournalIdentityConformanceTest`.
6. Review byte-level diffs and run the focused conformance tests.
