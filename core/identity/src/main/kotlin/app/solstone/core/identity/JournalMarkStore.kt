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
)

sealed class JournalMarkPresentation {
    data object Generic : JournalMarkPresentation()
    data object Unavailable : JournalMarkPresentation()
    data class Identified(val mark: JournalMark) : JournalMarkPresentation()
}

interface JournalMarkStore {
    fun load(): JournalMarkRecord?
    fun save(record: JournalMarkRecord)
    fun clear()
}


