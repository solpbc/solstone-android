// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerTaskLaunchTest {
    @Test
    fun theShellsTwoOwnerRoutesAreOwnerTasks() {
        assertTrue(launch(scansPairQr = true))
        assertTrue(launch(showsLocalCache = true))
    }

    @Test
    fun anOwnerRouteSurvivesAConfigurationChange() {
        // The routing extras are re-read from the same intent after a rotation. If `firstLaunch`
        // gated them too, rotating on the pair scanner would drop the owner into the operator menu.
        assertTrue(launch(scansPairQr = true, firstLaunch = false))
        assertTrue(launch(showsLocalCache = true, firstLaunch = false))
    }

    @Test
    fun anAppLinkIsAnOwnerTaskOnASurfaceThatHandlesPairLinks() {
        assertTrue(launch(isViewAction = true, hasData = true, handlesPairLinks = true))
    }

    @Test
    fun aBareLaunchKeepsTheOperatorMenu() {
        // `apps/watch` declares this activity as its LAUNCHER, so the harness is that app.
        assertFalse(launch())
    }

    @Test
    fun anAppLinkIsNotAnOwnerTaskWhenItCannotBeRouted() {
        // Each of these matches an arm of the router's own gate; a launch the router will not
        // dispatch must not be treated as a task, or leaving it would finish onto a blank screen.
        assertFalse(launch(isViewAction = true, hasData = true, handlesPairLinks = false))
        assertFalse(launch(isViewAction = true, hasData = false, handlesPairLinks = true))
        assertFalse(launch(isViewAction = false, hasData = true, handlesPairLinks = true))
        assertFalse(launch(isViewAction = true, hasData = true, handlesPairLinks = true, firstLaunch = false))
    }

    private fun launch(
        scansPairQr: Boolean = false,
        showsLocalCache: Boolean = false,
        isViewAction: Boolean = false,
        hasData: Boolean = false,
        handlesPairLinks: Boolean = false,
        firstLaunch: Boolean = true,
    ): Boolean = isOwnerTaskLaunch(
        scansPairQr = scansPairQr,
        showsLocalCache = showsLocalCache,
        isViewAction = isViewAction,
        hasData = hasData,
        handlesPairLinks = handlesPairLinks,
        firstLaunch = firstLaunch,
    )
}
