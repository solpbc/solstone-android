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
}
