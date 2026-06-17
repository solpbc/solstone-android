// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import java.io.File

class FileEndpointStore(private val file: File) : EndpointStore {
    override fun save(endpoint: DirectEndpoint) {
        file.parentFile?.mkdirs()
        file.writeText("${endpoint.host}\n${endpoint.port}\n")
        setOwnerOnlyPermissions(file)
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

    override fun clear() {
        file.delete()
    }
}
