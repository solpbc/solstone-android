// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessFirstLaunchTest {
    @Test
    fun firstLaunchWithoutPermissionsNeedsAttention() {
        val f = fixture(permissionStatus = grantedPermissions().copy(microphoneGranted = false))
        val diagnostics = f.controller.diagnostics()
        assertEquals(SourceState.NEEDS_ATTENTION, diagnostics.state)
        assertEquals(ReasonCode.PERMISSION_REVOKED, diagnostics.reason)
    }
}
