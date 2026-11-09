// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import app.solstone.core.watch.courier.WatchSessionState
import app.solstone.core.watch.courier.WindowPumpStopReason
import app.solstone.observer.wear.service.WatchCaptureStatusStore
import java.nio.file.Files
import kotlin.test.*

class WatchCaptureStatusStoreTest {
    @Test fun retainsOwnerIntentAndEndReasonAcrossStoreInstances() {
        val root = Files.createTempDirectory("watch-capture-status").toFile()
        val first = WatchCaptureStatusStore(root)
        assertNull(first.read())
        first.write(WatchSessionState.ACTIVE, null, 1234L)
        val afterLoss = WatchCaptureStatusStore(root).read()!!
        assertEquals(WatchSessionState.ACTIVE, afterLoss.state)
        assertNull(afterLoss.reason)
        assertEquals(1234L, afterLoss.startedAt)
        root.resolve("capture.properties.tmp").writeText("incomplete write")
        assertEquals(afterLoss, WatchCaptureStatusStore(root).read())
        first.write(WatchSessionState.ENDED, WindowPumpStopReason.PERMISSION_FAILURE, 1234L)
        assertEquals(WindowPumpStopReason.PERMISSION_FAILURE, WatchCaptureStatusStore(root).read()!!.reason)
        assertEquals(2, root.resolve("capture-transitions.tsv").readLines().size)
    }
}
