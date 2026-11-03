# mark-vectors

Generates `core/pl/src/test/resources/journal-mark/vectors.json`, the shared vectors that hold
the Android journal mark derivation byte-identical to `spl_core::mark::mark_from_jid`.

The dependency is pinned in `Cargo.toml`. From this directory:

```bash
cargo run --release > ../../core/pl/src/test/resources/journal-mark/vectors.json
```

Output is deterministic. It takes a few minutes, because it derives marks until it has found
journals whose words are hyphenated.
