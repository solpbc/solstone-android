// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnresolvedInterruptionStoreTest {
    @Test
    fun failedParentSyncForNewOrExistingMarkerForbidsDiscard() {
        val dir = Files.createTempDirectory("interruption-durability")
        try {
            val marker = dir.resolve("interrupted")
            val failing = UnresolvedInterruptionStore(marker) { throw IOException("sync failed") }
            assertFalse(failing.markUnresolved())
            assertTrue(Files.exists(marker))
            assertFalse(failing.markUnresolved(), "an existing marker still needs durable publication")
            assertTrue(UnresolvedInterruptionStore(marker).markUnresolved())
            assertTrue(UnresolvedInterruptionStore(marker).isUnresolved())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
