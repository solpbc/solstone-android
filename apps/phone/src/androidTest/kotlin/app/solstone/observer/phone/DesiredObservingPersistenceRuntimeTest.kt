// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.observer.harness.SharedPreferencesDesiredObservingStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesiredObservingPersistenceRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetPersistence(context)
    }

    /**
     * AC3 red proof: the old real flavor used an in-memory desired-on store, so restart rebuilt desired-on as false.
     */
    @Test
    fun desiredOnPersistsAcrossStoreRebuild() {
        val first = SharedPreferencesDesiredObservingStore(context, "desired-observing-persistence-test")
        assertFalse(first.isDesiredOn())

        first.setDesiredOn(true)
        val rebuilt = SharedPreferencesDesiredObservingStore(context, "desired-observing-persistence-test")

        assertTrue(rebuilt.isDesiredOn())
    }
}
