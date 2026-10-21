// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `proto/session.md` § 7, as the reference library's refusal tracker states it, scenario by scenario. */
class HandshakeRefusalTrackerTest {
    private val credential = "sha256:client"
    private var now = 1_700_000_000_000L
    private val records = MemoryRecords()
    private val reports = mutableListOf<String>()
    private val tracker = HandshakeRefusalTracker(records, { now }, { now }, { message, _ -> reports += message })

    private fun advance(millis: Long) {
        now += millis
    }

    private fun refusals(): Int = tracker.current(credential)?.refusals ?: 0

    // Scenario 1. Falsified by counting every refusal: the retry burst below reaches the limit
    // within one spacing interval. Also falsified by a 299 s step counting, or a 300 s one not.
    @Test
    fun refusalsCountOncePerSpacingAndStopAtTheLimit() {
        repeat(REFUSAL_LIMIT * 3) {
            assertNull(tracker.failed(credential, HandshakeFailure.TLS_REFUSED))
        }
        assertEquals(1, refusals())

        for (counted in 2 until REFUSAL_LIMIT) {
            advance(REFUSAL_SPACING_MILLIS)
            assertNull(tracker.failed(credential, HandshakeFailure.TLS_REFUSED))
            assertEquals(counted, refusals())
        }

        advance(REFUSAL_SPACING_MILLIS - 1_000)
        assertNull(tracker.failed(credential, HandshakeFailure.TLS_REFUSED))
        assertEquals(REFUSAL_LIMIT - 1, refusals())

        advance(1_000)
        assertEquals(HandshakeStop.REFUSALS_EXHAUSTED, tracker.failed(credential, HandshakeFailure.TLS_REFUSED))
        assertEquals(REFUSAL_LIMIT, refusals())

        // A stop never clears, not even after the journal answers.
        tracker.accepted(credential)
        assertEquals(HandshakeStop.REFUSALS_EXHAUSTED, tracker.stop(credential))
    }

    // Scenario 2. Falsified by letting 46, an unreachable journal or an unknown journal reset the
    // count (it drops) or advance it (it grows), or by letting time offline advance it.
    @Test
    fun unreachableCertificateUnknownAndUnknownJournalNeitherAdvanceNorClear() {
        tracker.failed(credential, HandshakeFailure.TLS_REFUSED)
        for (failure in listOf(
            HandshakeFailure.UNREACHABLE,
            HandshakeFailure.TLS_CERTIFICATE_UNKNOWN,
            HandshakeFailure.UNKNOWN_JOURNAL,
        )) {
            repeat(REFUSAL_LIMIT * 2) {
                advance(REFUSAL_SPACING_MILLIS)
                assertNull(tracker.failed(credential, failure))
                assertEquals(1, refusals(), "$failure")
            }
        }
        // Two days offline between two refusals still advance the count by exactly one.
        advance(48L * 60 * 60 * 1000)
        assertNull(tracker.failed(credential, HandshakeFailure.TLS_REFUSED))
        assertEquals(2, refusals())
    }

    // Scenario 3. Falsified by clearing on anything but the journal's answer, or by keeping the
    // spacing after a clear (the next refusal would not count at once).
    @Test
    fun onlyTheJournalsAnswerClearsTheCount() {
        repeat(REFUSAL_LIMIT - 1) {
            tracker.failed(credential, HandshakeFailure.TLS_REFUSED)
            advance(REFUSAL_SPACING_MILLIS)
        }
        assertEquals(REFUSAL_LIMIT - 1, refusals())

        tracker.accepted(credential)
        assertEquals(0, refusals())

        tracker.failed(credential, HandshakeFailure.TLS_REFUSED)
        assertEquals(1, refusals())
        assertNull(tracker.stop(credential))
    }

    // Scenario 4. Falsified by counting 46, or by treating 49 as one more counted refusal.
    @Test
    fun accessDeniedStopsAndCertificateUnknownNeverDoes() {
        repeat(REFUSAL_LIMIT * 2) {
            advance(REFUSAL_SPACING_MILLIS)
            assertNull(tracker.failed(credential, HandshakeFailure.TLS_CERTIFICATE_UNKNOWN))
        }
        assertEquals(0, refusals())
        assertEquals(HandshakeStop.TLS_ACCESS_DENIED, tracker.failed(credential, HandshakeFailure.TLS_ACCESS_DENIED))
        assertEquals(HandshakeStop.TLS_ACCESS_DENIED, tracker.stop(credential))
    }

    // Falsified by a per-code counter: a journal alternating 80 with 42 would reset it forever.
    @Test
    fun differentRefusalCodesShareOneCount() {
        repeat(REFUSAL_LIMIT) { index ->
            val failure = refusalForAlert(ReceivedTlsAlert(if (index % 2 == 0) 80 else 42))
            tracker.failed(credential, failure)
            tracker.failed(credential, HandshakeFailure.UNREACHABLE)
            advance(REFUSAL_SPACING_MILLIS)
        }
        assertEquals(HandshakeStop.REFUSALS_EXHAUSTED, tracker.stop(credential))
    }

