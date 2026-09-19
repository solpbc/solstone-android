// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import kotlin.test.Test
import kotlin.test.assertFalse

class PairingCanaryRedactionTest {
    private val canaries = listOf(
        "secret-private-key-data",
        "super-secret-device-jwt-token-12345",
        "https://relay.secret.domain.org",
        "192.168.123.45",
        "sha256:client-cert-secret-hash",
        "sha256:ca-cert-secret-hash",
        "nested-io-exception-trace-message",
    )

    @Test
    fun snapshotAndLeaseToStringOmitsCanaries() {
        val home = PairedHome(
            instanceId = "home-inst-123",
            homeLabel = "My Home",
            relayOrigin = "https://relay.secret.domain.org",
            caChainFingerprint = "sha256:ca-cert-secret-hash",
            clientCertFingerprint = "sha256:client-cert-secret-hash",
            observerHandle = "obs",
            deviceToken = "super-secret-device-jwt-token-12345",
            expiresAt = "2030-01-01T00:00:00Z",
            state = IdentityState.PAIRED,
        )
        val cred = ClientCredential(
            privateKeyPem = "secret-private-key-data",
            clientCertPem = "cert-data",
            caChainPem = listOf("ca-data"),
        )
        val endpoint = DirectEndpoint("192.168.123.45", 7657)

        val committed = PairingGraphSnapshot.Committed(
            sequenceNumber = 42,
            revisions = GraphRevisions(1, 2, 3),
            home = home,
            hasDirectEndpoint = true,
            directAssociated = true,
            relayLiveEligible = true,
        )
        val directLease = PairingLease.Direct(committed, cred, endpoint)
        val relayLease = PairingLease.Relay(committed, cred, "https://relay.secret.domain.org", "home-inst-123", "super-secret-device-jwt-token-12345")
        val mutationApplied = GraphMutationResult.Applied(committed)
        val mutationFailed = GraphMutationResult.PersistenceFailed(RuntimeException("nested-io-exception-trace-message"))

        val outputs = listOf(
            committed.toString(),
            directLease.toString(),
            relayLease.toString(),
            mutationApplied.toString(),
            mutationFailed.toString(),
            PairingGraphSnapshot.Absent(1).toString(),
            PairingGraphSnapshot.Uncertain(2, PersistenceIssue.PERSISTENCE_FAILED).toString(),
        )

        for (output in outputs) {
            for (canary in canaries) {
                assertFalse(
                    output.contains(canary),
                    "String output '$output' leaked canary '$canary'",
                )
            }
        }
    }
}
