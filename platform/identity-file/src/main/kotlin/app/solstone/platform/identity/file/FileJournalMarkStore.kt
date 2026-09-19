// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.JournalMarkRecord
import app.solstone.core.identity.JournalMarkStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PersistenceIssue
import app.solstone.core.identity.StoreInspectResult
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
            "client_cert_fingerprint" to record.pairing?.clientCertFingerprint,
            "committed" to (record.mark != null),
            "mark" to markMap,
        )
        val json = toJson(root)
        atomicWriteOwnerOnly(file, json.toByteArray(Charsets.UTF_8))
    }

    override fun inspect(): StoreInspectResult<JournalMarkRecord> {
        if (!file.exists()) return StoreInspectResult.Missing
        return try {
            val text = file.readText(Charsets.UTF_8)
            val root = parseJson(text) as? Map<*, *>
                ?: return StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "mark root")
            val instanceId = root["instance_id"] as? String
                ?: return StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "mark instance")
            val committed = root["committed"] as? Boolean
                ?: return StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "mark committed")
            val fingerprint = root["client_cert_fingerprint"] as? String
            val pairing = fingerprint?.let { PairingGeneration(instanceId, it) }
            if (!committed) {
                return StoreInspectResult.Ready(
                    JournalMarkRecord(instanceId = instanceId, mark = null, pairing = pairing),
                )
            }
            val parsed = parseIdentityResponse(text)
                ?: return StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "mark payload")
            val mark = parsed.mark
                ?: return StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "mark missing")
            StoreInspectResult.Ready(JournalMarkRecord(instanceId, mark, pairing))
        } catch (t: Throwable) {
            StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, t.javaClass.simpleName)
        }
    }

    override fun load(): JournalMarkRecord? =
        (inspect() as? StoreInspectResult.Ready)?.value

    override fun clear() {
        file.delete()
    }
}
