// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

interface PushKeyAccess {
    fun readPushKey(generation: PairingGeneration): ByteArray?
    fun obtainPushKey(generation: PairingGeneration): ObtainResult
}

sealed interface ObtainResult {
    data class Obtained(val bytes: ByteArray) : ObtainResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Obtained) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }
    data object Refused : ObtainResult
    data class Failed(val cause: Throwable) : ObtainResult
}
