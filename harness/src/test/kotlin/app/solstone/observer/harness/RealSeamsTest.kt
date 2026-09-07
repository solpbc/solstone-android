// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.JournalVersionRecord
import app.solstone.core.identity.JournalVersionStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.model.QueueState
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.JournalVersionFreshness
import app.solstone.core.pl.JournalVersionRefreshCoordinator
import app.solstone.core.pl.PlHttpClient
import app.solstone.platform.persistence.room.EventRow
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RealSeamsTest {

    @Test
    fun backlogStatusReaderReadsJournalVersionFromCoordinator() {
        val versionStore = TestJournalVersionStore()
        versionStore.save(JournalVersionRecord("inst-1", "fp-1", "0.9.5"))
        val coordinator = JournalVersionRefreshCoordinator(versionStore)
        val identityStore = TestIdentityStore()
        identityStore.save(
            PairedHome(
                instanceId = "inst-1",
                homeLabel = "home",
                relayOrigin = null,
                caChainFingerprint = "fp-1",
                clientCertFingerprint = "fp-client",
                observerHandle = "phone",
                deviceToken = null,
                expiresAt = null,
                state = IdentityState.PAIRED,
            ),
        )

        val reader = RealBacklogStatusReader(
            dao = TestSegmentDao(),
            plStatus = { HarnessPlStatus.Reachable(200) },
            identityStore = identityStore,
            coordinator = coordinator,
        )

        val status = reader.read()
        assertEquals("0.9.5", status.journalVersion?.version)
        assertEquals(JournalVersionFreshness.LAST_KNOWN, status.journalVersion?.freshness)
    }

    @Test
    fun backlogStatusReaderReflectsInvalidationFromPlStatusCall() {
        val versionStore = TestJournalVersionStore()
        versionStore.save(JournalVersionRecord("inst-1", "fp-1", "0.9.5"))
        val coordinator = JournalVersionRefreshCoordinator(versionStore)
        coordinator.onUsableConnection("inst-1", "fp-1") {
            object : PlHttpClient {
                override fun request(
                    method: String,
                    path: String,
                    headers: Map<String, String>,
                    body: ByteArray?,
                    maxResponseBytes: Int,
                ): HttpResponse = when (path) {
                    "/app/network/api/clients/self" -> HttpResponse(
                        200,
                        emptyMap(),
                        """{"protocol_version":1,"revision":1,"reported":null,"journal":{"name":"home","version":"0.9.5"},"owner_label":null,"display_label":"Phone","updated_at":null}""".toByteArray(),
                    )
                    else -> HttpResponse(200, emptyMap(), """{"version":{"current":"0.9.5"}}""".toByteArray())
                }
            }
        }
        Thread.sleep(100)
        assertEquals(JournalVersionFreshness.CURRENT, coordinator.currentReading("inst-1", "fp-1").freshness)

        val identityStore = TestIdentityStore()
        identityStore.save(
            PairedHome(
                instanceId = "inst-1",
                homeLabel = "home",
                relayOrigin = null,
                caChainFingerprint = "fp-1",
                clientCertFingerprint = "fp-client",
                observerHandle = "phone",
                deviceToken = null,
                expiresAt = null,
                state = IdentityState.PAIRED,
            ),
        )

        val reader = RealBacklogStatusReader(
            dao = TestSegmentDao(),
            plStatus = {
                coordinator.onConnectionLost()
                HarnessPlStatus.PairedButUnreachable("connection lost")
            },
            identityStore = identityStore,
            coordinator = coordinator,
        )

        val status = reader.read()
        assertEquals(HarnessPlStatus.PairedButUnreachable("connection lost"), status.plStatus)
        assertEquals("0.9.5", status.journalVersion?.version)
        assertEquals(JournalVersionFreshness.LAST_KNOWN, status.journalVersion?.freshness)
    }

    @Test
    fun backlogStatusReaderReturnsNullWhenNoIdentityOrCoordinator() {
        val reader = RealBacklogStatusReader(
            dao = TestSegmentDao(),
            plStatus = { HarnessPlStatus.NotPaired },
        )

        val status = reader.read()
        assertNull(status.journalVersion)
    }

    @Test
    fun plStatusProbeReturnsNotPairedWhenEmpty() {
        val endpointStore = TestEndpointStore()
        val credentialStore = TestCredentialStore()
        val identityStore = TestIdentityStore()
        val probe = RealPlStatusProbe(
            endpointStore = endpointStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
        )
        assertEquals(HarnessPlStatus.NotPaired, probe.probe())
    }

    @Test
    fun directConnectExceptionFallsBackToRelayAndSchedulesCoordinatorsWhenLiveEligible() {
        val endpointStore = TestEndpointStore().apply { save(DirectEndpoint("192.0.2.1", 7657)) }
        val credentialStore = TestCredentialStore().apply { save(ClientCredential("priv", "cert", listOf("ca"))) }
        val home = PairedHome(
            instanceId = "home-1",
            homeLabel = "Home",
            relayOrigin = "https://link.solstone.app",
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = "phone",
            deviceToken = "token_abc",
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val identityStore = TestIdentityStore().apply { save(home) }
        val mutator = TestMutator(home, liveEligible = true)
        val journalVersionStore = TestJournalVersionStore()
        val journalCoord = JournalVersionRefreshCoordinator(journalVersionStore)
        val relayCoord = app.solstone.core.pl.RelayAccessRefreshCoordinator(mutator)

        var directOpened = false
        var relayOpened = false
        val journalLatch = java.util.concurrent.CountDownLatch(1)
        val relayLatch = java.util.concurrent.CountDownLatch(1)

        val probe = RealPlStatusProbe(
            endpointStore = endpointStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            coordinator = journalCoord,
            relayAccessCoordinator = relayCoord,
            mutator = mutator,
            localDescriptionProvider = { app.solstone.core.pl.ClientReportedDescription("Phone", "1.0", "Android") },
            openTransport = { transport, _ ->
                when (transport) {
                    is app.solstone.platform.work.SyncTransport.Direct -> {
                        directOpened = true
                        throw java.net.ConnectException("direct refused")
                    }
                    is app.solstone.platform.work.SyncTransport.Relay -> {
                        relayOpened = true
                        object : PlHttpClient {
                            override fun request(
                                method: String,
                                path: String,
                                headers: Map<String, String>,
                                body: ByteArray?,
                                maxResponseBytes: Int,
                            ): HttpResponse {
                                if (path == "/app/network/api/status") {
                                    return HttpResponse(200, emptyMap(), ByteArray(0))
                                }
                                if (path.startsWith("/app/network/api/clients/self")) {
                                    journalLatch.countDown()
                                    return HttpResponse(200, emptyMap(), """{"protocol_version":1,"revision":1,"reported":null,"journal":{"name":"Home","version":"1.0.0"},"owner_label":null,"display_label":"Phone","updated_at":null}""".toByteArray())
                                }
                                if (path.startsWith("/app/network/api/relay/access")) {
                                    relayLatch.countDown()
                                    return HttpResponse(200, emptyMap(), """{"protocol_version":1,"status":"not_configured"}""".toByteArray())
                                }
                                return HttpResponse(200, emptyMap(), ByteArray(0))
                            }
                        }
                    }
                }
            },
        )

        val result = probe.probe()
        assertEquals(HarnessPlStatus.Reachable(200), result)
        kotlin.test.assertTrue(directOpened)
        kotlin.test.assertTrue(relayOpened)
        kotlin.test.assertTrue(journalLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
        kotlin.test.assertTrue(relayLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun directTrustRefusalDoesNotOpenRelay() {
        val endpointStore = TestEndpointStore().apply { save(DirectEndpoint("192.0.2.1", 7657)) }
        val credentialStore = TestCredentialStore().apply { save(ClientCredential("priv", "cert", listOf("ca"))) }
        val home = PairedHome(
            instanceId = "home-1",
            homeLabel = "Home",
            relayOrigin = "https://link.solstone.app",
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = "phone",
            deviceToken = "token_abc",
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val identityStore = TestIdentityStore().apply { save(home) }
        val mutator = TestMutator(home, liveEligible = true)
        var relayOpened = false

        val probe = RealPlStatusProbe(
            endpointStore = endpointStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            mutator = mutator,
            openTransport = { transport, _ ->
                when (transport) {
                    is app.solstone.platform.work.SyncTransport.Direct -> {
                        throw javax.net.ssl.SSLPeerUnverifiedException("peer not verified")
                    }
                    is app.solstone.platform.work.SyncTransport.Relay -> {
                        relayOpened = true
                        object : PlHttpClient {
                            override fun request(
                                method: String,
                                path: String,
                                headers: Map<String, String>,
                                body: ByteArray?,
                                maxResponseBytes: Int,
                            ): HttpResponse = HttpResponse(200, emptyMap(), ByteArray(0))
                        }
                    }
                }
            },
        )

        val result = probe.probe()
        kotlin.test.assertIs<HarnessPlStatus.PairedButUnreachable>(result)
        kotlin.test.assertFalse(relayOpened)
    }

    @Test
    fun directConnectExceptionDoesNotOpenRelayWhenLiveIneligible() {
        val endpointStore = TestEndpointStore().apply { save(DirectEndpoint("192.0.2.1", 7657)) }
        val credentialStore = TestCredentialStore().apply { save(ClientCredential("priv", "cert", listOf("ca"))) }
        val home = PairedHome(
            instanceId = "home-1",
            homeLabel = "Home",
            relayOrigin = "https://link.solstone.app",
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = "phone",
            deviceToken = "token_abc",
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val identityStore = TestIdentityStore().apply { save(home) }
        val mutator = TestMutator(home, liveEligible = false)
        var relayOpened = false

        val probe = RealPlStatusProbe(
            endpointStore = endpointStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            mutator = mutator,
            openTransport = { transport, _ ->
                when (transport) {
                    is app.solstone.platform.work.SyncTransport.Direct -> {
                        throw java.net.ConnectException("direct refused")
                    }
                    is app.solstone.platform.work.SyncTransport.Relay -> {
                        relayOpened = true
                        object : PlHttpClient {
                            override fun request(
                                method: String,
                                path: String,
                                headers: Map<String, String>,
                                body: ByteArray?,
                                maxResponseBytes: Int,
                            ): HttpResponse = HttpResponse(200, emptyMap(), ByteArray(0))
                        }
                    }
                }
            },
        )

        val result = probe.probe()
        kotlin.test.assertIs<HarnessPlStatus.PairedButUnreachable>(result)
        kotlin.test.assertFalse(relayOpened)
    }

    @Test
    fun opportunisticSyncEmptySpoolInvokesRecoveryProbeAndSchedulesJobs() {
        val endpointStore = TestEndpointStore().apply { save(DirectEndpoint("192.0.2.1", 7657)) }
        val credentialStore = TestCredentialStore().apply { save(ClientCredential("priv", "cert", listOf("ca"))) }
        val home = PairedHome(
            instanceId = "home-1",
            homeLabel = "Home",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = "phone",
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val identityStore = TestIdentityStore().apply { save(home) }
        val journalVersionStore = TestJournalVersionStore()
        val journalCoord = JournalVersionRefreshCoordinator(journalVersionStore)
        val journalLatch = java.util.concurrent.CountDownLatch(1)

        val probe = RealPlStatusProbe(
            endpointStore = endpointStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            coordinator = journalCoord,
            openTransport = { _, _ ->
                object : PlHttpClient {
                    override fun request(
                        method: String,
                        path: String,
                        headers: Map<String, String>,
                        body: ByteArray?,
                        maxResponseBytes: Int,
                    ): HttpResponse {
                        if (path == "/app/network/api/status") {
                            return HttpResponse(200, emptyMap(), ByteArray(0))
                        }
                        if (path.startsWith("/app/network/api/clients/self")) {
                            journalLatch.countDown()
                            return HttpResponse(200, emptyMap(), """{"protocol_version":1,"revision":1,"reported":null,"journal":{"name":"Home","version":"1.0.0"},"owner_label":null,"display_label":"Phone","updated_at":null}""".toByteArray())
                        }
                        return HttpResponse(200, emptyMap(), ByteArray(0))
                    }
                }
            },
        )

        val evidence = FakeEvidenceReader(sync = HarnessSyncState(0, null, null))
        var enqueued = false
        val syncEnqueue = object : SyncEnqueue {
            override fun enqueueNow() {
                enqueued = true
            }
            override fun enqueuePeriodic() {}
        }
        val network = object : NetworkAvailability {
            private var listener: (() -> Unit)? = null
            override fun isUsableNow(): Boolean = true
            override fun start(onUsable: () -> Unit) {
                listener = onUsable
            }
            override fun stop() {
                listener = null
            }
            fun trigger() {
                listener?.invoke()
            }
        }

        val opportunisticSync = OpportunisticSync(
            evidenceReader = evidence,
            syncEnqueue = syncEnqueue,
            networkAvailability = network,
            emptySpoolRecovery = { probe.probe() },
        )

        opportunisticSync.start()
        network.trigger()
        kotlin.test.assertFalse(enqueued)
        kotlin.test.assertTrue(journalLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))

        opportunisticSync.onPairingSuccess()
        kotlin.test.assertTrue(enqueued)
    }

    private class TestEndpointStore : EndpointStore {
        private var endpoint: DirectEndpoint? = null
        override fun save(endpoint: DirectEndpoint) {
            this.endpoint = endpoint
        }
        override fun load(): DirectEndpoint? = endpoint
        override fun clear() {
            endpoint = null
        }
    }

    private class TestCredentialStore : ClientCredentialStore {
        private var credential: ClientCredential? = null
        override fun save(credential: ClientCredential) {
            this.credential = credential
        }
        override fun load(): ClientCredential? = credential
        override fun clear() {
            credential = null
        }
    }

    private class TestSegmentDao : SegmentDao() {
        override fun insertSegment(segment: SegmentRow) = Unit
        override fun insertFiles(files: List<SegmentFileRow>) = Unit
        override fun insertEvents(events: List<EventRow>) = Unit
        override fun segmentsByState(state: QueueState): List<SegmentRow> = emptyList()
        override fun segmentsForDrain(stream: String): List<SegmentRow> = emptyList()
        override fun segmentsByDay(day: String): List<SegmentRow> = emptyList()
        override fun segmentById(id: String): SegmentRow? = null
        override fun duplicateBySha256(sha256: String): List<SegmentFileRow> = emptyList()
        override fun filesBySegmentId(segmentId: String): List<SegmentFileRow> = emptyList()
        override fun recordAttempt(id: String, attempts: Int, at: Long): Int = 0
        override fun recordUploaded(id: String, serverKey: String?): Int = 0
        override fun recordFailure(id: String, code: Int?, error: String?): Int = 0
        override fun recordDedupeChecked(id: String, at: Long): Int = 0
        override fun upsertSyncState(row: SyncStateRow) = Unit
        override fun syncState(): SyncStateRow? = null
        override fun pendingCount(stream: String): Int = 0
        override fun pendingSourceIds(stream: String): List<String> = emptyList()
        override fun segmentState(id: String): QueueState? = null
        override fun updateState(id: String, state: QueueState): Int = 0
        override fun deleteFilesBySegmentId(segmentId: String): Int = 0
        override fun deleteFilesBySegmentIds(segmentIds: List<String>): Int = 0
        override fun deleteFilesBySource(sourceId: String): Int = 0
    }

    private class TestJournalVersionStore : JournalVersionStore {
        private var record: JournalVersionRecord? = null
        override fun save(record: JournalVersionRecord) {
            this.record = record
        }
        override fun load(): JournalVersionRecord? = record
        override fun clear() {
            record = null
        }
    }

    private class TestIdentityStore : IdentityStore {
        private var home: PairedHome? = null
        override fun save(home: PairedHome) {
            this.home = home
        }
        override fun load(): PairedHome? = home
        override fun clear() {
            home = null
        }
    }

    private class TestMutator(
        private var home: PairedHome?,
        private var liveEligible: Boolean = true,
    ) : app.solstone.core.identity.IdentityMutator {
        override fun current(): PairedHome? = home
        override fun currentPairingGeneration(): app.solstone.core.identity.PairingGeneration? =
            home?.let { app.solstone.core.identity.PairingGeneration(it.instanceId, it.clientCertFingerprint) }
        override fun currentAccessMutationGen(): Long = 0
        override fun isRelayLiveEligible(): Boolean = liveEligible
        override fun disableRelayLive() {
            liveEligible = false
        }
        override fun lastPersistenceIssue(): app.solstone.core.identity.PersistenceIssue? = null
        override fun installNewPairing(home: PairedHome): Boolean = true
        override fun mutate(
            expectedPairing: app.solstone.core.identity.PairingGeneration,
            expectedAccessMutationGen: Long,
            transform: (PairedHome) -> PairedHome,
        ): app.solstone.core.identity.AccessMutationResult {
            val h = home ?: return app.solstone.core.identity.AccessMutationResult.Conflict("missing")
            val next = transform(h)
            home = next
            return app.solstone.core.identity.AccessMutationResult.Applied(next, 1)
        }
    }
}
