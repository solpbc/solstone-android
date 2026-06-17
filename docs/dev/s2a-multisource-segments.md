# S2a Multi-Source Segments

This note records the locked S2a implementation shape for multi-source observer
windowing, capture-time segment keys, single-threaded teardown, launch recovery,
and passive location capture. It is a design record and caller-change checklist;
production code changes are intentionally not included here.

## Locked Decisions

### Source emissions

- Add `stream` to `SourceEmission` immediately after `sourceId`.
- Add shared stream constants in `core:sources`:
  - `MAIN_STREAM = "observer"`
- Every `SourceEmission` construction must pass `stream`.
- Audio and location use `stream = MAIN_STREAM`.

### Segmenter

- Replace the current monotonic-clock API with `Segmenter(zoneId: ZoneId, windowMs: Long = 300_000L, graceMs: Long = 5_000L)`.
- Delete `SegmenterAnchor`; delete `MonotonicClock` once all callers no longer need it.
- Key open buckets by `WindowKey(stream, windowStartEpochMs)`.
- Compute `windowStartEpochMs` from capture time:
  - `Math.floorDiv(emission.captureStartEpochMs, windowMs) * windowMs`
- Track per-stream sealing with `sealedWatermark: MutableMap<String, Long>`, where the value is the highest sealed `windowStartEpochMs` for that stream.
- `feed(emission)`:
  - Use `emission.stream`.
  - If `windowStartEpochMs <= sealedWatermark[stream]`, treat the emission as late, record a durable `late_emission` gap, do not create or reopen the target bucket, then still run opportunistic sealing.
  - Otherwise, append one `SegmentPayload` per `payloadRef`, append emission gaps, and update `bucket.maxCaptureEndEpochMs` from `emission.captureEndEpochMs` even when there are no payloads.
  - Opportunistically seal earlier open windows in the same stream when a later capture arrives.
- `sealDue(nowEpochMs)` seals any open window in any stream where `windowStart + windowMs + graceMs <= nowEpochMs`.
- `flush()` seals all remaining open windows, advances stream watermarks, clears open buckets, and orders deterministically by `windowStartEpochMs` then `stream`.
- Sealing always advances that stream watermark to the sealed window start.
- Remove `fullWindow`. Covered length is always:
  - `(maxCaptureEndEpochMs - windowStartEpochMs).coerceIn(0, windowMs)`
- `wireKeys(windowStartEpochMs, windowStartEpochMs + coveredMs, zoneId)` is the only LEN path.

### Late emissions

- Represent late emissions as `GapEvent(kind = "late_emission", atEpochMs = emission.captureStartEpochMs, detail = targetWindowStart.toString())`.
- The durable deterministic home is the first not-yet-sealed window in the same stream:
  - `WindowKey(stream, sealedWatermark[stream] + windowMs)`
- This is sound because the target bucket is strictly after the watermark and cannot already be sealed for that stream. It will be sealed by a later capture, `sealDue`, or `flush`.
- Gap vocabulary stays distinct:
  - Segmenter: `late_emission`
  - Location source details: `no_fix`, `permission`, `provider_disabled`

### Capture pipeline

- Add pure-JVM `CapturePipeline` in `core:observer`.
- Add `core:observer` dependencies on `:core:sources`, `:core:segment`, and `:core:spool`.
- Constructor:
  - `Segmenter`
  - `SpoolWriter`
  - `SealedSegmentSink`
  - `PayloadBytesProvider`
  - `List<ContinuousSourceEngine>`
  - `nowProvider: () -> Long`
  - `tickIntervalMs: Long`
- The pipeline owns a single-thread `ScheduledExecutorService`.
- Each engine starts with an `EmissionSink` that submits `drain(segmenter.feed(emission))` to the executor.
- A periodic task submits `drain(segmenter.sealDue(nowProvider()))`.
- `drain` writes each sealed segment with `spoolWriter.seal(...)`, then calls `sealedSink.persistSealed(...)` on the same executor thread.
- Use `sealedAtEpochMs = nowProvider()` for `persistSealed`, because the sink interface already requires it.
- `start()` schedules the tick and starts engines.
- `stop()` order is strict:
  - stop all engines
  - engine `stop()` must join workers
  - submit final `drain(segmenter.flush())`
  - shut down executor
  - await bounded termination
- No segmenter or spool seal path runs concurrently.

### Audio source

- `AudioContinuousSourceEngine.stop()` captures the worker, sets running false, interrupts it, joins with a bounded timeout, then clears the worker reference.
- Remove `WindowClock.kt`, `WindowClockTest.kt`, the `clock` constructor parameter, and `clock.advanceWindow()`.
- Add `stream = MAIN_STREAM` to every audio `SourceEmission`.
- No production caller should read `engine.clock` after `CaptureSetup` is reshaped.

