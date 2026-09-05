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
            if (instanceId.isBlank() || caChainFingerprint.isBlank() || version.isBlank()) return null
            JournalVersionRecord(instanceId, caChainFingerprint, version)
        }.getOrNull()
    }

    override fun clear() {
        file.delete()
    }
}
