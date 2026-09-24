// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.spool.SealedSegmentSink

/**
 * A sealed segment asks for a sync at once, so it reaches the journal within seconds instead of at
 * the next 15 min periodic sync. [requestSync] is the owner's own "sync now": it does nothing while
 * unpaired, waits for a network, and is dropped while a sync is already queued or running, and a
 * running drain picks up what sealed during it.
 */
fun syncingOnSeal(sink: SealedSegmentSink, requestSync: () -> Unit): SealedSegmentSink =
    SealedSegmentSink { segment, result, sealedAtEpochMs ->
        sink.persistSealed(segment, result, sealedAtEpochMs)
        // ⛔ The request never fails the seal: the pipeline reports a throwing sink as an orphaned
        // segment, and the periodic sync still collects this one.
        runCatching { requestSync() }
    }
