// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import java.io.File

class FileIdentityStore(private val file: File) : IdentityStore {
    override fun save(home: PairedHome) {
        file.parentFile?.mkdirs()
        val lines = buildList {
            add("instanceId\t${home.instanceId}")
            add("homeLabel\t${home.homeLabel}")
            home.relayOrigin?.let { add("relayOrigin\t$it") }
            add("caChainFingerprint\t${home.caChainFingerprint}")
            add("clientCertFingerprint\t${home.clientCertFingerprint}")
            home.observerHandle?.let { add("observerHandle\t$it") }
            add("state\t${home.state.name}")
        }
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        setOwnerOnlyPermissions(file)
    }

    override fun load(): PairedHome? {
        if (!file.exists()) {
            return null
        }
        val values = file.readLines()
            .filter { it.isNotEmpty() }
            .associate { line ->
                val parts = line.split('\t', limit = 2)
                parts[0] to parts.getOrElse(1) { "" }
            }
        return PairedHome(
            instanceId = values.getValue("instanceId"),
            homeLabel = values.getValue("homeLabel"),
            relayOrigin = values["relayOrigin"],
            caChainFingerprint = values.getValue("caChainFingerprint"),
            clientCertFingerprint = values.getValue("clientCertFingerprint"),
            observerHandle = values["observerHandle"],
            state = IdentityState.valueOf(values.getValue("state")),
        )
    }

    override fun clear() {
        file.delete()
    }
}
