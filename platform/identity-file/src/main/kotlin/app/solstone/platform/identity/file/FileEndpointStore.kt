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
    override fun save(endpoint: DirectEndpoint) = saveAll(listOf(endpoint))

    fun saveAll(endpoints: List<app.solstone.core.model.DirectEndpoint>) {
        require(endpoints.isNotEmpty())
        val payload = endpoints.distinct().joinToString("") { "${it.host}\n${it.port}\n" }.toByteArray()
        fileWriter.write(file, payload)
    }

    override fun load(): DirectEndpoint? = loadAll().firstOrNull()

    // A pre-existing two-line endpoint.txt is already the one-member representation.
    override fun loadAll(): List<DirectEndpoint> = when (val result = inspectAll()) {
        is StoreInspectResult.Ready -> result.value
        else -> emptyList()
    }

    fun inspectAll(): StoreInspectResult<List<DirectEndpoint>> {
        if (!file.exists()) return StoreInspectResult.Missing
        return runCatching {
            val lines = file.readLines()
            require(lines.isNotEmpty() && lines.size % 2 == 0)
            lines.chunked(2).map { pair ->
                val host = pair[0].trim()
                val port = pair[1].trim().toInt()
                require(host.isNotEmpty() && host.none { it.isWhitespace() || it.isISOControl() } && port in 1..65535)
                DirectEndpoint(host, port)
            }.distinct()
        }.fold(
            onSuccess = { StoreInspectResult.Ready(it) },
            onFailure = { StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "endpoint set parse failed") },
        )
    }

    override fun inspect(): StoreInspectResult<DirectEndpoint> = when (val result = inspectAll()) {
        is StoreInspectResult.Ready -> StoreInspectResult.Ready(result.value.first())
        StoreInspectResult.Missing -> StoreInspectResult.Missing
        is StoreInspectResult.Unreadable -> result
    }

    override fun clear() { file.delete() }
}
