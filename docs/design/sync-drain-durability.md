# Sync Drain Durability Design

## Scope

Durability-only change to the Android sync drain path. No HTTP wire/protocol change and no Room schema migration. Retry due-ness is derived from existing `SegmentRow` fields. All queue state movement continues through legal `QueueEvent` edges.

## Validated Decisions

### D1. Extracted drain seam

Add `platform/work/src/main/kotlin/app/solstone/platform/work/SegmentDrainer.kt`.

Define `DrainStore` with exactly this surface:

- `syncState(): SyncStateRow?`
- `segmentsForDrain(): List<SegmentRow>`
- `filesBySegmentId(id: String): List<SegmentFileRow>`
- `recordDedupeChecked(id: String, at: Long): Int`
- `advanceState(id: String, event: QueueEvent): QueueState`
- `recordAttempt(id: String, attempts: Int, at: Long): Int`
- `recordUploaded(id: String, serverKey: String?): Int`
- `recordFailure(id: String, code: Int?, error: String?): Int`
- `pendingCount(stream: String): Int`
- `upsertSyncState(row: SyncStateRow)`

Add `RoomDrainStore(private val dao: SegmentDao) : DrainStore` in the same file. It is a thin adapter. `segmentsForDrain()` calls the new DAO query with `MAIN_STREAM`. `pendingCount(stream)` delegates to DAO; worker/drain will pass `MAIN_STREAM`.

Move drain into `fun drainSegments(store: DrainStore, reconcile: (List<BundleManifest>, String) -> List<ReconcileVerdict>, ingest: (BundleManifest, (BundleFile) -> ByteArray) -> IngestOutcome, readPayload: (SegmentRow, BundleFile) -> ByteArray, now: () -> Long, log: (String, Throwable?) -> Unit): DrainReport`.

Validation:

- The proposed `ingest` seam can drop handle/host/platform because `SyncWorker` can pre-bind them into the lambda. `ObserverIngestClient.ingest` currently takes host/platform as optional params, and the worker is the only drain caller.
- `SegmentReconciler::diff` currently matches the reconcile lambda: `(List<BundleManifest>, String) -> List<ReconcileVerdict>`. D6 changes only throw typed exceptions; return shape stays the same.
- `RelayWebSocketClosedException` is a plain `IOException` subclass in `platform/pl-transport-conscrypt`, so importing/rethrowing it does not introduce `android.*`.
- `host` is redundant in the drain signature because `ingest` is fully pre-bound by `SyncWorker`.

`SyncWorker` changes:

- Build `RoomDrainStore(db.segmentDao())`.
- Bind `reconcile = SegmentReconciler(c, handle)::diff`.
- Bind `ingest = { manifest, fileBytes -> ObserverIngestClient(c) { "solstoneSync${System.nanoTime()}" }.ingest(manifest, handle, fileBytes, deviceLabel(), "android") }`.
- Bind `readPayload = { segment, file -> readPayloadFor(spoolDir, segment, file) }`.
- Bind `now = System::currentTimeMillis`.
- Bind `log = { message, throwable -> Log.w(TAG, message, throwable) }`.

### D2. Recovery transitions and selection

Add DAO query in `SegmentDao`:

- `segmentsForDrain(stream: String): List<SegmentRow>`
- SQL: select rows for `stream` where state is `SEALED`, `UPLOADING`, or `FAILED`, ordered by `sealed_at ASC, id ASC`.

Claim each selected row into `UPLOADING` before attempt recording:

- `SEALED -> START_UPLOAD`
- `FAILED -> RETRY`
- `UPLOADING -> MARK_FAILED -> RETRY`

Validation:

- These are all legal edges in `core/queue/Queue.kt`. There is no `UPLOADING -> SEALED` edge and none is needed.
- `UPLOADING` is safe to treat as crash leftover only after D8 single-drain exclusion.
- Grouping by day still works with a retry-due/capped subset because reconcile consumes the manifests for only the selected rows in that day. Rows excluded by backoff/cap remain untouched and count in `pendingAfter`.

Unexpected claim failure must log and skip only that segment. Do not let `IllegalStateException` from `advanceState` escape the drain.

### D3. Retry due helper