### Passive location source

- Add `:platform:location` as an Android library module.
- Build shape mirrors `:platform:audio`:
  - namespace `app.solstone.platform.location`
  - `compileSdk = 35`
  - `minSdk = 23`
  - dependencies on `:core:model`, `:core:sources`, `:core:segment`, `:core:spool`
  - `testImplementation(kotlin("test"))`
  - no androidTest or managed device config
- Pure-JVM logic:
  - `LocationFix(provider, timestampEpochMs, lat, lon, accuracyMeters, fixAgeMs)`
  - `NoFixReason` enum: `NO_FIX`, `PERMISSION`, `PROVIDER_DISABLED`
  - `buildLocationRecord(fix)` returns one deterministic JSONL line.
  - `decideGap(reason, atEpochMs)` returns `GapEvent("location_gap", atEpochMs, detail)`.
- JSON format decision:
  - Object keys in order: `provider`, `timestamp`, `lat`, `lon`, `accuracy`, `fixAge`
  - One line per fix, trailing newline included by the record builder.
  - No Gson; use deterministic hand-rolled escaping for `provider`.
  - Numeric values use Kotlin/JVM `Double.toString()` and `Long.toString()`.
- `LocationContinuousSourceEngine` implements `ContinuousSourceEngine` and `PayloadBytesProvider`.
- The engine accumulates passive/network samples across one 300,000 ms window into exactly one `location.jsonl` payload.
- A zero-fix window emits a gap-only `SourceEmission` with no payload refs.
- Constants:
  - `SOURCE_ID = "location"`
  - `PAYLOAD_NAME = "location.jsonl"`
  - `MEDIA_TYPE = "application/x-ndjson"`
  - `WINDOW_MS = 300_000L`
- Thin Android seam:
  - `LocationSource` exposes last fix and provider/permission state.
  - Android implementation wraps `LocationManager` passive/network access and does not force continuous GPS.

### Recovery and launch

- Add pure-JVM `applyRecoveryActions(actions: List<RecoveryAction>): List<SpoolRecoveryEvent>` in `core:spool`.
- `Finalize` creates final parents, moves draft dir to final dir with `ATOMIC_MOVE` and non-atomic fallback, and cleans empty draft parents.
- `Discard` deletes the draft dir recursively and returns its `SpoolRecoveryEvent`.
- The applier may synthesize a finalize visibility event, but discard events are required.
- Both app `MainActivity` launch paths must run this order before capture:
  - `spoolDir = filesDir.toPath().resolve("spool")`
  - `applyRecoveryActions(RecoveryScanner(spoolDir).scan(nowProvider()))`
  - `SpoolRoomReconciler(spoolDir, db.segmentDao()).reconcile()`
  - construct and start `CapturePipeline`
- On-disk sealed directories remain the source of truth.

### App capture setup

- Reshape both app `CaptureSetup` classes to:
  - `engines: List<ContinuousSourceEngine>`
  - `payloadBytesProvider: PayloadBytesProvider`
- Real factories construct audio and location engines.
- Real `payloadBytesProvider` routes by `payload.sourceId`:
  - `"audio"` to audio engine
  - `"location"` to location engine
  - otherwise error
- Mock factories construct fake audio and location sources on `MAIN_STREAM`.
- `FakeContinuousSource` gains a `stream` parameter defaulting to `MAIN_STREAM`.
- `VirtualMonotonicClock` is removed if no remaining caller needs `MonotonicClock`.
- Both `MainActivity` classes construct `Segmenter(ZoneId.systemDefault())` and `CapturePipeline(...)`.
- `onDestroy` calls `pipeline.stop()` before closing the database.
- Runtime permissions include audio, fine/coarse location, and notifications when applicable.
- State rendering continues through `reduce(SourceFacts)`; location permission must be reflected honestly.

### Manifests, gate, and build

- Both app manifests add:
  - `FOREGROUND_SERVICE_LOCATION`
  - `ACCESS_FINE_LOCATION`
  - `ACCESS_COARSE_LOCATION`
- Both services use `foregroundServiceType="microphone|location"`.
- Keep the existing API 28 untyped `startForeground` call in `ObserverForegroundService`.
- Do not add `dataSync`.
- Extend `registerMicrophoneManifestCheck` to require:
  - microphone foreground service permission
  - location foreground service permission
  - foreground service type includes `microphone`
  - foreground service type includes `location`
  - no `dataSync`
- Add `testOptions { targetSdk = 35 }` to `platform:persistence-room`.
- Register `:platform:location` in `settings.gradle.kts`.
- Add `:platform:location:assembleDebug` to `make ci`.

## Caller Change Checklist

