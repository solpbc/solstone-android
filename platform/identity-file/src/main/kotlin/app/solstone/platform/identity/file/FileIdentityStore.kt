// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.atomicWriteOwnerOnly
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import java.io.File

class FileIdentityStore(
    private val file: File,
    private val protector: SecretProtector,
    private val log: (String) -> Unit = { java.util.logging.Logger.getLogger("FileIdentityStore").warning(it) },
) : IdentityStore {
    override fun save(home: PairedHome) {
        val lines = buildList {
            add("instanceId\t${home.instanceId}")
            add("homeLabel\t${home.homeLabel}")
            home.relayOrigin?.let { add("relayOrigin\t$it") }
            home.deviceToken?.let { add("deviceToken\t$it") }
            home.expiresAt?.let { add("expiresAt\t$it") }
            add("caChainFingerprint\t${home.caChainFingerprint}")
            add("clientCertFingerprint\t${home.clientCertFingerprint}")
            home.observerHandle?.let { add("observerHandle\t$it") }
            add("state\t${home.state.name}")
        }
        val wrapped = protector.protect(lines.joinToString(separator = "\n", postfix = "\n").toByteArray())
        atomicWriteOwnerOnly(file, WRAP_MARKER + wrapped)
    }

    override fun load(): PairedHome? {
        if (!file.exists()) {
            return null
        }
        val bytes = file.readBytes()
        return if (bytes.startsWithMarker()) {
            try {
                val wrapped = bytes.copyOfRange(WRAP_MARKER.size, bytes.size)
                parse(protector.unprotect(wrapped).decodeToString())
            } catch (_: Exception) {
                log("identity blob malformed")
                null
            }
        } else {
            parse(bytes.decodeToString())
        }
    }

    override fun clear() {
        file.delete()
    }
}

private fun parse(text: String): PairedHome? {
    val values = text.lineSequence()
        .filter { it.isNotEmpty() }
        .associate { line ->
            val parts = line.split('\t', limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }
    val state = try {
        IdentityState.valueOf(values["state"] ?: return null)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return PairedHome(
        instanceId = values["instanceId"] ?: return null,
        homeLabel = values["homeLabel"] ?: return null,
        relayOrigin = values["relayOrigin"],
        caChainFingerprint = values["caChainFingerprint"] ?: return null,
        clientCertFingerprint = values["clientCertFingerprint"] ?: return null,
        observerHandle = values["observerHandle"],
        deviceToken = values["deviceToken"],
        expiresAt = values["expiresAt"],
        state = state,
    )
}
