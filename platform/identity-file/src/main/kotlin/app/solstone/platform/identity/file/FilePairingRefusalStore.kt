// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingRefusalStore
import app.solstone.core.identity.atomicWriteOwnerOnly
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class FilePairingRefusalStore(private val file: File) : PairingRefusalStore {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "pairing-refusal-listener").apply { isDaemon = true }
    }

    override fun refusedFor(pairing: PairingGeneration): Boolean =
        synchronized(lock) { read() == pairing }

    override fun markRefused(pairing: PairingGeneration) {
        val changed = synchronized(lock) {
            if (read() == pairing) {
                false
            } else {
                write(pairing)
                true
            }
        }
        if (changed) notifyListeners()
    }

    override fun markAuthorized(pairing: PairingGeneration) {
        val changed = synchronized(lock) {
            if (read() == pairing) {
                write(null)
                true
            } else {
                false
            }
        }
        if (changed) notifyListeners()
    }

    override fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    // An unreadable record reads as no refusal: the pane then falls back to the pairing's other
    // facts rather than claiming a refusal it can't show.
    private fun read(): PairingGeneration? {
        if (!file.exists()) return null
        return runCatching {
            val root = parseJson(file.readText(Charsets.UTF_8)) as? Map<*, *> ?: return null
            val refused = root["refused"] as? Map<*, *> ?: return null
            val instanceId = refused["instance_id"] as? String ?: return null
            val fingerprint = refused["client_cert_fingerprint"] as? String ?: return null
            PairingGeneration(instanceId, fingerprint)
        }.getOrNull()
    }

    private fun write(pairing: PairingGeneration?) {
        val refused = pairing?.let {
            mapOf("instance_id" to it.instanceId, "client_cert_fingerprint" to it.clientCertFingerprint)
        }
        atomicWriteOwnerOnly(file, toJson(mapOf("refused" to refused)).toByteArray(Charsets.UTF_8))
    }

    private fun notifyListeners() {
        executor.execute {
            for (listener in listeners) {
                runCatching { listener() }
            }
        }
    }
}
