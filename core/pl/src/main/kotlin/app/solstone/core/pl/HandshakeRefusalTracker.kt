// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

/**
 * The persisted handshake-refusal state for one client credential.
 *
 * [credential] is the client certificate fingerprint it belongs to, so a new pairing (a new
 * certificate) starts from zero. [lastCountedAtMillis] is the wall-clock time of the last counted
 * refusal. [stop] never clears; only a new pairing leaves it behind.
 */
data class RefusalRecord(
    val credential: String,
    val refusals: Int = 0,
    val lastCountedAtMillis: Long? = null,
    val stop: HandshakeStop? = null,
)

/** One read of the refusal record: absent (start from zero) is not the same as failed. */
sealed interface RefusalRecordRead {
    data object Absent : RefusalRecordRead
    data class Present(val record: RefusalRecord) : RefusalRecordRead
    data class Failed(val cause: Throwable) : RefusalRecordRead
}

/** Where the refusal record lives: a record of its own, never a field of the pairing record. */
interface RefusalRecordStore {
    fun read(): RefusalRecordRead

    /** Write [record] durably, or throw. */
    fun write(record: RefusalRecord)
}

/**
 * The refusal rule for every credential this process dials, over a persisted [RefusalRecordStore].
 *
 * Feed it each attempt's outcome: [accepted] when the journal answered anything at all, [failed]
 * with the classified failure otherwise. Only acceptance clears the count, and nothing but a new
 * pairing clears a stop.
 *
 * Within this process the spacing between counted refusals runs on [monotonicMillis]; across
 * processes it runs on the persisted [wallMillis] time, and a stored time later than now (the clock
 * moved back) lets the next refusal count. When the record cannot be read, an access denied still
 * stops in this process and every outcome is kept, in order, to be replayed against the record on
 * the next good read; a record that could not be read is never written over blind. A failed write is
 * reported through [report] and its outcomes are kept for the next write. Every update is serialised.
 */
class HandshakeRefusalTracker(
    private val store: RefusalRecordStore,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val report: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private sealed interface Event {
        val credential: String
        val wallAt: Long
        val monoAt: Long
    }

    private data class Accepted(
        override val credential: String,
        override val wallAt: Long,
        override val monoAt: Long,
    ) : Event

    private data class Failed(
        override val credential: String,
        val failure: HandshakeFailure,
        override val wallAt: Long,
        override val monoAt: Long,
    ) : Event

    /** The wall time a counted refusal was stored with, and the monotonic time it happened at. */
    private data class MonotonicAnchor(val credential: String, val wallAt: Long, val monoAt: Long)

    private val lock = Any()
    private val pending = ArrayList<Event>()
    private var anchor: MonotonicAnchor? = null
    private val inProcessStops = HashMap<String, HandshakeStop>()

    /** The journal accepted [credential]: it sent application data. */
    fun accepted(credential: String) {
        record(Accepted(credential, wallMillis(), monotonicMillis()))
    }

    /** Record one failed attempt for [credential]; returns the stop, if the credential is stopped. */
    fun failed(credential: String, failure: HandshakeFailure): HandshakeStop? =
        record(Failed(credential, failure, wallMillis(), monotonicMillis()))

    /** Why [credential] is stopped, if it is. Also applies any outcomes still waiting for the store. */
    fun stop(credential: String): HandshakeStop? = synchronized(lock) {
        val applied = settleLocked()
        applied?.takeIf { it.credential == credential }?.stop ?: inProcessStops[credential]
    }

    /** The persisted record for [credential], applying anything pending; null if absent or unreadable. */
    fun current(credential: String): RefusalRecord? = synchronized(lock) {
        settleLocked()?.takeIf { it.credential == credential }
    }

    private fun record(event: Event): HandshakeStop? = synchronized(lock) {
        pending.add(event)
        if (event is Failed && event.failure == HandshakeFailure.TLS_ACCESS_DENIED) {
            // An access denied stops this process even when the record cannot be read.
            if (event.credential !in inProcessStops) inProcessStops[event.credential] = HandshakeStop.TLS_ACCESS_DENIED
        }
        val applied = settleLocked()
        val stored = applied?.takeIf { it.credential == event.credential }?.stop
        if (stored != null && event.credential !in inProcessStops) inProcessStops[event.credential] = stored
        stored ?: inProcessStops[event.credential]
    }

    /**
     * Replay every pending outcome against the stored record and write the result. Returns the
     * record as it now stands, or null when it could not be read (outcomes stay pending).
     */
    private fun settleLocked(): RefusalRecord? {
        val stored = when (val read = store.read()) {
            RefusalRecordRead.Absent -> null
            is RefusalRecordRead.Present -> read.record
            is RefusalRecordRead.Failed -> {
                if (pending.isNotEmpty()) report("refusal record unreadable; keeping outcomes", read.cause)
                return null
            }
        }
        if (pending.isEmpty()) return stored
        var record = stored
        var anchorNow = anchor
        for (event in pending) {
            val base = record?.takeIf { it.credential == event.credential } ?: RefusalRecord(event.credential)
            if (anchorNow != null && anchorNow.credential != event.credential) anchorNow = null
            val (next, nextAnchor) = apply(base, event, anchorNow)
            record = next
            anchorNow = nextAnchor
        }
        val result = requireNotNull(record)
        if (result != stored) {
            try {
                store.write(result)
            } catch (e: Exception) {
                report("refusal record write failed; keeping outcomes", e)
                return result
            }
        }
        pending.clear()
        anchor = anchorNow
        return result
    }

    private fun apply(record: RefusalRecord, event: Event, anchorNow: MonotonicAnchor?): Pair<RefusalRecord, MonotonicAnchor?> =
        when (event) {
            is Accepted -> record.copy(refusals = 0, lastCountedAtMillis = null) to null
            is Failed -> when (event.failure) {
                HandshakeFailure.TLS_ACCESS_DENIED -> latch(record, HandshakeStop.TLS_ACCESS_DENIED) to anchorNow
                HandshakeFailure.TLS_REFUSED -> {
                    if (counts(record, event, anchorNow)) {
                        val refusals = record.refusals + 1
                        val counted = record.copy(refusals = refusals, lastCountedAtMillis = event.wallAt)
                        val next = if (refusals >= REFUSAL_LIMIT) latch(counted, HandshakeStop.REFUSALS_EXHAUSTED) else counted
                        next to MonotonicAnchor(event.credential, event.wallAt, event.monoAt)
                    } else {
                        record to anchorNow
                    }
                }
                HandshakeFailure.UNREACHABLE,
                HandshakeFailure.TLS_CERTIFICATE_UNKNOWN,
                HandshakeFailure.UNKNOWN_JOURNAL,
                -> record to anchorNow
            }
        }

    private fun counts(record: RefusalRecord, event: Failed, anchorNow: MonotonicAnchor?): Boolean {
        val last = record.lastCountedAtMillis ?: return true
        // Counted in this process: the monotonic clock decides, whatever the wall clock did.
        if (anchorNow != null && anchorNow.credential == event.credential && anchorNow.wallAt == last) {
            return event.monoAt - anchorNow.monoAt >= REFUSAL_SPACING_MILLIS
        }
        // A stored time in the future means the clock moved back: count, and store the current time.
        if (last > event.wallAt) return true
        return event.wallAt - last >= REFUSAL_SPACING_MILLIS
    }

    private fun latch(record: RefusalRecord, stop: HandshakeStop): RefusalRecord =
        if (record.stop == null) record.copy(stop = stop) else record
}
