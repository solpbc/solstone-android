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
                ): HttpResponse = HttpResponse(200, emptyMap(), """{"version":{"current":"0.9.5"}}""".toByteArray())
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
}
