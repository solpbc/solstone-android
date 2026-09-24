// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.AccessMutationResult
import app.solstone.core.identity.AtomicFileWriter
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.platform.identity.file.FilePairingGraph
import app.solstone.platform.identity.file.SecretProtector
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PublisherIdentityMutatorAdapterTest {
    @get:Rule val temp = TemporaryFolder()
    private val protector = object : SecretProtector {
        override fun protect(plaintext: ByteArray) = plaintext
        override fun unprotect(wrapped: ByteArray) = wrapped
    }

    private fun graph(writer: AtomicFileWriter = AtomicFileWriter.Default) = FilePairingGraph(
        File(temp.root, "identity.tsv"), File(temp.root, "credential.pem"),
        File(temp.root, "endpoint.txt"), File(temp.root, "pairing.commit"), protector, writer,
    )

    private fun seed(graph: FilePairingGraph) {
        val home = PairedHome(
            instanceId = "home", homeLabel = "Home", relayOrigin = "https://link.solstone.app",
            caChainFingerprint = "sha256:ca", clientCertFingerprint = "sha256:cert",
            observerHandle = "phone", deviceToken = "token", expiresAt = null, state = IdentityState.PAIRED,
        )
        val credential = ClientCredential(
            "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
            "-----BEGIN CERTIFICATE-----\ncert\n-----END CERTIFICATE-----\n",
            listOf("-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----\n"),
        )
        assertIs<GraphMutationResult.Applied>(graph.installOrReplace(home, credential, DirectEndpoint("10.0.0.1", 7657), true))
    }

    @Test
    fun clearPublishesOneAcceptedRevocation() {
        val graph = graph()
        seed(graph)
        val mutator = PublisherIdentityMutatorAdapter(graph)
        val before = requireNotNull(mutator.accessSnapshot())
        val result = assertIs<AccessMutationResult.Applied>(mutator.clearRelayAccess(before))
        assertEquals(before.generation + 1, result.accessMutationGen)
        assertEquals(null, result.home.deviceToken)
        assertFalse((graph().currentSnapshot() as PairingGraphSnapshot.Committed).isRelayEligible)
    }

    @Test
    fun failedClearReturnsTheDisabledRevisionForRetry() {
        var fail = false
        val graph = graph(AtomicFileWriter { target, bytes ->
            if (fail && target.name.endsWith(".bak")) throw IOException("disk unavailable")
            AtomicFileWriter.Default.write(target, bytes)
        })
        seed(graph)
        val mutator = PublisherIdentityMutatorAdapter(graph)
        val before = requireNotNull(mutator.accessSnapshot())
        fail = true
        val failed = assertIs<AccessMutationResult.PersistenceFailed>(mutator.clearRelayAccess(before))
        assertEquals(before.generation + 1, failed.accessMutationGen)
        assertFalse(mutator.isRelayLiveEligible())
        assertTrue((graph.currentSnapshot() as PairingGraphSnapshot.Committed).isDirectEligible)
        fail = false
        val retry = mutator.mutate(before.pairing, requireNotNull(failed.accessMutationGen)) {
            it.copy(relayOrigin = null, deviceToken = null, expiresAt = null)
        }
        assertIs<AccessMutationResult.Applied>(retry)
        assertEquals(null, (graph().currentSnapshot() as PairingGraphSnapshot.Committed).home.deviceToken)
    }

    @Test
    fun staleClearPreservesNewerAccess() {
        val graph = graph()
        seed(graph)
        val mutator = PublisherIdentityMutatorAdapter(graph)
        val before = requireNotNull(mutator.accessSnapshot())
        assertIs<GraphMutationResult.Applied>(graph.updateRelayAccess(before.pairing, "https://link.solstone.app", "new", null))
        assertIs<AccessMutationResult.Conflict>(mutator.clearRelayAccess(before))
        assertEquals("new", mutator.current()?.deviceToken)
        assertTrue(mutator.isRelayLiveEligible())
    }
}