Add pure helpers in `SyncDecisions.kt`:

- `isRetryDue(state: QueueState, attemptCount: Int, lastAttemptAt: Long?, lastStatusCode: Int?, now: Long): Boolean`
- `retryBackoffMs(attemptCount: Int, decision: RetryDecision): Long`

Exact policy for sign-off:

- `SEALED` is due.
- `UPLOADING` is due; D8 makes it a crash-leftover recovery path.
- `FAILED` is due only if the stored classification is retryable/hard-fail cadence and elapsed time satisfies backoff.
- `STOP_AUTH` is not due.
- `RETRY` cadence: 15 min, 30 min, 1 h, 2 h, 4 h, capped at 4 h. Formula: `15min * 2^min(max(attemptCount - 1, 0), 4)`.
- `HARD_FAIL` cadence: 2 h, 4 h, 6 h, capped at 6 h. Formula: `min(2h * 2^min(max(attemptCount - 1, 0), 2), 6h)`.
- For stored rows, classify with `classify(lastStatusCode, ioError = lastStatusCode == null)`.

Rationale: retryable failures recover quickly at first but stop waking frequently after repeated failures; hard failures are not terminal forever but move slowly enough to avoid tight loops after durable 4xx errors.

### D4. Honest success

Drain outcome must be derived from the whole run, not per-segment success timestamps, and WorkManager retry should only be used for failures or work that is due now.

Track:

- `halted`
- `failedThisRun`, set on retry/hard-fail/auth result, payload failure, reconcile unavailable/auth, and claim failure
- `dueRemaining`, set when the per-run cap excludes retry-due rows
- immutable `priorLastSuccessAt = store.syncState()?.lastSuccessAt`
- `pendingAfter = store.pendingCount(MAIN_STREAM)` after the loop
- `cleanDrain = !halted && !failedThisRun && pendingAfter == 0`

Outcome:

- `halted -> FAILURE`
- `failedThisRun -> RETRY`
- `dueRemaining -> RETRY`
- otherwise `SUCCESS`

At end, call `advanceLastSuccess(priorLastSuccessAt, cleanDrain, now())`. Remove the current per-segment `lastSuccessAt = now` mutations from skip/upload paths.

Beacon accounting uses `nextRecentErrorCount(previous, cleanDrain, failedThisRun)`: clean drains reset to 0, failed runs increment and clamp, and idle-not-due runs leave the previous count unchanged.

Validation:

- AC-3 holds: all 422 hard-fail rows set `failedThisRun`, outcome `RETRY`, no success stamp, and no health error-count reset.
- AC-4a holds: only FAILED rows still in backoff are not selected; `pendingAfter > 0` with no failures and no due remainder returns `SUCCESS`, but `cleanDrain` is false so there is no success stamp and beacon error count is unchanged.

### D5. Missing or unreadable payloads

At the ingest call site, catch in this order:

- `RelayWebSocketClosedException`: rethrow.
- `FileNotFoundException`: mark that segment `FAILED`, `recordFailure(id, null, "payload missing: <name>")`, set `anyFailedThisRun`, log, continue.
- `IllegalArgumentException`: mark that segment `FAILED`, `recordFailure(id, null, "payload unreadable")`, set `anyFailedThisRun`, log, continue.
- `IOException`: network/transient IO, resolve as current retry behavior.

Validation:

- `FileNotFoundException` subclasses `IOException`, so it must be before `IOException`.
- The path containment guard in `readPayloadFor` uses `require`, so it throws `IllegalArgumentException`.
- Continuing the loop preserves current per-segment isolation for non-auth failures.

### D6. Reconcile robustness

In `core/observer/src/main/kotlin/app/solstone/core/observer/SegmentReconciler.kt`, add typed exceptions:

- `sealed class ReconcileException(...)`
- `class ReconcileAuthException(val status: Int) : ReconcileException(...)`
- `class ReconcileUnavailableException(val status: Int?, cause: Throwable? = null) : ReconcileException(...)`

Change `fetch` status handling:

- `200`: parse body. Wrap parse/shape exceptions as `ReconcileUnavailableException(200, cause)`.
- `401`/`403`: throw `ReconcileAuthException(status)`.
- Other non-200: throw `ReconcileUnavailableException(status)`.

