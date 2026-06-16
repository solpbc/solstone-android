// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import java.io.File

class FileClientCredentialStore(private val file: File) : ClientCredentialStore {
    override fun save(credential: ClientCredential) {
        file.parentFile?.mkdirs()
        file.writeText(
            credential.privateKeyPem +
                credential.clientCertPem +
                credential.caChainPem.joinToString(""),
        )
        setOwnerOnlyPermissions(file)
    }

    override fun load(): ClientCredential? {
        if (!file.exists()) {
            return null
        }
        val blocks = PEM_BLOCK_REGEX.findAll(file.readText()).map { match ->
            PemBlock(match.groupValues[1], match.value)
        }.toList()
        val privateKeyPem = blocks.firstOrNull { it.type == "PRIVATE KEY" }?.text ?: return null
        val certs = blocks.filter { it.type == "CERTIFICATE" }.map { it.text }
        val clientCertPem = certs.firstOrNull() ?: return null
        return ClientCredential(privateKeyPem, clientCertPem, certs.drop(1))
    }

    override fun clear() {
        file.delete()
    }
}

private data class PemBlock(val type: String, val text: String)

private val PEM_BLOCK_REGEX = Regex("-----BEGIN ([A-Z ]+)-----.*?-----END \\1-----\\n?", RegexOption.DOT_MATCHES_ALL)

internal fun setOwnerOnlyPermissions(file: File) {
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setExecutable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
}
