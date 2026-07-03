// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

class CaPinException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun caSpkiFp16(caPem: String): ByteArray =
    sha256(certificateFromPem(caPem).publicKey.encoded).copyOf(16)

fun assertCaPin(caPem: String, expectedFp16: ByteArray, peerLeafDer: ByteArray?) {
    val caCert = certificateFromPem(caPem)
    if (!caSpkiFp16(caPem).contentEquals(expectedFp16)) {
        throw CaPinException("relay CA SPKI fingerprint did not match QR pin")
    }
    if (peerLeafDer == null) {
        throw CaPinException("relay peer leaf certificate was missing")
    }
    val leaf = certificateFromDer(peerLeafDer)
    try {
        leaf.verify(caCert.publicKey)
    } catch (e: Exception) {
        throw CaPinException("relay peer leaf was not signed by pinned CA", e)
    }
    try {
        caCert.verify(caCert.publicKey)
    } catch (e: Exception) {
        throw CaPinException("relay CA certificate was not self-signed", e)
    }
}
