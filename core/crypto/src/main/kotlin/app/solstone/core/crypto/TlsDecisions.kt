// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import javax.net.ssl.SSLException

// Direct v04/v05 links pin certificate DER; relay v03 pins CA SPKI in SpkiPin.kt.
fun assertDirectCaPin(chain: List<ByteArray>, expectedCaDerPrefix: ByteArray) {
    if (chain.isEmpty()) {
        throw CaPinException("direct pair peer chain was empty")
    }
    val anchorDer = chain.firstOrNull { startsWith(sha256(it), expectedCaDerPrefix) }
        ?: throw CaPinException("no direct pair chain member matched pinned CA DER prefix")
    val anchor = certificateFromDer(anchorDer)
    val leaf = certificateFromDer(chain[0])
    try {
        leaf.verify(anchor.publicKey)
    } catch (e: Exception) {
        throw CaPinException("direct pair leaf was not signed by pinned CA", e)
    }
    try {
        anchor.verify(anchor.publicKey)
    } catch (e: Exception) {
        throw CaPinException("direct pair pinned CA was not self-signed", e)
    }
}

fun requireTls13(supportedProtocols: Array<String>): Array<String> {
    if ("TLSv1.3" in supportedProtocols) {
        return arrayOf("TLSv1.3")
    }
    throw SSLException("TLSv1.3 not supported: " + supportedProtocols.toList())
}
