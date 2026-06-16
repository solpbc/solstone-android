// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

import app.solstone.core.model.PairedHome

data class ClientCredential(
    val privateKeyPem: String,
    val clientCertPem: String,
    val caChainPem: List<String>,
)

interface ClientCredentialStore {
    fun save(credential: ClientCredential)
    fun load(): ClientCredential?
    fun clear()
}

interface IdentityStore {
    fun save(home: PairedHome)
    fun load(): PairedHome?
    fun clear()
}