Drain wraps each day's reconcile call:

- `RelayWebSocketClosedException`: rethrow.
- `ReconcileAuthException`: log, set `halted = true`, break; outcome `FAILURE`.
- `ReconcileUnavailableException` or `IOException`: log, set `anyFailedThisRun = true`, continue; that day's rows are never claimed.

Validation:

- Other callers are tests and `LiveObserverDriverTest.t4_reconcileSegments`; no production app caller depends on parsing error bodies.
- No wire change: same GET path and headers.
- Core observer tests must be updated for typed exceptions on non-200 and parse/shape failures.

### D7. Register auth

In `ObserverRegistration.register`, add typed exception:

- `class ObserverAuthException(val status: Int) : IOException` is not recommended because `registerThenDrain` rethrows all `IOException` as transient. Use a non-IO `RuntimeException` or sealed observer exception and catch it before generic `Exception`.

Status handling:

- `200`: parse as today.
- `401`/`403`: throw `ObserverAuthException(status)`.
- Other non-200: throw clear non-auth exception, or keep `IllegalStateException`.

In `registerThenDrain`:

- Add `RegisterDrainOutcome.Halt`.
- Catch `ObserverAuthException` before generic `Exception`, call `onError`, return `Halt`.
- Keep `IOException` rethrow for transient transport failures.
- Other `Exception` remains `onError + Retry`.

Worker `when` must add `RegisterDrainOutcome.Halt -> SyncOutcome.FAILURE`; current `when` has only `Retry` and `Drained`, so it will not be exhaustive after adding `Halt`.

Validation:

- `registerObserverHandle` can keep returning `String`; typed exceptions pass through.
- Existing `RegisterThenDrainTest` needs a new auth-halt test.

### D8. Single-drain exclusion

Add a process-wide gate in platform/work:

- `object SyncDrainGate` backed by `java.util.concurrent.Semaphore(1)`.
- Expose `tryAcquire(): Boolean` and `release()`.

Wrap `SyncWorker.sync(...)` from before DB open through DB close. Loser returns `Result.retry()` and logs. Winner releases in `finally`.

Validation:

- Wrapping `sync` covers token maintenance, DB open, status probe, registration, drain, health, and DB close.
- This makes observed `UPLOADING` rows in drain candidates crash leftovers rather than same-process concurrent work.
- Unit-test gate directly: first acquire true, second false, release, acquire true.

### D9. Insert-if-absent with file refresh

Change `SegmentDao.insertSegmentWithFiles`:

- If `segmentById(segment.id) == null`, call `insertSegment(segment)`.
- Always `deleteFilesBySegmentId(segment.id)`.
- Insert replacement file rows when non-empty.

Leave `insertSegment` conflict strategy as `REPLACE`; production code does not call it directly outside `insertSegmentWithFiles`.

Validation:

- `RoomSealedSegmentSink.persistSealed` inserts new `SEALED` rows and still works.
- `SpoolRoomReconciler.insertManifest` already guards existing rows, so behavior remains unchanged.
- Existing instrumented test `insertSegmentWithFiles_persistsThenReplacesFilesOnReinsert` encodes old behavior and must be rewritten.
- New AC-10 persistence-room androidTest: seed an `UPLOADED` row with `serverKey` and `attemptCount`, reinsert same id as `SEALED` with new files, assert row remains `UPLOADED`, serverKey/attemptCount remain intact, stale files are gone, new files exist.

### D10. Per-run cap

Add constant in SegmentDrainer:

- `DRAIN_SEGMENT_CAP = 50`

Change `selectDrainSegments` to:

- `selectDrainSegments(rows: List<SegmentRow>, now: Long, cap: Int = DRAIN_SEGMENT_CAP): List<SegmentRow>`
- Filter `isRetryDue(...)`.
- Take `cap`.

Validation:

- Main-stream filtering moves to DAO query. The pure selector should not re-filter stream unless tests intentionally pass mixed streams.
- Query only touches `SegmentDao`; no schema migration.
- Oldest-first remains because DAO orders by `sealed_at ASC, id ASC`.
- Sequential drain keeps memory bounded to one segment payload at a time; cap mainly bounds run duration and reconcile batch size.

