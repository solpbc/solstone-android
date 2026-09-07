// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

import java.io.File

fun interface AtomicFileWriter {
    fun write(target: File, bytes: ByteArray)

    companion object {
        val Default: AtomicFileWriter = AtomicFileWriter { target, bytes ->
            atomicWriteOwnerOnly(target, bytes)
        }
    }
}
