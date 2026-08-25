// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.solstone.observer.scaffold.ObserverActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObserverActivityDirectScanRuntimeTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(application)
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    @Test
    fun scanPairQrExtraShowsCameraPreviewInsteadOfMenu() {
        val intent = Intent(application, ObserverActivity::class.java)
            .putExtra(ObserverActivity.EXTRA_SCAN_PAIR_QR, true)
        ActivityScenario.launch<ObserverActivity>(intent).use { scenario ->
            waitUntil("QR preview shown") {
                var present = false
                scenario.onActivity { activity ->
                    present = allActivityViews(activity).any { it.javaClass.simpleName.contains("QrPreviewView") }
                }
                present
            }
            scenario.onActivity { activity ->
                assertFalse(activityTexts(activity).contains("Permissions"))
            }
        }
    }

    @Test
    fun scanPairQrExtraTakesPrecedenceOverViewPairLink() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://go.solstone.app/p#garbage"))
            .setClass(application, ObserverActivity::class.java)
            .putExtra(ObserverActivity.EXTRA_SCAN_PAIR_QR, true)
        ActivityScenario.launch<ObserverActivity>(intent).use { scenario ->
            waitUntil("QR preview shown") {
                var present = false
                scenario.onActivity { activity ->
                    present = allActivityViews(activity).any { it.javaClass.simpleName.contains("QrPreviewView") }
                }
                present
            }
            scenario.onActivity { activity ->
                assertFalse(activityTexts(activity).contains("Invalid pair link"))
            }
        }
    }
}