## Logging Seam

Use logging lambdas in extracted/pure helpers and bind to Android `Log` only in `SyncWorker`. This keeps JVM tests free of `android.util.Log` and matches the existing `onError` pattern in `registerThenDrain`.

Catch sites to update for AC-12:

- `SyncWorker.sync`: currently catches `RelayDialWaitingException`, `RelayWebSocketClosedException`, `IOException`, and generic `Exception`; log each catch directly.
- `SyncWorker.syncWithTransport` status probe: catch `IOException` currently returns retry without logging; log. `RelayWebSocketClosedException` rethrow can log at outer catch only.
- Extracted drain ingest catches: every catch logs through `log` seam except rethrow if outer catch logs.
- New claim/reconcile/payload catches in SegmentDrainer must log.
- `SyncDecisions.registerThenDrain`: existing `onError` logs register/persist exceptions; add auth halt logging through the same seam.
- `BeaconDecisions.emitObserverHealth`: currently catches generic `Exception` without logging. Add `onError: (Throwable) -> Unit` or `log: (String, Throwable?) -> Unit` parameter and update callers/tests.
- `RelayTokenMaintenance` has swallowed relay-close inside refresh fallback; not directly in drain AC unless Jer scopes AC-12 to all platform/work catches. If AC-12 literally means every platform/work catch, update it too.

## Health Beacon, Classify, and Observer Changes

- `ObserverHealthClient.report` must treat non-2xx as failure: either throw a typed exception or return a failure result. Minimal implementation: throw `IllegalStateException` on non-2xx and let `emitObserverHealth` return `FAILED`.
- Update `core/observer` test `ObserverHealthTest.reportIgnoresNon200Response` to assert failure/throw, and `platform/work` `BeaconDecisionsTest` to assert `emitObserverHealth` returns `FAILED` on non-2xx.
- Add `408`, `425`, and `429` retry mapping in `core/queue/Queue.kt`.
- Update `QueueTest.classifyMapsRetryDecisions`.

## Implementation Order

1. Add/update pure tests for queue classification and retry due helpers.
2. Add `SegmentDrainer.kt` with `DrainStore`, selector, retry-due use, claim helper, honest outcome, logging seam, and JVM fake tests.
3. Add `SegmentDao.segmentsForDrain` and `RoomDrainStore`; update insert-if-absent behavior and persistence-room androidTest.
4. Harden `SegmentReconciler` exceptions and update core/observer tests.
5. Harden `ObserverRegistration`, add `RegisterDrainOutcome.Halt`, update platform/work register tests and worker `when`.
6. Harden `ObserverHealthClient.report` and `emitObserverHealth` logging/failure behavior; update core/observer and platform/work tests.
7. Add `SyncDrainGate`, wrap worker sync path, and add gate unit test.
8. Refactor `SyncWorker` to bind the extracted drain seams and remove old in-worker drain function.
9. Run `make ci`; run `make ci-device` because persistence-room androidTest and on-device sync surfaces are touched.

## AC to Test Matrix

