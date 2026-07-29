// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.RelayDialObserver
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelayPlTransportObserverTest {
    @Test
    fun dialObserverFiresImmediatelyBeforeOpener() {
        val events = mutableListOf<String>()
        val expected = IOException("stop at opener")

        val actual = assertFailsWith<IOException> {
            openRelayClient(
                host = "relay.example",
                port = 443,
                tunnelOpener = TunnelOpener {
                    events += "open"
                    throw expected
                },
                tlsMode = RelayTlsMode.Certless,
                dialObserver = RelayDialObserver { attempt, host, port ->
                    events += "dial:$attempt:$host:$port"
                },
            )
        }

        assertEquals(expected, actual)
        assertEquals(listOf("dial:1:relay.example:443", "open"), events)
    }

    @Test
    fun eachProductionOpenHasOneMonotonicAttempt() {
        val attempts = mutableListOf<Int>()
        repeat(2) {
            assertFailsWith<IOException> {
                openRelayClient(
                    host = "relay.example",
                    port = 443,
                    tunnelOpener = TunnelOpener { throw IOException("expected") },
                    tlsMode = RelayTlsMode.Certless,
                    dialObserver = RelayDialObserver { attempt, _, _ -> attempts += attempt },
                )
            }
        }

        assertEquals(listOf(1, 1), attempts)
        assertEquals(attempts.sorted(), attempts)
    }

    @Test
    fun nullObserverLeavesOpenerFailureUnchanged() {
        val expected = IOException("same failure")

        val actual = assertFailsWith<IOException> {
            openRelayClient(
                host = "relay.example",
                port = 443,
                tunnelOpener = TunnelOpener { throw expected },
                tlsMode = RelayTlsMode.Certless,
            )
        }

        assertEquals(expected, actual)
    }
}
