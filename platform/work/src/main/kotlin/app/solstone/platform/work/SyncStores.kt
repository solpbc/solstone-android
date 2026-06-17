// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import android.content.Context
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.pl.EndpointStore
import app.solstone.platform.identity.file.FileClientCredentialStore
import app.solstone.platform.identity.file.FileEndpointStore
import app.solstone.platform.identity.file.FileIdentityStore
import java.io.File

data class SyncStores(
    val endpointStore: EndpointStore,
    val credentialStore: ClientCredentialStore,
    val identityStore: IdentityStore,
)

fun plStoreDir(context: Context): File = File(context.filesDir, "pl")

fun syncStores(context: Context): SyncStores {
    val dir = plStoreDir(context)
    return SyncStores(
        endpointStore = FileEndpointStore(File(dir, "endpoint.txt")),
        credentialStore = FileClientCredentialStore(File(dir, "credential.pem")),
        identityStore = FileIdentityStore(File(dir, "identity.tsv")),
    )
}
