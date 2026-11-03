// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

data class JournalMarkIcon(
    val name: String,
    val svg: String,
    val colorName: String,
    val colorHex: String,
    val rot: Int,
)

data class JournalMark(
    val icon1: JournalMarkIcon,
    val icon2: JournalMarkIcon,
    val words: List<String>,
)

data class JournalMarkRecord(
    val instanceId: String,
    val mark: JournalMark?,
    val pairing: PairingGeneration? = null,
    /** Whether [mark] was computed here from this pairing's pinned CA, rather than served to us. */
    val derived: Boolean = false,
)

sealed class JournalMarkPresentation {
    data object Loading : JournalMarkPresentation()
    data object Generic : JournalMarkPresentation()
    /** A paired journal whose mark the owner hasn't confirmed yet: the generic form, set up. */
    data object Unconfirmed : JournalMarkPresentation()
    data object Unavailable : JournalMarkPresentation()
    data class Identified(val mark: JournalMark) : JournalMarkPresentation()
}

interface JournalMarkStore {
    fun load(): JournalMarkRecord?
    fun inspect(): StoreInspectResult<JournalMarkRecord> =
        load()?.let { StoreInspectResult.Ready(it) } ?: StoreInspectResult.Missing
    fun save(record: JournalMarkRecord)
    fun clear()
}
