// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

/** What every status surface on this phone says about the journal connection. */
enum class PresentedConnection { CONNECTED, CONNECTING, OFFLINE }

/** The presented connection, and the waiting count the surfaces draw beside it. */
data class PresentedStatus(val connection: PresentedConnection, val pendingCount: Int)

/**
 * How long each presented state is allowed to stand.
 *
 * The phone holds no connection open: it learns about the journal from a status check, one fresh
 * TLS session per check. A check answer stands for one full check cycle plus a slow answer:
 * the longest gap between two answers, plus a second.
 *
 * - With the app open, a check may start every 15 s, timed from the previous start, and the 5 s
 *   status tick can add up to 5 s. 9 s more covers a check that answers slower than the one before
 *   it (one saved address timing out before the relay answers): 15 + 5 + 9 + 1 = 30 s.
 * - In the background a check starts 60 s after the previous one finished, the same budget as
 *   before, so the gap also includes the whole check: 60 + 5 + 14 + 1 = 80 s.
 */
data class ConnectionTiming(
    val foregroundLeaseMs: Long = 30_000L,
    val backgroundLeaseMs: Long = 80_000L,
    /** A drop from connected stays shown at least this long before connected returns. */
    val downDwellMs: Long = 1_000L,
    /** Not reaching the journal for this long, with a check failing in that time, reads offline. */
    val offlineAfterMs: Long = 30_000L,
    /** Coming to the foreground holds the last shown state at most this long while a check answers. */
    val foregroundGraceMs: Long = 500L,
    /** Syncing stays this long after the queue drains, so consecutive items don't strobe. */
    val syncingLingerMs: Long = 1_500L,
)

/**
 * The one place this phone decides connected, connecting or offline. Pure: every call takes the
 * time on one monotonic clock, and nothing here reads a clock, a network or a file.
 *
 * Evidence is [heard] (the journal answered a check, with any HTTP status or a verdict of its own)
 * and [unheard] (a check got no answer from it). Connected needs something heard within the lease,
 * and after a drop, something heard since the drop. One check that goes unanswered changes nothing
 * while the lease stands. No network at all is a hard drop and reads offline at once. Otherwise a
 * drop reads connecting, and offline only once the journal has not been reached for
 * [ConnectionTiming.offlineAfterMs] and a check has failed in that time. A new episode, connecting
 * again, starts at launch, when the network comes back, and when the app comes to the foreground.
 */
class ConnectionPresenter(
    startMs: Long,
    private val timing: ConnectionTiming = ConnectionTiming(),
) {
    private var foreground = false
    private var networkPresent = true
    private var lastHeardMs: Long? = null
    private var lastUnheardMs: Long? = null
    private var connected = false
    private var episodeStartMs = startMs
    // The latest launch, network return or foreground entry. No episode starts before it.
    private var episodeFloorMs = startMs
    private var downShownMs: Long? = null
    private var graceUntilMs: Long? = null
    private var lastShown: PresentedConnection? = null
    private var lingerCount = 0
    private var drainSeenMs: Long? = null

    fun heard(nowMs: Long) {
        lastHeardMs = nowMs
    }

    fun unheard(nowMs: Long) {
        lastUnheardMs = nowMs
    }

    fun network(present: Boolean, nowMs: Long) {
        if (present && !networkPresent) newEpisode(nowMs)
        networkPresent = present
    }

    fun foreground(inForeground: Boolean, nowMs: Long) {
        if (inForeground && !foreground) {
            // An answer that landed just before, and no read has applied yet, still counts.
            truth(nowMs)
            graceUntilMs = nowMs + timing.foregroundGraceMs
            newEpisode(nowMs)
        }
        foreground = inForeground
    }

    /** The phone now answers to another pairing, or none: nothing heard about the old one counts. */
    fun reset(nowMs: Long) {
        lastHeardMs = null
        lastUnheardMs = null
        connected = false
        downShownMs = null
        graceUntilMs = null
        lastShown = null
        lingerCount = 0
        drainSeenMs = null
        newEpisode(nowMs)
    }

    fun present(nowMs: Long, pendingCount: Int): PresentedStatus {
        val truth = truth(nowMs)
        val graceUntil = graceUntilMs
        val held = lastShown
        val shown = if (graceUntil != null && nowMs < graceUntil && held != null && networkPresent && truth != PresentedConnection.CONNECTED) {
            held
        } else {
            truth
        }
        if (graceUntil != null && nowMs >= graceUntil) graceUntilMs = null
        if (shown != PresentedConnection.CONNECTED && lastShown == PresentedConnection.CONNECTED) downShownMs = nowMs
        lastShown = shown
        return PresentedStatus(shown, lingered(shown, pendingCount, nowMs))
    }

    /** When the presented state next changes on its own, or null when only new evidence changes it. */
    fun nextChangeAt(nowMs: Long): Long? {
        val candidates = buildList {
            graceUntilMs?.let(::add)
            val heard = lastHeardMs
            if (connected && heard != null) add(heard + lease())
            if (!connected) {
                downShownMs?.let { add(it + timing.downDwellMs) }
                if (failedSince(episodeStartMs)) add(episodeStartMs + timing.offlineAfterMs)
            }
            drainSeenMs?.let { add(it + timing.syncingLingerMs) }
        }
        return candidates.filter { it > nowMs }.minOrNull()
    }

    private fun truth(nowMs: Long): PresentedConnection {
        val heard = lastHeardMs
        val leaseHolds = networkPresent && heard != null && nowMs - heard < lease()
        if (connected && !leaseHolds) {
            connected = false
            val droppedAt = if (networkPresent && heard != null) minOf(nowMs, heard + lease()) else nowMs
            episodeStartMs = maxOf(episodeFloorMs, droppedAt)
        }
        if (!connected && leaseHolds && heard != null && heard >= episodeStartMs) {
            val dwellDone = downShownMs?.let { nowMs - it >= timing.downDwellMs } ?: true
            if (dwellDone) {
                connected = true
                downShownMs = null
            }
        }
        return when {
            connected -> PresentedConnection.CONNECTED
            !networkPresent -> PresentedConnection.OFFLINE
            failedSince(episodeStartMs) && nowMs - episodeStartMs >= timing.offlineAfterMs -> PresentedConnection.OFFLINE
            else -> PresentedConnection.CONNECTING
        }
    }

    private fun lingered(shown: PresentedConnection, pendingCount: Int, nowMs: Long): Int {
        if (shown != PresentedConnection.CONNECTED) {
            lingerCount = 0
            drainSeenMs = null
            return pendingCount
        }
        if (pendingCount > 0) {
            lingerCount = pendingCount
            drainSeenMs = null
            return pendingCount
        }
        if (lingerCount == 0) return 0
        val drained = drainSeenMs ?: nowMs.also { drainSeenMs = it }
        if (nowMs - drained < timing.syncingLingerMs) return lingerCount
        lingerCount = 0
        drainSeenMs = null
        return 0
    }

    private fun failedSince(sinceMs: Long): Boolean = lastUnheardMs?.let { it >= sinceMs } ?: false

    private fun lease(): Long = if (foreground) timing.foregroundLeaseMs else timing.backgroundLeaseMs

    /** A connected state carries on; anything else starts counting toward offline again from now. */
    private fun newEpisode(nowMs: Long) {
        episodeFloorMs = nowMs
        if (!connected) episodeStartMs = nowMs
    }
}
