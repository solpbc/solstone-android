// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import android.content.Context
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.JournalVersionStore
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.JournalVersionRefreshCoordinator
import app.solstone.platform.identity.file.AndroidKeyStoreProtector
import app.solstone.platform.identity.file.FileClientCredentialStore
import app.solstone.platform.identity.file.FileEndpointStore
import app.solstone.platform.identity.file.FileIdentityStore
import app.solstone.platform.identity.file.FileJournalVersionStore
import java.io.File

data class SyncStores(
    val endpointStore: EndpointStore,
    val credentialStore: ClientCredentialStore,
    val identityStore: IdentityStore,
    val journalVersionStore: JournalVersionStore,
    val journalVersionCoordinator: JournalVersionRefreshCoordinator,
)

private object JournalVersionCoordinatorHolder {
    @Volatile
    private var instance: JournalVersionRefreshCoordinator? = null

    fun get(store: JournalVersionStore): JournalVersionRefreshCoordinator =
        instance ?: synchronized(this) {
            instance ?: JournalVersionRefreshCoordinator(store).also { instance = it }
        }
}

fun plStoreDir(context: Context): File = File(context.filesDir, "pl")

fun syncStores(context: Context): SyncStores {
    val dir = plStoreDir(context)
    val protector = AndroidKeyStoreProtector()
    val journalVersionStore = FileJournalVersionStore(File(dir, "journal_version.tsv"))
    return SyncStores(
        endpointStore = FileEndpointStore(File(dir, "endpoint.txt")),
        credentialStore = FileClientCredentialStore(File(dir, "credential.pem"), protector),
        identityStore = FileIdentityStore(File(dir, "identity.tsv"), protector),
        journalVersionStore = journalVersionStore,
        journalVersionCoordinator = JournalVersionCoordinatorHolder.get(journalVersionStore),
    )
}

