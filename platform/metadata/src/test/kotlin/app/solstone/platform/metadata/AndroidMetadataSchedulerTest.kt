// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.metadata

import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidMetadataSchedulerTest {
    @Test
    fun executeTaskThrowSurfacesAsDiag() {
        val lines = mutableListOf<String>()
        val scheduler = AndroidMetadataScheduler(diag = lines::add)

        scheduler.execute { throw IllegalStateException("boom") }

        waitUntil { lines.isNotEmpty() }
        assertTrue("capture event=metadata-scheduler-task-failed type=IllegalStateException message=boom" in lines)
        scheduler.shutdown()
    }

    private fun waitUntil(block: () -> Boolean) {
        repeat(200) {
            if (block()) return
            Thread.sleep(5L)
        }
        throw AssertionError("condition was not met")
    }
}
