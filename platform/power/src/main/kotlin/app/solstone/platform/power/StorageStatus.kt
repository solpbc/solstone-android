// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.power

import java.io.File

fun interface UsableSpaceProvider {
    fun usableBytes(): Long
}

class FileUsableSpaceProvider(private val directory: File) : UsableSpaceProvider {
    override fun usableBytes(): Long = directory.usableSpace
}

class StorageStatus(
    private val usableSpaceProvider: UsableSpaceProvider,
    private val minimumFreeBytes: Long,
) {
    init {
        require(minimumFreeBytes >= 0L) { "minimumFreeBytes must be non-negative" }
    }

    fun isStorageOk(): Boolean = usableSpaceProvider.usableBytes() >= minimumFreeBytes
}
