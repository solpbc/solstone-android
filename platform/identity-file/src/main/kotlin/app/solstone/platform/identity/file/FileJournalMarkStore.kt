// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.JournalMarkRecord
import app.solstone.core.identity.JournalMarkStore
import app.solstone.core.identity.atomicWriteOwnerOnly
import app.solstone.core.pl.parseIdentityResponse
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.io.File

class FileJournalMarkStore(private val file: File) : JournalMarkStore {

    override fun save(record: JournalMarkRecord) {
        val markMap = record.mark?.let { mark ->
            mapOf(
                "icon1" to mapOf(
                    "name" to mark.icon1.name,
                    "svg" to mark.icon1.svg,
                    "color" to mapOf(
                        "name" to mark.icon1.colorName,
                        "hex" to mark.icon1.colorHex,
                    ),
                    "rot" to mark.icon1.rot,
                ),
                "icon2" to mapOf(
                    "name" to mark.icon2.name,
                    "svg" to mark.icon2.svg,
                    "color" to mapOf(
                        "name" to mark.icon2.colorName,
                        "hex" to mark.icon2.colorHex,
                    ),
                    "rot" to mark.icon2.rot,
                ),
                "words" to mark.words,
            )
        }
        val root = mapOf(
            "instance_id" to record.instanceId,
            "committed" to (record.mark != null),
            "mark" to markMap,
        )
        val json = toJson(root)
        atomicWriteOwnerOnly(file, json.toByteArray(Charsets.UTF_8))
    }

    override fun load(): JournalMarkRecord? {
        if (!file.exists()) return null
        return runCatching {
            val text = file.readText(Charsets.UTF_8)
            val root = parseJson(text) as? Map<*, *> ?: return null
            val instanceId = root["instance_id"] as? String ?: return null
            val committed = root["committed"] as? Boolean ?: return null
            if (!committed) {
                return JournalMarkRecord(instanceId = instanceId, mark = null)
            }
            val parsed = parseIdentityResponse(text) ?: return null
            JournalMarkRecord(instanceId = instanceId, mark = parsed.mark)
        }.getOrNull()
    }

    override fun clear() {
        file.delete()
    }
}
