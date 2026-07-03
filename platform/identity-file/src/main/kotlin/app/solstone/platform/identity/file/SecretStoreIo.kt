// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

internal val WRAP_MARKER: ByteArray = byteArrayOf(0x01) + "SOLW1".toByteArray(Charsets.US_ASCII)

internal fun ByteArray.startsWithMarker(): Boolean =
    size >= WRAP_MARKER.size && WRAP_MARKER.indices.all { this[it] == WRAP_MARKER[it] }
