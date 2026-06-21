# platform/work S3 Design

`platform/work` owns the observer background sync shell and the pure sync decisions it needs. It is an Android library with namespace `app.solstone.platform.work`, `compileSdk = 35`, `minSdk = 23`, and Kotlin/JVM target 17.

## Module

- Add `include(":platform:work")`.
- Dependencies:
  - `api("androidx.work:work-runtime:2.9.1")`
  - `implementation(project(":platform:persistence-room"))`
  - `implementation(project(":platform:pl-transport-conscrypt"))`
  - `implementation(project(":platform:identity-file"))`
  - `implementation(project(":core:observer"))`
  - `implementation(project(":core:pl"))`
  - `implementation(project(":core:identity"))`
  - `implementation(project(":core:model"))`
  - `implementation(project(":core:queue"))`
  - `testImplementation(kotlin("test"))`
- JVM decision tests live in `src/test`.
- Mirror `platform/persistence-room` GMD/androidTest settings only if androidTest coverage is added.

## Boundaries

- Pure layer: no `android.*` or `androidx.work.*` imports. It contains the decision functions and sealed result types below and is JVM-tested with fakes. It may reference Room row data classes, core observer/pl/identity/model/queue types, and stores.
- Shell layer: the only layer that imports `android.Context` and `androidx.work`. It contains the `CoroutineWorker`, `SyncScheduler`, and the store/path factory.

## Endpoint And Store Paths

- Add `EndpointStore` beside `DirectEndpoint` in `core/pl`.
- Add `FileEndpointStore` in `platform/identity-file`, writing `host\nport\n`, reading line 0 as host and line 1 as `Int`, and applying owner-only permissions like the existing file stores.
- Add `implementation(project(":core:pl"))` to `platform/identity-file`.
- `pairAndProbe` accepts `endpointStore: EndpointStore` and saves the successful endpoint immediately after `identityStore.save(home)`.
- Canonical production PL directory: `File(context.filesDir, "pl")`.
- Store files in that directory:
  - `credential.pem`
  - `identity.tsv`
  - `endpoint.txt`
- The platform/work shell factory builds `FileClientCredentialStore`, `FileIdentityStore`, and `FileEndpointStore` from this one directory. The worker and both MainActivity files use the factory.

## Pure Signatures

```kotlin
package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.BundleManifest
import app.solstone.core.observer.IngestOutcome
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow

sealed interface SyncCredentials {
    data class Ready(
        val endpoint: DirectEndpoint,
        val credential: ClientCredential,
        val handle: String,
    ) : SyncCredentials

    data class NeedsRepair(val reason: String) : SyncCredentials
}

fun recoverSyncCredentials(
    endpointStore: EndpointStore,
    credentialStore: ClientCredentialStore,
    identityStore: IdentityStore,
): SyncCredentials

fun selectDrainSegments(segments: List<SegmentRow>): List<SegmentRow>

fun reconstructManifest(
    segment: SegmentRow,
    files: List<SegmentFileRow>,
): BundleManifest

enum class ReachabilityVerdict { DRAIN, RESCHEDULE, SKIP }

fun decideReachability(
    paired: Boolean,
    reachable: Boolean,
): ReachabilityVerdict

sealed interface SegmentSyncResult {
    data class Uploaded(val serverKey: String?) : SegmentSyncResult
    data class Retry(val status: Int?) : SegmentSyncResult
    data class HardFail(val status: Int) : SegmentSyncResult
    data class AuthHalt(val status: Int) : SegmentSyncResult
}

fun resolveIngestOutcome(outcome: IngestOutcome): SegmentSyncResult

fun resolveIoError(): SegmentSyncResult

fun haltsDrain(result: SegmentSyncResult): Boolean

fun nextSyncState(
    pendingCount: Int,
    lastSuccessAt: Long?,
    lastFailureAt: Long?,
): SyncStateRow
```

## Pure Decisions

- `recoverSyncCredentials` is fail-closed:
  - `NeedsRepair` if endpoint, credential, identity, observer handle, or paired state is missing.
  - `Ready` only when endpoint, credential, observer handle, and `IdentityState.PAIRED` are all durable facts.
- `selectDrainSegments` keeps only `QueueState.SEALED` and `MAIN_STREAM`; import `MAIN_STREAM` from `core:sources`, do not hardcode it. Location stream rows are excluded.
- `reconstructManifest` maps one `SegmentRow` plus its `SegmentFileRow`s to `BundleManifest(SegmentKey(segment.day, segment.segment), files, gaps = emptyList())`.
- `decideReachability`: not paired -> `SKIP`; paired and unreachable -> `RESCHEDULE`; paired and reachable -> `DRAIN`.
- `resolveIngestOutcome`:
  - Accepted, Collision, Duplicate -> `Uploaded(serverKey)`
  - Rejected -> classify by status: 401/403 -> `AuthHalt`, retryable 5xx -> `Retry`, other 4xx -> `HardFail`
  - Collision and Duplicate never reach retry classification.
