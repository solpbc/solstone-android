// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import javax.net.ssl.SSLException

// Direct v04/v05 links pin certificate DER; relay v03 pins CA SPKI in SpkiPin.kt.
fun chainMatchesPrefix(chain: List<ByteArray>, expectedPrefix: ByteArray): Boolean =
    chain.any { startsWith(sha256(it), expectedPrefix) }

fun requireTls13(supportedProtocols: Array<String>): Array<String> {
    if ("TLSv1.3" in supportedProtocols) {
        return arrayOf("TLSv1.3")
    }
    throw SSLException("TLSv1.3 not supported: " + supportedProtocols.toList())
}
