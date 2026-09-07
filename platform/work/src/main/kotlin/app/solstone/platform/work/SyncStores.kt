// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import android.content.Context
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.JournalVersionStore
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.JournalVersionRefreshCoordinator
import app.solstone.core.pl.RelayAccessRefreshCoordinator
import app.solstone.platform.identity.file.AndroidKeyStoreProtector
import app.solstone.platform.identity.file.FileClientCredentialStore
import app.solstone.platform.identity.file.FileEndpointStore
import app.solstone.platform.identity.file.FileIdentityMutator
import app.solstone.platform.identity.file.FileIdentityStore
import app.solstone.platform.identity.file.FileJournalVersionStore
import java.io.File

data class SyncStores(
    val endpointStore: EndpointStore,
    val credentialStore: ClientCredentialStore,
    val identityStore: IdentityStore,
    val identityMutator: IdentityMutator,
    val journalVersionStore: JournalVersionStore,
    val journalVersionCoordinator: JournalVersionRefreshCoordinator,
    val relayAccessCoordinator: RelayAccessRefreshCoordinator,
)

private object SyncStoresHolder {
    @Volatile
    private var mutator: IdentityMutator? = null
    @Volatile
    private var jvCoordinator: JournalVersionRefreshCoordinator? = null
    @Volatile
    private var raCoordinator: RelayAccessRefreshCoordinator? = null

    fun getMutator(identityStore: IdentityStore): IdentityMutator =
        mutator ?: synchronized(this) {
            mutator ?: FileIdentityMutator(identityStore).also { mutator = it }
        }

    fun getJvCoordinator(store: JournalVersionStore): JournalVersionRefreshCoordinator =
        jvCoordinator ?: synchronized(this) {
            jvCoordinator ?: JournalVersionRefreshCoordinator(store).also { jvCoordinator = it }
        }

    fun getRaCoordinator(mutator: IdentityMutator): RelayAccessRefreshCoordinator =
        raCoordinator ?: synchronized(this) {
            raCoordinator ?: RelayAccessRefreshCoordinator(mutator).also { raCoordinator = it }
        }
}

fun plStoreDir(context: Context): File = File(context.filesDir, "pl")

fun syncStores(context: Context): SyncStores {
    val dir = plStoreDir(context)
    val protector = AndroidKeyStoreProtector()
    val journalVersionStore = FileJournalVersionStore(File(dir, "journal_version.tsv"))
    val identityStore = FileIdentityStore(File(dir, "identity.tsv"), protector)
    val mutator = SyncStoresHolder.getMutator(identityStore)
    return SyncStores(
        endpointStore = FileEndpointStore(File(dir, "endpoint.txt")),
        credentialStore = FileClientCredentialStore(File(dir, "credential.pem"), protector),
        identityStore = identityStore,
        identityMutator = mutator,
        journalVersionStore = journalVersionStore,
        journalVersionCoordinator = SyncStoresHolder.getJvCoordinator(journalVersionStore),
        relayAccessCoordinator = SyncStoresHolder.getRaCoordinator(mutator),
    )
}
