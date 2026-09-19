// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import android.content.Context
import app.solstone.core.identity.AccessMutationResult
import app.solstone.core.identity.AccessSnapshot
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.JournalMarkStore
import app.solstone.core.identity.JournalVersionStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.PersistenceIssue
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.JournalIdentityRefreshCoordinator
import app.solstone.core.pl.JournalVersionRefreshCoordinator
import app.solstone.core.pl.RelayAccessRefreshCoordinator
import app.solstone.platform.identity.file.AndroidKeyStoreProtector
import app.solstone.platform.identity.file.FileClientCredentialStore
import app.solstone.platform.identity.file.FileEndpointStore
import app.solstone.platform.identity.file.FileIdentityStore
import app.solstone.platform.identity.file.FileJournalMarkStore
import app.solstone.platform.identity.file.FileJournalVersionStore
import app.solstone.platform.identity.file.FilePairingGraph
import java.io.File

data class SyncStores(
    val publisher: PairingPublisher,
    val endpointStore: EndpointStore,
    val credentialStore: ClientCredentialStore,
    val identityStore: IdentityStore,
    val identityMutator: IdentityMutator,
    val journalVersionStore: JournalVersionStore,
    val journalVersionCoordinator: JournalVersionRefreshCoordinator,
    val relayAccessCoordinator: RelayAccessRefreshCoordinator,
    val journalMarkStore: JournalMarkStore,
    val journalIdentityCoordinator: JournalIdentityRefreshCoordinator,
)