- `resolveIoError` returns `Retry(null)`.
- `haltsDrain` returns true only for `AuthHalt`.
- `nextSyncState` returns singleton `SyncStateRow(id = 0, pendingCount, lastSuccessAt, lastFailureAt)`.

## Worker Drain

1. Build PL stores with the Context factory and call `recoverSyncCredentials`.
2. `NeedsRepair` records the condition and returns `Result.failure()`; re-pairing re-enqueues.
3. Open the authenticated PL client with the recovered endpoint and credential.
4. Probe `/app/network/api/status`; `status == 200` is reachable. Use `decideReachability(true, reachable)`.
5. Load `dao.segmentsByState(QueueState.SEALED)`, then `selectDrainSegments`.
6. Group by day. For each day, build `SegmentReconciler(client, handle)`, reconstruct manifests from DB rows, call `reconciler.diff(manifests, day)`, and record `dedupe_checked_at = now` for checked rows.
7. For each segment:
   - `advanceState(id, START_UPLOAD)`
   - increment attempt count and set `last_attempt_at`
   - if reconcile says upload is not needed, `advanceState(MARK_UPLOADED)` without POST
   - otherwise POST with `ObserverIngestClient`, mapping outcomes through the pure functions
   - `Uploaded` marks uploaded, stores `server_key` when non-null, and clears `last_error`
   - `Retry` marks failed, records status/error, and makes the run retry later
   - `HardFail` marks failed and records terminal failure metadata
   - `AuthHalt` marks failed, records metadata, sets `lastFailureAt`, and stops the whole drain
8. Retry and hard failure are per-segment isolated; only auth halt and non-IO fatal errors stop the batch.
9. After the drain, count pending main-stream rows, upsert `nextSyncState`, then return:
   - `Result.retry()` if any retry occurred and no auth halt
   - `Result.failure()` on auth halt
   - `Result.success()` otherwise

## Payload Path

`FileSpoolWriter` writes sealed segments from `baseDir = context.filesDir.toPath().resolve("spool")` to:

```text
<filesDir>/spool/<day>/<stream>/<segment>/<file.name>
```

The worker reads payload bytes from that exact path. For a `SegmentRow` and `SegmentFileRow`, `readPayloadFor` resolves:

```text
context.filesDir.toPath()
    .resolve("spool")
    .resolve(segment.day)
    .resolve(segment.stream)
    .resolve(segment.segment)
    .resolve(file.name)
```

`file.name` must remain a single path segment, matching `FileSpoolWriter`'s existing separator guard.

## Persistence Changes

- Add six nullable/additive segment columns:
  - `server_key TEXT`
  - `attempt_count INTEGER NOT NULL DEFAULT 0`
  - `last_status_code INTEGER`
  - `last_attempt_at INTEGER`
  - `dedupe_checked_at INTEGER`
  - `last_error TEXT`
- `SegmentRow` gains matching fields with defaults, so existing constructors keep compiling.
- Bump Room to version 2 and add `MIGRATION_1_2`; register it in `openSolstonePersistenceDatabase`. Do not add destructive fallback.
- Add focused DAO methods for uploaded metadata, attempt metadata, failure metadata, dedupe timestamp, sync state upsert/load, and pending main-stream count.
- Add `androidTestImplementation("androidx.room:room-testing:2.6.1")` and a 1 -> 2 migration test with `validateMigration = true`.

## Scheduling And Status

- `SyncScheduler.enqueuePeriodic(context)` creates unique periodic work with `ExistingPeriodicWorkPolicy.KEEP` and `NetworkType.CONNECTED`.
- `SyncScheduler.enqueueNow(context)` creates expedited unique one-time work with `ExistingWorkPolicy.KEEP` and `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`.
- Keep unique work names as constants.
- Worker holds no sensor lock.
- Phone and watch MainActivity use the store factory and `recoverSyncCredentials` for honest `linkPaired` / `authValid` facts.
- Replace hardcoded auth booleans minimally.
- Add a small status surface for pending count, last success, last failure, and auth-needs-attention, plus a `Sync now` button.
- Enqueue periodic sync on start.

## Build Integration

- Add `:platform:work:test` and `:platform:work:assembleDebug` to `make ci`.

## Implementation Order

1. Add module skeleton and dependencies.
2. Add endpoint store interface and file implementation; update pairing and test driver.
3. Add pure decision types/functions and JVM tests.
4. Add Room v2 rows, DAO methods, migration, schema export, and migration test.
5. Add worker shell, path/store factory, and scheduler.
6. Wire phone/watch status and sync actions.
7. Update Makefile CI targets.
