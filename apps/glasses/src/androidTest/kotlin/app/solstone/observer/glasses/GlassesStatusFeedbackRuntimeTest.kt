// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassesStatusFeedbackRuntimeTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @After
    fun resetRuntime() {
        GlassesHarnessRuntime.hooks = null
        GlassesHarnessRuntime.runtime?.closeForTest()
        GlassesHarnessRuntime.runtime = null
    }

    @Test
    fun speakCurrentStatusUsesFakeAudioFeedback() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val container = GlassesHarnessRuntime.container as? GlassesAppContainer ?: run {
                assumeTrue("glasses harness container was not created", false)
                error("unreachable")
            }
            val audio = container.flavor.audioFeedback as FakeAudioFeedback

            container.speakCurrentStatus()

            assertEquals(R.raw.fb_observer_paused, waitForPlayedResId(audio))
        }
    }

    private fun waitForPlayedResId(audio: FakeAudioFeedback): Int? {
        repeat(30) {
            audio.lastPlayedResId?.let { return it }
            Thread.sleep(100L)
        }
        return null
    }
}
