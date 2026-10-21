// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.PairingGeneration
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalAttemptTest {
    private val pairing = PairingGeneration("home", "sha256:client")

    // Falsified by an unknown journal ending the attempt: the relay would never be tried for a
    // saved address that now holds another journal.
    @Test
    fun legsWithoutAVerdictMoveOnAndAnUnknownJournalOutranksUnreachable() {
        val attempt = JournalAttempt.untracked(pairing)
        assertEquals(LegOutcome.NEXT_LEG, attempt.legFailed(UnknownJournalException("10.0.0.5:7657", "claimed-id")))
        assertEquals(LegOutcome.NEXT_LEG, attempt.legFailed(ConnectException("refused")))
        assertEquals(LegOutcome.NEXT_LEG, attempt.legFailed(SSLHandshakeException("Received fatal alert: access_denied")))
        assertFalse(attempt.decided)
        assertEquals(HandshakeFailure.UNKNOWN_JOURNAL, attempt.failureWithoutVerdict)
        assertEquals(listOf("10.0.0.5:7657"), attempt.sightings.map { it.address })
    }

    // Falsified by dialling on after a verdict: a LAN refusal would also be tried at the relay and
    // counted twice, or hidden by the relay's own failure.
    @Test
    fun theFirstVerdictDecidesTheAttempt() {
        val attempt = JournalAttempt.untracked(pairing)
        assertEquals(LegOutcome.NEXT_LEG, attempt.legFailed(UnknownJournalException(null, null)))
        assertEquals(LegOutcome.DECIDED, attempt.legFailed(JournalRefusalException(HandshakeFailure.TLS_REFUSED, 80)))
        assertTrue(attempt.decided)
        assertEquals(HandshakeFailure.TLS_REFUSED, attempt.refusal)
        // A later verdict on the same attempt changes nothing.
        attempt.onRefused(JournalRefusalException(HandshakeFailure.TLS_ACCESS_DENIED, 49))
        assertEquals(HandshakeFailure.TLS_REFUSED, attempt.refusal)
        // The earlier sighting is still reported.
        assertEquals(1, attempt.sightings.size)
    }

    @Test
    fun anAnswerDecidesTheAttemptAsAcceptance() {
        val attempt = JournalAttempt.untracked(pairing)
        attempt.onAccepted()
        assertTrue(attempt.decided)
        assertNull(attempt.refusal)
        assertEquals(LegOutcome.DECIDED, attempt.legFailed(ConnectException("late")))
    }
}
