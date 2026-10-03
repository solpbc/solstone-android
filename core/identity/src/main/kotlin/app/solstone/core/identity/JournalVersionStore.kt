// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

data class JournalVersionRecord(
    val instanceId: String,
    val caChainFingerprint: String,
    val version: String,
    val name: String? = null,
    val os: String? = null,
    val osVersion: String? = null,
    val arch: String? = null,
    val build: String? = null,
    val versionSeenAt: Long? = null,
    val hostFactsAt: Long? = null,
)

interface JournalVersionStore {
    fun load(): JournalVersionRecord?
    fun save(record: JournalVersionRecord)
    fun clear()
}
