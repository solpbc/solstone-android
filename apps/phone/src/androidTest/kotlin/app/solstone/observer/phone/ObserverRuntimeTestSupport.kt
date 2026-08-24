// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.solstone.core.model.QueueState
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.observer.scaffold.ObserverAppContainer
import app.solstone.observer.scaffold.ObserverApplication
import app.solstone.observer.scaffold.ObserverHarnessRuntime
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import org.junit.Assert.fail

internal const val TEST_DATABASE_NAME = "solstone-persistence.db"

internal fun resetObserverRuntime() {
    val runtime = ObserverHarnessRuntime.runtime
    runtime?.closeForTest()
    val appRuntime = (ApplicationProvider.getApplicationContext<Context>() as ObserverApplication).runtime
    if (appRuntime !== runtime) appRuntime.closeForTest()
    ObserverHarnessRuntime.runtime = null
    ObserverHarnessRuntime.hooks = null
    PhoneStatusSupplier.override = null
}

internal fun resetPersistence(context: Context) {
    context.deleteDatabase(TEST_DATABASE_NAME)
    context.filesDir.resolve("spool").deleteRecursively()
    context.filesDir.resolve("mock-export").deleteRecursively()
    context.filesDir.resolve("source-wishes").delete()
    context.filesDir.resolve("journal-cache-limit").delete()
    context.getSharedPreferences("desired-observing-persistence-test", Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
    context.getSharedPreferences("phone_widget_start_outcome", Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
}

internal fun obtainObserverContainer(): ObserverAppContainer {
    val app = ApplicationProvider.getApplicationContext<Context>() as ObserverApplication
    val runtime = ObserverHarnessRuntime.runtime ?: app.runtime.also {
        ObserverHarnessRuntime.runtime = it
    }
    return runtime.container()
}

internal fun seededObserverContainer(seed: () -> Unit): ObserverAppContainer {
    seed()
    // An activity from the prior test can create and cache a container during reset. Close that
    // container so the one returned here opens the database file written by this fixture.
    resetObserverRuntime()
    return obtainObserverContainer()
}

internal fun waitForObserverContainer(): ObserverAppContainer {
    waitUntil("observer container") { ObserverHarnessRuntime.container is ObserverAppContainer }
    return ObserverHarnessRuntime.container as ObserverAppContainer
}

internal fun waitForRecovery(container: ObserverAppContainer): Boolean {
    repeat(100) {
        if (container.recoveryCompleted) return true
        Thread.sleep(100L)
    }
    return container.recoveryCompleted
}

internal fun waitUntil(label: String, timeoutMs: Long = 10_000L, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (predicate()) return
        Thread.sleep(100)
    }
    fail("Timed out waiting for $label")
}

internal fun seedPendingEvidence(context: Context, id: String = "pending-1") {
    seedEvidence(
        context = context,
        id = id,
        sourceIds = listOf("audio"),
        stream = MAIN_STREAM,
        state = QueueState.SEALED,
    )
}

internal fun seedEvidence(
    context: Context,
    id: String,
    sourceIds: List<String>,
    stream: String,
    state: QueueState,
) {
    check(ObserverHarnessRuntime.container == null) {
        "Seed evidence before obtaining an observer container; concurrent Room instances can read a stale view of the test database"
    }
    val db = openSolstonePersistenceDatabase(context)
    try {
        db.segmentDao().insertSegmentWithFiles(
            SegmentRow(
                id = id,
                day = "20260617",
                stream = stream,
                segment = "120000_300",
                dirSegment = "120000_300",
                state = state,
                byteSize = 5,
                sealedAt = 10,
                homeInstanceId = null,
                observerHandle = null,
            ),
            sourceIds.map { sourceId ->
                SegmentFileRow(
                    segmentId = id,
                    sourceId = sourceId,
                    name = if (sourceId == "audio") "audio.m4a" else "$sourceId.bin",
                    sha256 = if (sourceId == "audio") "sha-$id" else "sha-$id-$sourceId",
                    byteSize = 5,
                    mediaType = "audio/mp4",
                    captureStartEpochMs = 1,
                    captureEndEpochMs = 2,
                )
            },
        )
    } finally {
        db.close()
    }
}

internal fun pendingEvidenceCount(context: Context): Int {
    val db = openSolstonePersistenceDatabase(context)
    return try {
        db.segmentDao().pendingCount(MAIN_STREAM)
    } finally {
        db.close()
    }
}
