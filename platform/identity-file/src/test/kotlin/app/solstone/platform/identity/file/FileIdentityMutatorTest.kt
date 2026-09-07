// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.AccessMutationResult
import app.solstone.core.identity.AtomicFileWriter
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileIdentityMutatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun createHome(jid: String, relay: String? = null, token: String? = null) = PairedHome(
        instanceId = jid,
        homeLabel = "Home",
        relayOrigin = relay,
        caChainFingerprint = "sha256:ca1",
        clientCertFingerprint = "sha256:cert1",
        observerHandle = null,
        deviceToken = token,
        expiresAt = null,
        state = IdentityState.PAIRED,
    )

    @Test
    fun installNewPairingInitializesGenerationsAndRelayEligibility() {
        val file = File(temp.root, "identity.tsv")
        val store = FileIdentityStore(file, SpySecretProtector())
        val mutator = FileIdentityMutator(store)

        val homeWithRelay = createHome("jid-1", "https://relay.solstone.app", "token-1")
        mutator.installNewPairing(homeWithRelay)

        assertEquals("jid-1", mutator.current()?.instanceId)
        assertTrue(mutator.isRelayLiveEligible())
        assertEquals(PairingGeneration("jid-1", "sha256:cert1"), mutator.currentPairingGeneration())
    }

    @Test
    fun installNewPairingWithoutRelayMarksIneligible() {
        val file = File(temp.root, "identity.tsv")
        val store = FileIdentityStore(file, SpySecretProtector())
        val mutator = FileIdentityMutator(store)

        val homeWithoutRelay = createHome("jid-1", null, null)
        mutator.installNewPairing(homeWithoutRelay)

        assertFalse(mutator.isRelayLiveEligible())
    }

    @Test
    fun mutateSucceedsAndIncrementsGeneration() {
        val file = File(temp.root, "identity.tsv")
        val store = FileIdentityStore(file, SpySecretProtector())
        val mutator = FileIdentityMutator(store)

        val initial = createHome("jid-1", null, null)
        mutator.installNewPairing(initial)

        val pairingGen = mutator.currentPairingGeneration()!!
        val accessGen = mutator.currentAccessMutationGen()
        val result = mutator.mutate(pairingGen, accessGen) { home ->
            home.copy(relayOrigin = "https://relay.solstone.app", deviceToken = "token-123")
        }

        assertTrue(result is AccessMutationResult.Applied)
        assertEquals(accessGen + 1, result.accessMutationGen)
        assertEquals("https://relay.solstone.app", mutator.current()?.relayOrigin)
        assertEquals("token-123", mutator.current()?.deviceToken)
        assertEquals("https://relay.solstone.app", store.load()?.relayOrigin)
    }

    @Test
    fun mutateRejectsConflictingGeneration() {
        val file = File(temp.root, "identity.tsv")
        val store = FileIdentityStore(file, SpySecretProtector())
        val mutator = FileIdentityMutator(store)

        val initial = createHome("jid-1", null, null)
        mutator.installNewPairing(initial)

        val wrongGen = PairingGeneration("jid-other", "sha256:wrong")
        val accessGen = mutator.currentAccessMutationGen()
        val result = mutator.mutate(wrongGen, accessGen) { home ->
            home.copy(deviceToken = "token")
        }

        assertTrue(result is AccessMutationResult.Conflict)
    }

    @Test
    fun preRenameFailureReturnsPersistenceFailedAndKeepsLiveIneligible() {
        val file = File(temp.root, "identity.tsv")
        val failingWriter = object : AtomicFileWriter {
            override fun write(target: File, bytes: ByteArray) {
                throw IOException("disk full before rename")
            }
        }
        val initial = createHome("jid-1", "https://relay.solstone.app", "token-1")
        FileIdentityStore(file, SpySecretProtector()).save(initial)

        val failingStore = FileIdentityStore(file, SpySecretProtector(), fileWriter = failingWriter)
        val mutator = FileIdentityMutator(failingStore)

        val pairingGen = mutator.currentPairingGeneration()!!
        val accessGen = mutator.currentAccessMutationGen()
        val result = mutator.mutate(pairingGen, accessGen) { home ->
            home.copy(relayOrigin = null, deviceToken = null)
        }

        assertTrue(result is AccessMutationResult.PersistenceFailed)
        assertFalse(mutator.isRelayLiveEligible())
    }

    @Test
    fun postRenameFailureReturnsDurabilityUncertainAndRetainsFields() {
        val file = File(temp.root, "identity.tsv")
        val throwingAfterWriteWriter = object : AtomicFileWriter {
            override fun write(target: File, bytes: ByteArray) {
                AtomicFileWriter.Default.write(target, bytes)
                throw IOException("post-rename fsync failure")
            }
        }
        val initial = createHome("jid-1", "https://relay.solstone.app", "token-1")
        FileIdentityStore(file, SpySecretProtector()).save(initial)

        val store = FileIdentityStore(file, SpySecretProtector(), fileWriter = throwingAfterWriteWriter)
        val mutator = FileIdentityMutator(store)

        val pairingGen = mutator.currentPairingGeneration()!!
        val accessGen = mutator.currentAccessMutationGen()
        val result = mutator.mutate(pairingGen, accessGen) { home ->
            home.copy(relayOrigin = null, deviceToken = null)
        }

        assertTrue(result is AccessMutationResult.DurabilityUncertain)
        assertFalse(mutator.isRelayLiveEligible())
        // New bytes on disk are loadable
        val reloaded = FileIdentityStore(file, SpySecretProtector()).load()
        assertEquals("jid-1", reloaded?.instanceId)
        assertEquals("sha256:ca1", reloaded?.caChainFingerprint)
        assertEquals("sha256:cert1", reloaded?.clientCertFingerprint)
        assertEquals(null, reloaded?.relayOrigin)
        assertEquals(null, reloaded?.deviceToken)
    }

    @Test
    fun disableRelayLiveSetsFlagInMemory() {
        val file = File(temp.root, "identity.tsv")
        val store = FileIdentityStore(file, SpySecretProtector())
        val mutator = FileIdentityMutator(store)

        val initial = createHome("jid-1", "https://relay.solstone.app", "token-1")
        mutator.installNewPairing(initial)
        assertTrue(mutator.isRelayLiveEligible())

        mutator.disableRelayLive()
        assertFalse(mutator.isRelayLiveEligible())
    }
}
