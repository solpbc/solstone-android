// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.JournalConfirmation
import app.solstone.core.identity.JournalConfirmationStore
import app.solstone.core.identity.PersistenceIssue
import app.solstone.core.identity.StoreInspectResult
import app.solstone.core.identity.atomicWriteOwnerOnly
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class FileJournalConfirmationStore(private val file: File) : JournalConfirmationStore {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "journal-confirmation-listener").apply { isDaemon = true }
    }

    override fun inspect(): StoreInspectResult<JournalConfirmation> {
        if (!file.exists()) return StoreInspectResult.Missing
        return try {
            val text = file.readText(Charsets.UTF_8)
            val root = parseJson(text) as? Map<*, *>
                ?: return StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "confirmation root")
            val settled = root["settled"] as? Boolean
                ?: return StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "confirmation settled")
            val rawConfirmed = root["confirmed"]
            if (rawConfirmed != null && rawConfirmed !is String) {
                return StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "confirmation confirmed")
            }
            val confirmed = rawConfirmed as? String
            StoreInspectResult.Ready(JournalConfirmation(confirmed = confirmed, settled = settled))
        } catch (t: Throwable) {
            StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, t.javaClass.simpleName)
        }
    }

    override fun confirm(fingerprint: String) {
        val root = mapOf(
            "confirmed" to fingerprint,
            "settled" to true,
        )
        val json = toJson(root)
        atomicWriteOwnerOnly(file, json.toByteArray(Charsets.UTF_8))
        notifyListeners()
    }

    override fun settle() {
        val existingConfirmed = (inspect() as? StoreInspectResult.Ready)?.value?.confirmed
        val root = mapOf(
            "confirmed" to existingConfirmed,
            "settled" to true,
        )
        val json = toJson(root)
        atomicWriteOwnerOnly(file, json.toByteArray(Charsets.UTF_8))
        notifyListeners()
    }

    override fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        executor.execute {
            runCatching { listener() }
        }
        return {
            listeners.remove(listener)
        }
    }

    private fun notifyListeners() {
        executor.execute {
            for (listener in listeners) {
                runCatching { listener() }
            }
        }
    }
}
