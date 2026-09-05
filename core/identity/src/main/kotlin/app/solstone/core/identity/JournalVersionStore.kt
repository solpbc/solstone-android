// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

data class JournalVersionRecord(
    val instanceId: String,
    val caChainFingerprint: String,
    val version: String,
)

interface JournalVersionStore {
    fun load(): JournalVersionRecord?
    fun save(record: JournalVersionRecord)
    fun clear()
}
