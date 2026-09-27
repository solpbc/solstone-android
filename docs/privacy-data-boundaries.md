# Privacy And Data Boundaries

This repo implements owner-side Android surfaces for solstone. The product rule is structural: owner data is for the owner and their journal.

## Prohibited

- analytics SDKs,
- third-party pixels,
- telemetry vendors,
- third-party crash reporters,
- behavioral profiling,
- logs containing payload bytes, transcripts, QR pair links, private keys, client certificates, or raw captured media.

## Required

- local-first evidence and spool state,
- explicit owner-visible permission flows,
- honest state when a source is unavailable, paused, killed, unlinked, or unsynced,
- a segment's local copy removed from the device once the journal confirms it holds that segment,
- battery and IMU telemetry in `metadata.jsonl` treated as owner data under the same local-first rules, and removed with its segment,
- redacted diagnostics that are useful without exposing owner data.
