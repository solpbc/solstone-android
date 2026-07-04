// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import app.solstone.core.model.QueueState
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.observer.scaffold.ObserverAppContainer
import app.solstone.observer.scaffold.ObserverHarnessRuntime
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import org.junit.Assert.fail

internal const val TEST_DATABASE_NAME = "solstone-persistence.db"

internal fun resetObserverRuntime() {
    ObserverHarnessRuntime.runtime?.closeForTest()
    ObserverHarnessRuntime.runtime = null
    ObserverHarnessRuntime.hooks = null
}

internal fun resetPersistence(context: Context) {
    context.deleteDatabase(TEST_DATABASE_NAME)
    context.filesDir.resolve("spool").deleteRecursively()
    context.filesDir.resolve("mock-export").deleteRecursively()
    context.getSharedPreferences("desired-observing-persistence-test", Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
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
    val db = openSolstonePersistenceDatabase(context)
    try {
        db.segmentDao().insertSegmentWithFiles(
            SegmentRow(
                id = id,
                day = "20260617",
                stream = MAIN_STREAM,
                segment = "120000_300",
                dirSegment = "120000_300",
                state = QueueState.SEALED,
                byteSize = 5,
                sealedAt = 10,
                homeInstanceId = null,
                observerHandle = null,
            ),
            listOf(
                SegmentFileRow(
                    segmentId = id,
                    sourceId = "audio",
                    name = "audio.m4a",
                    sha256 = "sha-$id",
                    byteSize = 5,
                    mediaType = "audio/mp4",
                    captureStartEpochMs = 1,
                    captureEndEpochMs = 2,
                ),
            ),
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

internal fun validDirectPairLink(): String {
    val bytes = ByteArray(40)
    bytes[0] = 0x04
    bytes[1] = 0x01
    bytes[2] = 10
    bytes[3] = 0
    bytes[4] = 0
    bytes[5] = 2
    bytes[6] = 0x1d
    bytes[7] = 0xe9.toByte()
    for (i in 8 until bytes.size) {
        bytes[i] = i.toByte()
    }
    return "https://go.solstone.app/p#${crockfordEncode(bytes)}"
}

private fun crockfordEncode(bytes: ByteArray): String {
    val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    val out = StringBuilder()
    var buffer = 0
    var bits = 0
    bytes.forEach { raw ->
        buffer = (buffer shl 8) or (raw.toInt() and 0xff)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            out.append(alphabet[(buffer shr bits) and 31])
            buffer = buffer and ((1 shl bits) - 1)
        }
    }
    if (bits > 0) {
        out.append(alphabet[(buffer shl (5 - bits)) and 31])
    }
    return out.toString()
}
