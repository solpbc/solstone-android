// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.atomicWriteOwnerOnly
import java.io.File

class FileClientCredentialStore(
    private val file: File,
    private val protector: SecretProtector,
    private val log: (String) -> Unit = { java.util.logging.Logger.getLogger("FileClientCredentialStore").warning(it) },
) : ClientCredentialStore {
    override fun save(credential: ClientCredential) {
        val blob = credential.privateKeyPem +
            credential.clientCertPem +
            credential.caChainPem.joinToString("")
        val wrapped = protector.protect(blob.toByteArray())
        atomicWriteOwnerOnly(file, WRAP_MARKER + wrapped)
    }

    override fun load(): ClientCredential? {
        if (!file.exists()) {
            return null
        }
        val bytes = file.readBytes()
        return if (bytes.startsWithMarker()) {
            try {
                val wrapped = bytes.copyOfRange(WRAP_MARKER.size, bytes.size)
                parse(protector.unprotect(wrapped).decodeToString())
            } catch (_: Exception) {
                log("credential unwrap failed")
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

private fun parse(text: String): ClientCredential? {
    val blocks = PEM_BLOCK_REGEX.findAll(text).map { match ->
        PemBlock(match.groupValues[1], match.value)
    }.toList()
    val privateKeyPem = blocks.firstOrNull { it.type == "PRIVATE KEY" }?.text ?: return null
    val certs = blocks.filter { it.type == "CERTIFICATE" }.map { it.text }
    val clientCertPem = certs.firstOrNull() ?: return null
    return ClientCredential(privateKeyPem, clientCertPem, certs.drop(1))
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
