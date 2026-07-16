// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.LocalIPv4Interface
import app.solstone.core.pl.orderCandidatesBySubnet
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class DirectPairEndpointFailureTest {
    @Test
    fun finalFailureReportsEndpointAfterCandidateReordering() {
        val candidates = listOf(
            DirectEndpoint("10.0.1.2", 7657),
            DirectEndpoint("10.0.0.2", 8765),
        )
        val ordered = orderCandidatesBySubnet(candidates, listOf(LocalIPv4Interface("10.0.0.8", 24)))
        val cause = ConnectException("refused")

        val failure = assertIs<DirectPairEndpointException>(directPairFailure(ordered.last(), cause))

        assertEquals("10.0.1.2", failure.endpointHost)
        assertEquals(7657, failure.endpointPort)
        assertSame(cause, failure.cause)
    }
}
