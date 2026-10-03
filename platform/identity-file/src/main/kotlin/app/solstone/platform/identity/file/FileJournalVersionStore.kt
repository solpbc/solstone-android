// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.JournalVersionRecord
import app.solstone.core.identity.JournalVersionStore
import app.solstone.core.identity.atomicWriteOwnerOnly
import java.io.File

class FileJournalVersionStore(private val file: File) : JournalVersionStore {
    override fun save(record: JournalVersionRecord) {
        val lines = buildList {
            add("instanceId\t${record.instanceId}")
            add("caChainFingerprint\t${record.caChainFingerprint}")
            add("version\t${record.version}")
            record.name?.let { add("name\t$it") }
            record.os?.takeIf(String::isNotBlank)?.let { add("os\t$it") }
            record.osVersion?.takeIf(String::isNotBlank)?.let { add("os_version\t$it") }
            record.arch?.takeIf(String::isNotBlank)?.let { add("arch\t$it") }
            record.build?.takeIf(String::isNotBlank)?.let { add("build\t$it") }
            record.versionSeenAt?.takeIf { it >= 0L }?.let { add("versionSeenAt\t$it") }
            record.hostFactsAt?.takeIf { it >= 0L }?.let { add("hostFactsAt\t$it") }
        }
        atomicWriteOwnerOnly(file, lines.joinToString(separator = "\n", postfix = "\n").toByteArray())
    }

    override fun load(): JournalVersionRecord? {
        if (!file.exists()) {
            return null
        }
        return runCatching {
            val map = file.readLines().filter { it.isNotBlank() }.associate { line ->
                val parts = line.split('\t', limit = 2)
                parts[0] to (parts.getOrNull(1) ?: "")
            }
            val instanceId = map["instanceId"] ?: return null
            val caChainFingerprint = map["caChainFingerprint"] ?: return null
            val version = map["version"] ?: return null
            val name = map["name"]?.ifBlank { null }
            fun optionalFact(key: String): String? = map[key]?.takeIf(String::isNotBlank)
            fun optionalTimestamp(key: String): Long? = map[key]?.toLongOrNull()?.takeIf { it >= 0L }
            if (instanceId.isBlank() || caChainFingerprint.isBlank() || version.isBlank()) return null
            JournalVersionRecord(
                instanceId = instanceId,
                caChainFingerprint = caChainFingerprint,
                version = version,
                name = name,
                os = optionalFact("os"),
                osVersion = optionalFact("os_version"),
                arch = optionalFact("arch"),
                build = optionalFact("build"),
                versionSeenAt = optionalTimestamp("versionSeenAt"),
                hostFactsAt = optionalTimestamp("hostFactsAt"),
            )
        }.getOrNull()
    }

    override fun clear() {
        file.delete()
    }
}