### Core

- `core:sources`:
  - Add `stream` and stream constants.
  - Update all source/test constructors.
- `core:segment`:
  - Replace clock/anchor windowing with capture-time stream windowing.
  - Add `sealDue`.
  - Add late-emission gap routing.
  - Remove `SegmenterAnchor` and maybe `MonotonicClock`.
- `core:spool`:
  - Add recovery applier.
  - Reuse existing `SealedSegmentSink`.
  - Keep zero-file manifests valid for gap-only segments.
- `core:observer`:
  - Add dependencies on sources, segment, spool.
  - Add `CapturePipeline`.

### Platform

- `platform:audio`:
  - Remove `WindowClock`.
  - Join worker in `stop`.
  - Emit `MAIN_STREAM`.
- `platform:location`:
  - Add module, pure logic, Android seam, engine, tests.
- `platform:fgs`:
  - Extend permission status to include coarse/fine location.
- `platform:persistence-room`:
  - Add JVM test target SDK setting.
  - Add JVM test dependency for new `src/test` coverage.
  - Add reconcile tests with a fake `SegmentDao` subclass.

### Apps

- Watch and phone:
  - Update `CaptureSetup`.
  - Update real and mock factories.
  - Add location module dependency to real variants.
  - Replace direct feed/seal/flush code with `CapturePipeline`.
  - Add launch recovery before pipeline start.
  - Add location runtime permissions.
  - Update manifests.

### Testing

- `testing`:
  - Update fakes to carry streams.
  - Remove virtual monotonic clock if no longer referenced.
- Existing tests:
  - Update `ObserverPipelineTest`.
  - Update `SegmentTest` only where new segmenter behavior is covered.
  - Update `SealedSegmentBridgeInstrumentedTest`.
  - Remove `WindowClockTest`.

## Test Plan By Acceptance Criterion

- AC1 multi-file JOIN and cross-stream separation:
  - `platform:persistence-room/src/test` with a fake `SegmentDao` subclass.
- AC2 capture-time grid, LEN, and out-of-order emissions into an open window:
  - `core:segment SegmentTest`.
- AC3 quiet-stream sealing through `sealDue`:
  - `core:segment SegmentTest`.
- AC4 durable late-emission gap:
  - `core:segment SegmentTest`.
- AC5 crash-safe single-threaded teardown:
  - `core:observer CapturePipeline` test with a fake engine that feeds until `stop` joins.
- AC6 recovery applier and reconcile:
  - `core:spool` test for `applyRecoveryActions`.
  - `platform:persistence-room/src/test` reconcile test with temp dir and fake DAO.
- AC7 location records, location gaps, and all-gap zero-file location segment:
  - `platform:location/src/test`.
  - Pipeline-level location test.

## Pressure-Test Findings

- `SealedSegmentSink` already lives in `core:spool`, so `CapturePipeline` can depend on it from `core:observer`.
- `SealedSegmentSink.persistSealed` requires `sealedAtEpochMs`; the pipeline must pass `nowProvider()` during `drain`.
- Adding `:core:sources`, `:core:segment`, and `:core:spool` to `core:observer` does not create a dependency cycle.
- `core:observer` is currently pure JVM, so the pipeline must stay free of Android imports.
- `SegmentDao` is an abstract class, so fake DAO tests can subclass it and implement abstract methods.
- `platform:persistence-room` currently declares androidTest dependencies only; the planned JVM `src/test` reconcile tests need a test dependency such as `testImplementation(kotlin("test"))`.
- `SpoolRoomReconciler` scans final manifest directories and ignores `.draft`, matching the required launch order after recovery applies draft actions.
- Both app `MainActivity` classes currently perform direct feed/seal/flush and close the database on a background thread; that must be replaced wholesale by `CapturePipeline`.
- `PermissionStatus` currently models only microphone and notifications, so location permission honesty requires extending that platform model.
- The manifest gate currently matches exactly `foregroundServiceType="microphone"` and must change to token-based checking for `microphone|location`.
- Current real factories expose `engine.clock`; removing `WindowClock` requires reshaping `CaptureSetup` in the same change.
- `VirtualMonotonicClock` is only tied to the old segmenter/testing path and should be deleted once fakes and tests use capture time.

## Chosen Specifics

- Location JSON keys: `provider`, `timestamp`, `lat`, `lon`, `accuracy`, `fixAge`.
- Location media type: `application/x-ndjson`.
- Audio stop join timeout: 5 seconds.
- Capture pipeline executor termination timeout: 5 seconds.
- Default pipeline tick interval: 5,000 ms in app wiring.
- Late-emission gap detail: original target `windowStartEpochMs.toString()`.
- Location gap details: `no_fix`, `permission`, `provider_disabled`.
