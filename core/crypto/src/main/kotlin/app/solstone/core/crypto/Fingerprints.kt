// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import java.security.MessageDigest
import java.util.Locale

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

fun sha256Hex(bytes: ByteArray): String = hex(sha256(bytes))

fun hex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        out.append(String.format(Locale.US, "%02x", byte.toInt() and 0xff))
    }
    return out.toString()
}

fun startsWith(value: ByteArray, prefix: ByteArray): Boolean {
    if (value.size < prefix.size) {
        return false
    }
    for (index in prefix.indices) {
        if (value[index] != prefix[index]) {
            return false
        }
    }
    return true
}
