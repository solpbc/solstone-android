// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

class SpySecretProtector(
    var failProtect: Boolean = false,
    var failUnprotect: Boolean = false,
    var unprotectTransform: (ByteArray) -> ByteArray = { it },
) : SecretProtector {
    var protectCalls: Int = 0
        private set
    var unprotectCalls: Int = 0
        private set

    override fun protect(plaintext: ByteArray): ByteArray {
        protectCalls += 1
        if (failProtect) {
            throw RuntimeException("protect failed")
        }
        return plaintext
    }

    override fun unprotect(wrapped: ByteArray): ByteArray {
        unprotectCalls += 1
        if (failUnprotect) {
            throw RuntimeException("unprotect failed")
        }
        return unprotectTransform(wrapped)
    }
}
