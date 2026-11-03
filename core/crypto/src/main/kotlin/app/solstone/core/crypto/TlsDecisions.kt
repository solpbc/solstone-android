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

/**
 * A direct pair response's id must be the journal id of the CA the link pinned. [pinnedCaDer] is the
 * exact bytes that passed the pin, and [caPem] is what gets stored: both have to be that one
 * certificate, and the certificate has to prove it holds its own key, or a copy of a real journal's
 * public key could be presented under that journal's id.
 */
fun assertDirectPairIdentity(instanceId: String, caPem: String, pinnedCaDer: ByteArray) {
    val ca = try {
        certificateFromDer(pinnedCaDer).also { it.verify(it.publicKey) }
    } catch (e: Exception) {
        throw CaPinException("direct pair CA was not a self-signed certificate", e)
    }
    val stored = runCatching { certificateFromPem(caPem).encoded }.getOrNull()
    if (!ca.encoded.contentEquals(pinnedCaDer) || !pinnedCaDer.contentEquals(stored)) {
        throw CaPinException("direct pair CA chain was not the pinned certificate")
    }
    val expected = try {
        jidFromSpkiDer(ca.publicKey.encoded)
    } catch (e: JidRefusalException) {
        throw CaPinException("direct pair CA has no journal id", e)
    }
    if (instanceId != expected) {
        throw CaPinException("direct pair response instance_id did not match pinned CA identity")
    }
}

fun requireTls13(supportedProtocols: Array<String>): Array<String> {
    if ("TLSv1.3" in supportedProtocols) {
        return arrayOf("TLSv1.3")
    }
    throw SSLException("TLSv1.3 not supported: " + supportedProtocols.toList())
}
