// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.AtomicFileWriter
import app.solstone.core.identity.PersistenceIssue
import app.solstone.core.identity.StoreInspectResult
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import java.io.File

class FileEndpointStore(
    private val file: File,
    private val fileWriter: AtomicFileWriter = AtomicFileWriter.Default,
) : EndpointStore {
    override fun save(endpoint: DirectEndpoint) {
        val payload = "${endpoint.host}\n${endpoint.port}\n".toByteArray()
        fileWriter.write(file, payload)
    }

    override fun load(): DirectEndpoint? {
        if (!file.exists()) {
            return null
        }
        return runCatching {
            val lines = file.readLines()
            DirectEndpoint(lines[0].trim(), lines[1].trim().toInt())
        }.getOrNull()
    }

    override fun inspect(): StoreInspectResult<DirectEndpoint> {
        if (!file.exists()) {
            return StoreInspectResult.Missing
        }
        return runCatching {
            val lines = file.readLines()
            DirectEndpoint(lines[0].trim(), lines[1].trim().toInt())
        }.fold(
            onSuccess = { StoreInspectResult.Ready(it) },
            onFailure = { StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "endpoint parse failed") },
        )
    }

    override fun clear() {
        file.delete()
    }
}