| AC | Test | Tier | Notes |
|---|---|---|---|
| AC-1 extracted drain is JVM-testable and drains a basic upload | `platform/work/src/test/kotlin/app/solstone/platform/work/SegmentDrainerTest.drainsSealedUploadAndPersistsCleanSuccess` | JVM platform/work | In-memory `DrainStore`, fake reconcile says upload, fake ingest accepted. Must fail pre-change because no extracted unit exists. |
| AC-2 stranded `UPLOADING` recovery | `SegmentDrainerTest.recoversUploadingViaMarkFailedThenRetryBeforeUpload` | JVM platform/work | Fake store records events; assert `MARK_FAILED`, `RETRY`, attempt increment, upload. |
| AC-3 honest success for hard failures | `SegmentDrainerTest.allHardFailuresReturnRetryAndDoNotStampSuccess` | JVM platform/work | Fake ingest returns 422 for all. Must fail pre-change because old code can stamp success per segment. |
| AC-4a in-backoff failed rows do not mark success | `SegmentDrainerTest.inBackoffFailedRowsReturnRetryWithoutSuccessStamp` | JVM platform/work | Seed failed row with recent `lastAttemptAt`; selector excludes; pending count remains >0. |
| AC-4b missing/unreadable payload is per-segment failure | `SegmentDrainerTest.payloadMissingMarksOnlyThatSegmentFailedAndContinues` and `payloadPathViolationMarksFailedAndContinues` | JVM platform/work | `readPayload` throws `FileNotFoundException` / `IllegalArgumentException`; second row still drains. |
| AC-5 408/425/429 retry | `core/queue/src/test/kotlin/app/solstone/core/queue/QueueTest.classifyMapsRetryDecisions` | JVM core/queue | Add explicit assertions. |
| AC-6 reconcile unavailable leaves rows unclaimed and retries | `SegmentDrainerTest.reconcileUnavailableLogsAndLeavesDayDrainable` | JVM platform/work | Fake reconcile throws `ReconcileUnavailableException`; assert no claim events and outcome retry. |
| AC-7 reconcile auth halts | `SegmentDrainerTest.reconcileAuthHaltsWithFailure` | JVM platform/work | Fake reconcile throws `ReconcileAuthException`; assert failure and log. |
| AC-8 single drain exclusion | `platform/work/src/test/kotlin/app/solstone/platform/work/SyncDrainGateTest.tryAcquireExcludesConcurrentDrainAndReleaseReopens` | JVM platform/work | Direct gate test. Worker loser path can be covered if constructor setup is practical; otherwise keep gate unit. |
| AC-9 register auth halts | `platform/work/src/test/kotlin/app/solstone/platform/work/RegisterThenDrainTest.registerAuthFailureHaltsWithoutPersistOrDrain` | JVM platform/work | Fake register throws `ObserverAuthException`; assert `Halt`, logged, no persist/drain. |
| AC-10 re-seal does not regress queue row | `platform/persistence-room/src/androidTest/kotlin/app/solstone/platform/persistence/room/RoomQueueStoreInstrumentedTest.insertSegmentWithFiles_refreshesFilesWithoutReplacingExistingSegmentRow` | Instrumented persistence-room | Seed uploaded row with serverKey/attemptCount; reinsert sealed; assert row metadata preserved and files refreshed. |
| AC-11 per-run cap | `SegmentDrainerTest.capLeavesRemainderPendingAndReturnsRetry` | JVM platform/work | Seed 51 due rows, cap 50; assert 50 attempted and outcome retry due pendingAfter. |
| AC-12 every catch logs | `SegmentDrainerTest.logsClaimPayloadAndReconcileCatches`; `RegisterThenDrainTest` auth/generic logging assertions; `BeaconDecisionsTest.emitObserverHealthLogsClientFailure` | JVM platform/work | Use log list fake. Worker-level `Log` catches are best verified by code review unless returnDefaultValues/Robolectric is added. |
| AC-13 health beacon non-2xx surfaces failure | `core/observer/src/test/kotlin/app/solstone/core/observer/ObserverHealthTest.reportFailsOnNon2xxResponse`; `platform/work/src/test/kotlin/app/solstone/platform/work/BeaconDecisionsTest.emitObserverHealthReturnsFailedOnNon2xx` | JVM core/observer + platform/work | Existing `reportIgnoresNon200Response` must be inverted. |

## Risks and Open Questions

- `host` in D1 drain signature does not have a consumer if ingest is pre-bound. Recommendation: remove it from the drain function or explicitly use it in the worker's ingest binding.
- AC-12 scope needs confirmation. If it means every `platform/work` catch, include `RelayTokenMaintenance`; if it means sync drain path only, it can remain out of scope.
- `ObserverAuthException` must not subclass `IOException` unless `registerThenDrain` catch order changes to catch it before `IOException`; non-IO typed exception is safer.
- `recentErrorCount` increments on in-backoff runs with no new error because honest outcome is `RETRY`. This is internally consistent but owner-visible health semantics may need Jer approval.
- `SegmentReconciler.fetch` parse errors as transient unavailable will change existing core/observer tests that currently expect `IllegalArgumentException` for malformed 200 bodies.
- `LiveObserverDriverTest` will compile with typed exceptions but its failure output class names change on auth/reconcile failures.
