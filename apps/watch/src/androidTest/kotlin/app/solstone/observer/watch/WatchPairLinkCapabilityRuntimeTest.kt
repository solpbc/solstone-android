// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.testing.validDirectPairLink
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchPairLinkCapabilityRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    @Test
    fun explicitPairViewIntentStaysOnMenuWithoutStartingPairAttempt() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validDirectPairLink()))
            .setClass(context, ObserverActivity::class.java)

        ActivityScenario.launch<ObserverActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val texts = collectTexts(activity.findViewById(android.R.id.content))
                assertTrue(texts.contains("Permissions"))
                assertNull(waitForObserverContainer().controller.lastPairProbe)
            }
        }
    }
}
