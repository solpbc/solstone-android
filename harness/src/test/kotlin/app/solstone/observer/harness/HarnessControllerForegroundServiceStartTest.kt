// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessControllerForegroundServiceStartTest {
    @Test
    fun alreadyForegroundStartDoesNotRequireVisibleCaptureOwner() {
        val f = fixture(
            visibleCaptureAuthority = FakeVisibleCaptureAuthority(present = false),
            snapshot = stoppedSnapshot(),
        )

        val readiness = f.controller.startWhenAlreadyForeground()

        assertTrue(readiness.allowed)
        assertEquals(1, f.lifecycle.starts)
        assertTrue(f.controller.desiredOn)
        assertFalse(f.controller.lastStartRefused)
    }

    @Test
    fun alreadyForegroundStartStillRefusesMissingPermissions() {
        val f = fixture(
            permissionStatus = grantedPermissions().copy(microphoneGranted = false),
            visibleCaptureAuthority = FakeVisibleCaptureAuthority(present = false),
            snapshot = stoppedSnapshot(),
        )

        val readiness = f.controller.startWhenAlreadyForeground()

        assertFalse(readiness.allowed)
        assertEquals(setOf(ReasonCode.PERMISSION_REVOKED), readiness.blockers)
        assertEquals(0, f.lifecycle.starts)
        assertTrue(f.controller.lastStartRefused)
        assertFalse(f.controller.desiredOn)
    }

    private fun stoppedSnapshot(): SourceRuntimeSnapshot =
        SourceRuntimeSnapshot(
            engineRunning = false,
            providerEmitting = true,
            storageOk = true,
            silenced = SilencedFact.NOT_SILENCED,
        )
}
