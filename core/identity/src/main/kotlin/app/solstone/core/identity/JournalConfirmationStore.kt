// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

data class JournalConfirmation(
    val confirmed: String?,
    /** The pairing already asked again because its id isn't its CA's. It is asked only that once. */
    val reasked: String? = null,
)

interface JournalConfirmationStore {
    fun inspect(): StoreInspectResult<JournalConfirmation>
    fun confirm(fingerprint: String)
    fun settle()

    /** Takes back the answer for [fingerprint] and records that it has been sent back to be asked. */
    fun holdToAskAgain(fingerprint: String): Unit = throw UnsupportedOperationException()
    fun addListener(listener: () -> Unit): () -> Unit
}
