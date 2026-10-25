// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColdWidgetSyncLineTest {
    @After
    fun reset() {
        PhoneStatusSupplier.override = null
        resetObserverRuntime()
    }

    @Test
    fun aRefreshWithNoContainerDoesNotRememberNotPaired() {
        val app = ApplicationProvider.getApplicationContext<PhoneApplication>()
        val line = File(app.filesDir, WIDGET_SYNC_LINE)
        line.delete()
        app.refreshWidgetModel(container = null)
        assertFalse(line.exists())
    }

    @Test
    fun aThrowingStatusReadDoesNotRememberNotPaired() {
        val app = ApplicationProvider.getApplicationContext<PhoneApplication>()
        val line = File(app.filesDir, WIDGET_SYNC_LINE)
        line.delete()
        resetObserverRuntime()
        val container = obtainObserverContainer()
        PhoneStatusSupplier.override = { throw IllegalStateException("status") }
        try {
            app.refreshWidgetModel(container)
            assertFalse(line.exists())
        } finally {
            PhoneStatusSupplier.override = null
            container.close()
        }
    }
}
