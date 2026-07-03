// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

interface SecretProtector {
    fun protect(plaintext: ByteArray): ByteArray
    fun unprotect(wrapped: ByteArray): ByteArray
}
