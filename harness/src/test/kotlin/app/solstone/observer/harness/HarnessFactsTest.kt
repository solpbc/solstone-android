// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HarnessFactsTest {
    @Test
    fun missingPermissionsAndStaleHeartbeatAreNeverOn() {
        val cameraDenied = fixture(permissionStatus = grantedPermissions().copy(cameraGranted = false))
        assertNotEquals(SourceState.ON, cameraDenied.controller.diagnostics().state)

        val f = fixture()
        f.controller.start()
        f.heartbeat.fresh = false
        val diagnostics = f.controller.diagnostics()
        assertEquals(SourceState.NEEDS_ATTENTION, diagnostics.state)
        assertEquals(ReasonCode.SERVICE_KILLED, diagnostics.reason)
    }
}
