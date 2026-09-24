// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.AccessMutationResult
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.JournalVersionRecord
import app.solstone.core.identity.JournalVersionStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PersistenceIssue
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.ClientReportedDescription
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.JournalVersionRefreshCoordinator
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.RelayAccessRefreshCoordinator
import app.solstone.core.identity.ObtainResult
import app.solstone.core.identity.PushKeyAccess
import app.solstone.core.push.DistributorPort
import app.solstone.core.push.DistributorResolution
import app.solstone.core.push.PushRegistrationCoordinator
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleOptionalJobsTest {
    @Test
    fun mismatchPairingDoesNotScheduleCoordinators() {
        val snapshot = sampleHome()
        val mutator = FakeMutator(snapshot.copy(clientCertFingerprint = "sha256:different"))
        val (journalCoord, _) = createCoordinators(mutator)
        val (relayCoord, _) = createRelayCoordinator(mutator)
        val tempDir = Files.createTempDirectory("push-test").toFile()
        val fakePort = FakeDistributorPort()
        val pushCoord = PushRegistrationCoordinator(
            directory = tempDir,
            port = fakePort,
            enabled = true,
            pushKeys = FakePushKeys(),
            pairingNow = { mutator.currentPairingGeneration() },
            log = {},
            enqueue = {},
        )
        var clientOpened = false

        val scheduled = scheduleOptionalJobsIfPairingCurrent(
            snapshotIdentity = snapshot,
            mutator = mutator,
            journalVersionCoordinator = journalCoord,
            relayAccessCoordinator = relayCoord,
            pushRegistration = pushCoord,
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android") },
            openClient = {
                clientOpened = true
                FakePlClient()
            },
        )

        assertFalse(scheduled)
        assertFalse(clientOpened)
        assertFalse(fakePort.registerCalled)
    }

    @Test
    fun matchingPairingSchedulesCoordinatorsAndExecutes() {
        val snapshot = sampleHome()
        val mutator = FakeMutator(snapshot)
        val latch = CountDownLatch(1)
        val pushRequestedLatch = CountDownLatch(1)
        val (journalCoord, _) = createCoordinators(mutator)
        val (relayCoord, _) = createRelayCoordinator(mutator)
        val tempDir = Files.createTempDirectory("push-test").toFile()
        val fakePort = FakeDistributorPort()
        val pushCoord = PushRegistrationCoordinator(
            directory = tempDir,
            port = fakePort,
            enabled = true,
            pushKeys = FakePushKeys(),
            pairingNow = { mutator.currentPairingGeneration() },
            log = {},
            enqueue = {},
        )

        val scheduled = scheduleOptionalJobsIfPairingCurrent(
            snapshotIdentity = snapshot,
            mutator = mutator,
            journalVersionCoordinator = journalCoord,
            relayAccessCoordinator = relayCoord,
            pushRegistration = pushCoord,
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android") },
            openClient = {
                latch.countDown()
                FakePlClient { path ->
                    if (path == "/api/push/vapid-key") {
                        pushRequestedLatch.countDown()
                    }
                }
            },
        )

        assertTrue(scheduled)
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertTrue(pushRequestedLatch.await(3, TimeUnit.SECONDS))
    }

    private fun sampleHome(): PairedHome =
        PairedHome(
            instanceId = "home-123",
            homeLabel = "Home",
            relayOrigin = "https://link.solstone.app",
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = "obs",
            deviceToken = "token",
            expiresAt = null,
            state = IdentityState.PAIRED,
        )

    private fun createCoordinators(
        mutator: IdentityMutator,
    ): Pair<JournalVersionRefreshCoordinator, FakeJournalVersionStore> {
        val store = FakeJournalVersionStore()
        val coord = JournalVersionRefreshCoordinator(store)
        return coord to store
    }

    private fun createRelayCoordinator(
        mutator: IdentityMutator,
    ): Pair<RelayAccessRefreshCoordinator, IdentityMutator> {
        val coord = RelayAccessRefreshCoordinator(mutator)
        return coord to mutator
    }

    private class FakeJournalVersionStore(var record: JournalVersionRecord? = null) : JournalVersionStore {
        override fun load(): JournalVersionRecord? = record
        override fun save(record: JournalVersionRecord) {
            this.record = record
        }
        override fun clear() {
            this.record = null
        }
    }

    private class FakePlClient(
        private val onRequest: (path: String) -> Unit = {},
    ) : PlHttpClient {
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            onRequest(path)
            return HttpResponse(200, emptyMap(), """{"journal_name":"Test Journal","protocol_version":1,"revision":1,"reported":null,"owner_label":null,"display_label":"Phone","updated_at":null,"journal":{"name":"J","version":"1.0.0"}}""".toByteArray())
        }
    }

    private class FakeDistributorPort : DistributorPort {
        var registerCalled = false
        override fun resolveDefault(): DistributorResolution = DistributorResolution.Found("org.fake.distributor")
        override fun available(): List<String> = listOf("org.fake.distributor")
        override val ownPackage: String = "app.solstone.phone"
        override fun save(pkg: String) {}
        override fun register(vapidKey: String) {
            registerCalled = true
        }
        override fun unregister() {}
    }

    private class FakePushKeys : PushKeyAccess {
        override fun readPushKey(generation: PairingGeneration): ByteArray? = ByteArray(32)
        override fun obtainPushKey(generation: PairingGeneration): ObtainResult = ObtainResult.Obtained(ByteArray(32))
    }

    private class FakeMutator(var home: PairedHome?) : IdentityMutator {
        override fun current(): PairedHome? = home
        override fun currentPairingGeneration(): PairingGeneration? =
            home?.let { PairingGeneration(it.instanceId, it.clientCertFingerprint) }
        override fun currentAccessMutationGen(): Long = 0
        override fun isRelayLiveEligible(): Boolean = true
        override fun disableRelayLive() {}
        override fun lastPersistenceIssue(): PersistenceIssue? = null
        override fun installNewPairing(home: PairedHome): Boolean = true
        override fun mutate(
            expectedPairing: PairingGeneration,
            expectedAccessMutationGen: Long,
            transform: (PairedHome) -> PairedHome,
        ): AccessMutationResult {
            val h = home ?: return AccessMutationResult.Conflict("missing")
            val next = transform(h)
            home = next
            return AccessMutationResult.Applied(next, 1)
        }
    }
}
