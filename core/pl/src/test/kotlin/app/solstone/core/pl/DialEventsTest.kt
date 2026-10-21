// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.crypto.CaPinException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals

class DialEventsTest {
    @Test
    fun failuresMapToTheThreeOutcomes() {
        assertEquals(DialOutcome.NO_ANSWER, classifyDialFailure(SocketTimeoutException("connect timed out")))
        assertEquals(DialOutcome.NO_ANSWER, classifyDialFailure(NoRouteToHostException()))
        assertEquals(DialOutcome.NO_ANSWER, classifyDialFailure(UnknownHostException("journal.local")))
        assertEquals(DialOutcome.NOT_VERIFIED, classifyDialFailure(SSLPeerUnverifiedException("peer not verified")))
        assertEquals(DialOutcome.NOT_VERIFIED, classifyDialFailure(IOException("wrapped", CaPinException("pin"))))
        assertEquals(DialOutcome.FAILED, classifyDialFailure(ConnectException("refused")))
        assertEquals(DialOutcome.FAILED, classifyDialFailure(IllegalStateException("other")))
    }

    @Test
    fun addressesAreBracketedForIpv6() {
        assertEquals("192.168.1.20:7657", displayAddress("192.168.1.20", 7657))
        assertEquals("[fd00::1]:7657", displayAddress("fd00::1", 7657))
        assertEquals("[fe80::1%wlan0]:7657", displayAddress("fe80::1%wlan0", 7657))
        assertEquals("journal.local:7657", displayAddress("journal.local", 7657))
    }

    @Test
    fun onlyFailuresAndChangesAreLogged() {
        val lines = mutableListOf<String>()
        val log = DialEventLog { lines += it }

        log.record("192.168.1.20", 7657, DialOutcome.CONNECTED)
        log.record("192.168.1.20", 7657, DialOutcome.CONNECTED)
        log.recordFailure("192.168.1.20", 7657, SocketTimeoutException())
        log.record("192.168.1.20", 7657, DialOutcome.CONNECTED)
        log.record("link.solstone.app", 443, DialOutcome.CONNECTED)
        log.record("link.solstone.app", 443, DialOutcome.CONNECTED)

        assertEquals(
            listOf(
                "kind=dial host=192.168.1.20 port=7657 outcome=connected",
                "kind=dial host=192.168.1.20 port=7657 outcome=no-answer",
                "kind=dial host=192.168.1.20 port=7657 outcome=connected",
                "kind=dial host=link.solstone.app port=443 outcome=connected",
            ),
            lines,
        )
    }

    // Outcome 5: an unknown journal is written through the dial log, its journal ID labelled as a
    // claim. Falsified by logging it as "not-verified" (the owner-facing notice could not tell it
    // from a TLS failure) or by writing the peer's claim as if it were proven.
    @Test
    fun anUnknownJournalIsLoggedWithItsClaim() {
        val lines = mutableListOf<String>()
        val log = DialEventLog { lines += it }
        val id = "0b6c3d5e-1f2a-8b3c-9d4e-5f6a7b8c9d0e"
        log.recordFailure("192.168.1.20", 7657, IOException("dial", UnknownJournalException("192.168.1.20:7657", id)))
        log.recordFailure("link.solstone.app", 443, UnknownJournalException(null, null))
        log.recordUnknownJournal("192.168.1.21", 7657, "not a journal id; rm -rf")

        assertEquals(DialOutcome.UNKNOWN_JOURNAL, classifyDialFailure(UnknownJournalException(null, null)))
        assertEquals(
            listOf(
                "kind=dial host=192.168.1.20 port=7657 outcome=unknown-journal claimed-journal=$id",
                "kind=dial host=link.solstone.app port=443 outcome=unknown-journal claimed-journal=none",
                "kind=dial host=192.168.1.21 port=7657 outcome=unknown-journal claimed-journal=none",
            ),
            lines,
        )
    }
}
