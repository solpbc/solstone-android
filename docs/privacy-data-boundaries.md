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
- delete-by-source design for future observer modules,
- battery and IMU telemetry in `metadata.jsonl` treated as owner data under the same local-first and delete-by-source covenant,
- diagnostics-only observer health beacons carrying only status, counts, and short reason codes, intentionally excluding captured content and captured file paths,
- redacted diagnostics that are useful without exposing owner data.