class PublisherIdentityMutatorAdapter(
    private val publisher: PairingPublisher,
) : IdentityMutator {
    override fun <T> withMutationBoundary(block: () -> T): T =
        publisher.withMutationBoundary(block)

    override fun current(): PairedHome? =
        (publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)?.home

    override fun currentPairingGeneration(): PairingGeneration? =
        (publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing

    override fun currentAccessMutationGen(): Long =
        (publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)?.revisions?.relayAccessRevision ?: 0L

    override fun isRelayLiveEligible(): Boolean =
        (publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)?.relayLiveEligible ?: false

    override fun disableRelayLive() {
        val snap = publisher.currentSnapshot() as? PairingGraphSnapshot.Committed ?: return
        publisher.revokeRelayAccess(snap.pairing)
    }

    override fun installNewPairing(home: PairedHome): Boolean = true

    override fun lastPersistenceIssue(): PersistenceIssue? =
        (publisher.currentSnapshot() as? PairingGraphSnapshot.Uncertain)?.reason

    override fun mutate(
        expectedPairing: PairingGeneration,
        expectedAccessMutationGen: Long,
        transform: (PairedHome) -> PairedHome,
    ): AccessMutationResult {
        val snap = publisher.currentSnapshot() as? PairingGraphSnapshot.Committed
            ?: return AccessMutationResult.Conflict("No committed pairing")
        if (snap.pairing != expectedPairing || snap.revisions.relayAccessRevision != expectedAccessMutationGen) {
            return AccessMutationResult.Conflict("obsolete access")
        }
        val transformed = transform(snap.home)
        val relayOrigin = transformed.relayOrigin
        val deviceToken = transformed.deviceToken
        if (relayOrigin != null && deviceToken != null) {
            val res = publisher.updateRelayAccess(
                expectedPairing = expectedPairing,
                relayOrigin = relayOrigin,
                deviceToken = deviceToken,
                expiresAt = transformed.expiresAt,
            )
            return when (res) {
                is GraphMutationResult.Applied -> AccessMutationResult.Applied((res.snapshot as PairingGraphSnapshot.Committed).home, res.snapshot.revisions.relayAccessRevision)
                is GraphMutationResult.Conflict -> AccessMutationResult.Conflict(res.reason)
                is GraphMutationResult.PersistenceFailed -> AccessMutationResult.PersistenceFailed(res.cause)
                is GraphMutationResult.DurabilityUncertain -> AccessMutationResult.Conflict("durability uncertain")
                is GraphMutationResult.Cleared -> AccessMutationResult.Conflict("cleared")
            }
        } else {
            val res = publisher.revokeRelayAccess(expectedPairing)
            return when (res) {
                is GraphMutationResult.Applied -> AccessMutationResult.Applied((res.snapshot as PairingGraphSnapshot.Committed).home, res.snapshot.revisions.relayAccessRevision)
                is GraphMutationResult.Conflict -> AccessMutationResult.Conflict(res.reason)
                is GraphMutationResult.PersistenceFailed -> AccessMutationResult.PersistenceFailed(res.cause)
                is GraphMutationResult.DurabilityUncertain -> AccessMutationResult.Conflict("durability uncertain")
                is GraphMutationResult.Cleared -> AccessMutationResult.Conflict("cleared")
            }
        }
    }
}

private object SyncStoresHolder {
    @Volatile
    private var publisher: PairingPublisher? = null
    @Volatile
    private var mutator: IdentityMutator? = null
    @Volatile
    private var jvCoordinator: JournalVersionRefreshCoordinator? = null
    @Volatile
    private var raCoordinator: RelayAccessRefreshCoordinator? = null
    @Volatile
    private var jiCoordinator: JournalIdentityRefreshCoordinator? = null

    fun getPublisher(dir: File, protector: AndroidKeyStoreProtector): PairingPublisher =
        publisher ?: synchronized(this) {
            publisher ?: FilePairingGraph(
                identityFile = File(dir, "identity.tsv"),
                credentialFile = File(dir, "credential.pem"),
                endpointFile = File(dir, "endpoint.txt"),
                commitMarkerFile = File(dir, "pairing.commit"),
                protector = protector,
            ).also { publisher = it }
        }

    fun getMutator(publisher: PairingPublisher): IdentityMutator =
        mutator ?: synchronized(this) {
            mutator ?: PublisherIdentityMutatorAdapter(publisher).also { mutator = it }
        }

    fun getJvCoordinator(store: JournalVersionStore): JournalVersionRefreshCoordinator =
        jvCoordinator ?: synchronized(this) {
            jvCoordinator ?: JournalVersionRefreshCoordinator(store).also { jvCoordinator = it }
        }

    fun getRaCoordinator(mutator: IdentityMutator): RelayAccessRefreshCoordinator =
        raCoordinator ?: synchronized(this) {
            raCoordinator ?: RelayAccessRefreshCoordinator(mutator).also { raCoordinator = it }
        }

    fun getJiCoordinator(
        store: JournalMarkStore,
        publisher: PairingPublisher,
    ): JournalIdentityRefreshCoordinator =
        jiCoordinator ?: synchronized(this) {
            jiCoordinator ?: JournalIdentityRefreshCoordinator(
                store = store,
                publisher = publisher,
            ).also { jiCoordinator = it }
        }
}

fun plStoreDir(context: Context): File = File(context.filesDir, "pl")

fun syncStores(context: Context): SyncStores {
    val dir = plStoreDir(context)
    val protector = AndroidKeyStoreProtector()
    val journalVersionStore = FileJournalVersionStore(File(dir, "journal_version.tsv"))
    val journalMarkStore = FileJournalMarkStore(File(dir, "journal_mark.json"))
    val identityStore = FileIdentityStore(File(dir, "identity.tsv"), protector)
    val publisher = SyncStoresHolder.getPublisher(dir, protector)
    val mutator = SyncStoresHolder.getMutator(publisher)
    return SyncStores(
        publisher = publisher,
        endpointStore = FileEndpointStore(File(dir, "endpoint.txt")),
        credentialStore = FileClientCredentialStore(File(dir, "credential.pem"), protector),
        identityStore = identityStore,
        identityMutator = mutator,
        journalVersionStore = journalVersionStore,
        journalVersionCoordinator = SyncStoresHolder.getJvCoordinator(journalVersionStore),
        relayAccessCoordinator = SyncStoresHolder.getRaCoordinator(mutator),
        journalMarkStore = journalMarkStore,
        journalIdentityCoordinator = SyncStoresHolder.getJiCoordinator(journalMarkStore, publisher),
    )
}
