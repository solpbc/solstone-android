// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

data class JournalConfirmation(
    val confirmed: String?,
)

interface JournalConfirmationStore {
    fun inspect(): StoreInspectResult<JournalConfirmation>
    fun confirm(fingerprint: String)
    fun settle()
    fun addListener(listener: () -> Unit): () -> Unit
}
