// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.watch.courier

import java.nio.ByteBuffer

/** Fixed-size messages carry state only in their payload, never in routing fields. */
data class WatchCourierHello(val kind: Int, val version: Int, val sealedWaiting: Int) {
    fun encode(): ByteArray = ByteBuffer.allocate(12).putInt(kind).putInt(version).putInt(sealedWaiting).array()
    companion object {
        fun decode(bytes: ByteArray, expectedKind: Int): WatchCourierHello? {
            if (bytes.size != 12) return null
            val input = ByteBuffer.wrap(bytes)
            val kind = input.int
            val version = input.int
            val waiting = input.int
            if (kind != expectedKind || version <= 0 || waiting < -1) return null
            return WatchCourierHello(kind, version, waiting)
        }
    }
}