    // A new pairing is a new certificate, so it starts from zero. Falsified by keying the count on
    // anything that survives a re-pair.
    @Test
    fun aNewCredentialStartsFresh() {
        tracker.failed(credential, HandshakeFailure.TLS_ACCESS_DENIED)
        assertEquals(HandshakeStop.TLS_ACCESS_DENIED, tracker.stop(credential))
        assertNull(tracker.stop("sha256:repaired"))
        assertNull(tracker.failed("sha256:repaired", HandshakeFailure.TLS_REFUSED))
        assertEquals(1, tracker.current("sha256:repaired")?.refusals)
    }

    // Within a process spacing runs on the monotonic clock. Falsified by trusting a wall clock that
    // jumped ahead: two refusals a second apart would both count.
    @Test
    fun withinAProcessTheMonotonicClockDecidesTheSpacing() {
        var mono = 0L
        val local = HandshakeRefusalTracker(MemoryRecords(), { now }, { mono })
        local.failed(credential, HandshakeFailure.TLS_REFUSED)
        now += 24L * 60 * 60 * 1000
        mono += 1_000
        local.failed(credential, HandshakeFailure.TLS_REFUSED)
        assertEquals(1, local.current(credential)?.refusals)
        mono += REFUSAL_SPACING_MILLIS
        local.failed(credential, HandshakeFailure.TLS_REFUSED)
        assertEquals(2, local.current(credential)?.refusals)
    }

    // The example in the rule: stored 5 at T, the read fails, refusals at T+10 s and T+400 s, then
    // the read succeeds: the count is 6. Falsified by starting from zero on a failed read (it would
    // be 1 or 2) or by writing the in-memory count over the stored one.
    @Test
    fun aFailedReadKeepsOutcomesAndReplaysThemOnTheNextGoodRead() {
        val t = now
        records.record = RefusalRecord(credential, 5, t, null)
        records.failReads = true
        now = t + 10_000
        assertNull(tracker.failed(credential, HandshakeFailure.TLS_REFUSED))
        now = t + 400_000
        assertNull(tracker.failed(credential, HandshakeFailure.TLS_REFUSED))
        assertEquals(RefusalRecord(credential, 5, t, null), records.record, "never written blind")
        records.failReads = false
        assertEquals(6, tracker.current(credential)?.refusals)
        assertEquals(t + 400_000, records.record?.lastCountedAtMillis)
    }

    // Falsified by dropping a pending acceptance, or by applying it out of order.
    @Test
    fun aFailedReadThenAnAcceptanceThenAGoodReadStoresZero() {
        records.record = RefusalRecord(credential, 4, now, null)
        records.failReads = true
        advance(REFUSAL_SPACING_MILLIS)
        tracker.failed(credential, HandshakeFailure.TLS_REFUSED)
        tracker.accepted(credential)
        records.failReads = false
        assertEquals(0, tracker.current(credential)?.refusals)
        assertEquals(0, records.record?.refusals)
    }

    // A 49 still stops while the record cannot be read. Falsified by an in-memory stop that waits
    // for a good read: the device would keep dialling a journal that unpaired it.
    @Test
    fun accessDeniedStopsInProcessEvenWhenTheRecordCannotBeRead() {
        records.failReads = true
        assertEquals(HandshakeStop.TLS_ACCESS_DENIED, tracker.failed(credential, HandshakeFailure.TLS_ACCESS_DENIED))
        assertEquals(HandshakeStop.TLS_ACCESS_DENIED, tracker.stop(credential))
        records.failReads = false
        assertEquals(HandshakeStop.TLS_ACCESS_DENIED, records.recordAfter { tracker.stop(credential) }?.stop)
    }

    // A stored time later than now means the clock moved back. Falsified by a negative interval
    // that never reaches the spacing: refusals would stop counting until the clock caught up.
    @Test
    fun aStoredTimeInTheFutureStillCountsAndStoresNow() {
        records.record = RefusalRecord(credential, 2, now + 3L * 24 * 60 * 60 * 1000, null)
        tracker.failed(credential, HandshakeFailure.TLS_REFUSED)
        assertEquals(3, records.record?.refusals)
        assertEquals(now, records.record?.lastCountedAtMillis)
    }

    // Falsified by swallowing a failed write: nobody would learn the count is not durable.
    @Test
    fun aFailedWriteIsReportedAndItsOutcomesAreKept() {
        records.failWrites = true
        tracker.failed(credential, HandshakeFailure.TLS_REFUSED)
        assertTrue(reports.any { it.contains("write failed") }, "$reports")
        records.failWrites = false
        assertEquals(1, tracker.current(credential)?.refusals)
    }

    @Test
    fun theConstantsMatchTheReferenceLibrary() {
        assertEquals(7, REFUSAL_LIMIT)
        assertEquals(300_000L, REFUSAL_SPACING_MILLIS)
    }

    private class MemoryRecords : RefusalRecordStore {
        var record: RefusalRecord? = null
        var failReads = false
        var failWrites = false

        override fun read(): RefusalRecordRead = when {
            failReads -> RefusalRecordRead.Failed(IOException("unreadable"))
            record == null -> RefusalRecordRead.Absent
            else -> RefusalRecordRead.Present(record!!)
        }

        override fun write(record: RefusalRecord) {
            if (failWrites) throw IOException("disk full")
            this.record = record
        }

        fun recordAfter(block: () -> Unit): RefusalRecord? {
            block()
            return record
        }
    }
}
