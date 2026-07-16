// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.IdentityState
import app.solstone.core.pl.DirectEndpoint
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessPlStatusTextTest {
    @Test
    fun rendersEveryStatusWithoutUnknownOrEmptyDetail() {
        assertEquals("Not paired", plStatusText(HarnessPlStatus.NotPaired))
        assertEquals("Reachable (HTTP 200)", plStatusText(HarnessPlStatus.Reachable(200)))
        assertEquals(
            "Paired but unreachable: IOException: refused",
            plStatusText(HarnessPlStatus.PairedButUnreachable("IOException: refused")),
        )
        val fallback = "Paired but unreachable: connection failed (no further detail)"
        assertEquals(fallback, plStatusText(HarnessPlStatus.PairedButUnreachable(null)))
        assertEquals(fallback, plStatusText(HarnessPlStatus.PairedButUnreachable("")))
        assertEquals(fallback, plStatusText(HarnessPlStatus.PairedButUnreachable("  ")))
    }

    @Test
    fun exceptionDetailIncludesTypeAndOnlyNonblankMessage() {
        assertEquals("IOException: refused", plFailureDetail(IOException("refused")))
        assertEquals("IOException", plFailureDetail(IOException()))
        assertEquals("IOException", plFailureDetail(IOException("")))
    }

    @Test
    fun probePreconditionDetailsStayStable() {
        assertEquals("missing credential", probe(null, pairedHome()).reason())
        assertEquals("missing identity", probe(credential(), null, DirectEndpoint("10.0.0.2", 7657)).reason())
        assertEquals("identity not paired", probe(credential(), pairedHome(IdentityState.REVOKED)).reason())
        assertEquals("missing endpoint", probe(credential(), pairedHome()).reason())
        assertEquals("missing device token", probe(credential(), pairedHome(relayOrigin = "https://relay.example")).reason())
    }

    private fun probe(
        credential: app.solstone.core.identity.ClientCredential?,
        identity: app.solstone.core.model.PairedHome?,
        endpoint: DirectEndpoint? = null,
    ): HarnessPlStatus = RealPlStatusProbe(
        FakeEndpointStore(endpoint),
        FakeCredentialStore(credential),
        FakeIdentityStore(identity),
    ).probe()

    private fun HarnessPlStatus.reason(): String? = (this as HarnessPlStatus.PairedButUnreachable).reason
}
