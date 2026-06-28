// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.pl.BeaconState
import java.io.File

class FileBeaconStateStore(private val file: File) {
    fun load(): BeaconState? {
        if (!file.exists()) {
            return null
        }
        return runCatching {
            val lines = file.readLines()
            BeaconState(lines[0].trim().toLong(), lines[1].trim().toInt())
        }.getOrNull()
    }

    fun save(state: BeaconState) {
        file.parentFile?.mkdirs()
        file.writeText("${state.startedAt}\n${state.recentErrorCount}\n")
        setOwnerOnlyPermissions(file)
    }
}
