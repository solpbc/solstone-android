// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.pl.BeaconState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileBeaconStateStoreTest {
    @Test
    fun saveThenLoadRoundTripsBeaconState() {
        val file = Files.createTempDirectory("beacon-state-store-test").resolve("beacon.txt").toFile()
        val store = FileBeaconStateStore(file)
        val state = BeaconState(startedAt = 1000, recentErrorCount = 3)

        store.save(state)

        assertEquals(state, store.load())
    }

    @Test
    fun loadMissingFileReturnsNull() {
        val file = Files.createTempDirectory("beacon-state-store-test").resolve("missing.txt").toFile()

        assertNull(FileBeaconStateStore(file).load())
    }

    @Test
    fun loadCorruptFileReturnsNull() {
        val file = Files.createTempDirectory("beacon-state-store-test").resolve("beacon.txt").toFile()
        file.parentFile?.mkdirs()
        file.writeText("not-a-time\nalso-not-count\n")

        assertNull(FileBeaconStateStore(file).load())
    }
}
