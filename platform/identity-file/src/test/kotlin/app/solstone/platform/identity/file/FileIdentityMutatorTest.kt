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

    @Test
    fun postRenameFailureDoesNotSilentlyPromoteUncertainToCurrentAccepted() {
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
            home.copy(deviceToken = "token-new")
        }

        assertTrue(result is AccessMutationResult.DurabilityUncertain)
        assertEquals(app.solstone.core.identity.PersistenceIssue.DURABILITY_UNCERTAIN, mutator.lastPersistenceIssue())
        // current() must still return old accepted snapshot, not uncertain token-new
        assertEquals("token-1", mutator.current()?.deviceToken)
        assertFalse(mutator.isRelayLiveEligible())
    }

    @Test
    fun installNewPairingFailureClassifiesPersistenceIssue() {
        val file = File(temp.root, "identity.tsv")
        val preRenameFailingWriter = object : AtomicFileWriter {
            override fun write(target: File, bytes: ByteArray) {
                throw IOException("disk full before write")
            }
        }
        val store = FileIdentityStore(file, SpySecretProtector(), fileWriter = preRenameFailingWriter)
        val mutator = FileIdentityMutator(store)

        val home = createHome("jid-1", "https://relay.solstone.app", "token-1")
        val success = mutator.installNewPairing(home)
        assertFalse(success)
        assertEquals(app.solstone.core.identity.PersistenceIssue.PERSISTENCE_FAILED, mutator.lastPersistenceIssue())
        assertEquals(null, mutator.current())
        assertFalse(mutator.isRelayLiveEligible())
    }

    @Test
    fun externalDiskReplacementWithRelayCredentialsDoesNotSetLiveEligibleViaCurrent() {
        val file = File(temp.root, "identity.tsv")
        val store = FileIdentityStore(file, SpySecretProtector())
        val mutator = FileIdentityMutator(store)

        // External process writes new home with relay to disk
        val diskHome = createHome("jid-1", "https://relay.solstone.app", "token-1")
        store.save(diskHome)

        val current = mutator.current()
        assertEquals("jid-1", current?.instanceId)
        assertEquals("https://relay.solstone.app", current?.relayOrigin)
        assertEquals("token-1", current?.deviceToken)
        assertFalse(mutator.isRelayLiveEligible())
    }

    @Test
    fun appliedClearSetsLiveIneligible() {
        val file = File(temp.root, "identity.tsv")
        val store = FileIdentityStore(file, SpySecretProtector())
        val mutator = FileIdentityMutator(store)

        val initial = createHome("jid-1", "https://relay.solstone.app", "token-1")
        mutator.installNewPairing(initial)
        assertTrue(mutator.isRelayLiveEligible())

        val pairingGen = mutator.currentPairingGeneration()!!
        val accessGen = mutator.currentAccessMutationGen()
        val result = mutator.mutate(pairingGen, accessGen) { home ->
            home.copy(relayOrigin = null, deviceToken = null)
        }

        assertTrue(result is AccessMutationResult.Applied)
        assertFalse(mutator.isRelayLiveEligible())
        assertEquals(null, mutator.current()?.relayOrigin)
    }

    @Test
    fun rePairWithSameHomeInvalidatesOldMutateWithConflict() {
        val file = File(temp.root, "identity.tsv")
        val store = FileIdentityStore(file, SpySecretProtector())
        val mutator = FileIdentityMutator(store)

        val home1 = createHome("jid-1").copy(clientCertFingerprint = "sha256:cert1")
        mutator.installNewPairing(home1)

        val pairingGen1 = mutator.currentPairingGeneration()!!
        val accessGen1 = mutator.currentAccessMutationGen()

        // Re-pair with same jid but new cert fingerprint
        val home2 = createHome("jid-1").copy(clientCertFingerprint = "sha256:cert2")
        mutator.installNewPairing(home2)

        val result = mutator.mutate(pairingGen1, accessGen1) { home ->
            home.copy(deviceToken = "token-new")
        }

        assertTrue(result is AccessMutationResult.Conflict)
    }
    @Test
    fun failedClearFencesOldRefreshAndNewerReadyWins() {
        var fail = false
        val writer = object : AtomicFileWriter {
            override fun write(target: File, bytes: ByteArray) {
                if (fail) throw IOException("disk full")
                AtomicFileWriter.Default.write(target, bytes)
            }
        }
        val store = FileIdentityStore(File(temp.root, "clear.tsv"), SpySecretProtector(), fileWriter = writer)
        val mutator = FileIdentityMutator(store)
        mutator.installNewPairing(createHome("jid-1", "https://relay.solstone.app", "token-old"))
        val before = mutator.accessSnapshot()!!
        fail = true
        val failed = mutator.clearRelayAccess(before)
        assertTrue(failed is AccessMutationResult.PersistenceFailed)
        assertFalse(mutator.isRelayLiveEligible())
        val disabled = mutator.accessSnapshot()!!
        assertTrue(disabled.generation > before.generation)
        fail = false
        val oldRefresh = mutator.mutateIfCurrent(before) { it.copy(deviceToken = "token-stale") }
        assertTrue(oldRefresh is AccessMutationResult.Conflict)
        assertFalse(mutator.isRelayLiveEligible())
        val ready = mutator.mutateIfCurrent(disabled) { it.copy(relayOrigin = "https://new.example", deviceToken = "token-ready") }
        assertTrue(ready is AccessMutationResult.Applied)
        assertTrue(mutator.clearRelayAccess(disabled) is AccessMutationResult.Conflict)
        assertEquals("token-ready", store.load()?.deviceToken)
        assertTrue(mutator.isRelayLiveEligible())
    }

    @Test
    fun obsoleteClearHasNoLiveOrDurableEffect() {
        val store = FileIdentityStore(File(temp.root, "obsolete.tsv"), SpySecretProtector())
        val mutator = FileIdentityMutator(store)
        mutator.installNewPairing(createHome("jid-1", "https://relay.solstone.app", "token-current"))
        val snapshot = mutator.accessSnapshot()!!
        assertTrue(mutator.clearRelayAccess(snapshot) { false } is AccessMutationResult.Conflict)
        assertEquals(snapshot, mutator.accessSnapshot())
        assertEquals(snapshot.home, store.load())
    }

}
