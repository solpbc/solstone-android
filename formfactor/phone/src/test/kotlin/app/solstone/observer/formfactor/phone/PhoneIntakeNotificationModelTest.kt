// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneIntakeNotificationModelTest {

    @Test
    fun nullSnapshotReturnsStatusUnavailable() {
        val model = derivePhoneIntakeNotification(snapshot = null, fgsLive = true, desiredOn = true)
        assertEquals(STATUS_UNAVAILABLE, model.stateWord)
        assertNull(model.route)
    }

    @Test
    fun catchingDeriveReturnsStatusUnavailableOnException() {
        val model = derivePhoneIntakeNotificationCatching(
            supplier = { error("simulated snapshot read failure") },
            fgsLive = true,
            desiredOn = true,
        )
        assertEquals(STATUS_UNAVAILABLE, model.stateWord)
        assertNull(model.route)
    }

    @Test
    fun desiredOffReturnsOff() {
        val snapshot = readModel(
            sources = listOf(
                SourceStatus("audio", SourceWish.On, SourceState.ON, ReasonCode.NONE),
            )
        )
        val model = derivePhoneIntakeNotification(snapshot = snapshot, fgsLive = true, desiredOn = false)
        assertEquals("off", model.stateWord)
        assertNull(model.route)
    }

    @Test
    fun noEnabledSourcesReturnsOff() {
        val snapshot = readModel(
            sources = listOf(
                SourceStatus("audio", SourceWish.Off, SourceState.OFF, ReasonCode.NONE),
            )
        )
        val model = derivePhoneIntakeNotification(snapshot = snapshot, fgsLive = true, desiredOn = true)
        assertEquals("off", model.stateWord)
        assertNull(model.route)
    }

    @Test
    fun allEnabledOnAndFgsLiveReturnsOn() {
        val snapshot = readModel(
            sources = listOf(
                SourceStatus("audio", SourceWish.On, SourceState.ON, ReasonCode.NONE),
                SourceStatus("location", SourceWish.On, SourceState.ON, ReasonCode.NONE),
                SourceStatus("camera", SourceWish.Off, SourceState.OFF, ReasonCode.NONE),
            )
        )
        val model = derivePhoneIntakeNotification(snapshot = snapshot, fgsLive = true, desiredOn = true)
        assertEquals("on", model.stateWord)
        assertNull(model.route)
    }

    @Test
    fun audioOffLocationOnReturnsOn() {
        val snapshot = readModel(
            sources = listOf(
                SourceStatus("audio", SourceWish.Off, SourceState.OFF, ReasonCode.NONE),
                SourceStatus("location", SourceWish.On, SourceState.ON, ReasonCode.NONE),
            )
        )
        val model = derivePhoneIntakeNotification(snapshot = snapshot, fgsLive = true, desiredOn = true)
        assertEquals("on", model.stateWord)
        assertNull(model.route)
    }

    @Test
    fun locationOffAudioOnReturnsOn() {
        val snapshot = readModel(
            sources = listOf(
                SourceStatus("audio", SourceWish.On, SourceState.ON, ReasonCode.NONE),
                SourceStatus("location", SourceWish.Off, SourceState.OFF, ReasonCode.NONE),
            )
        )
        val model = derivePhoneIntakeNotification(snapshot = snapshot, fgsLive = true, desiredOn = true)
        assertEquals("on", model.stateWord)
        assertNull(model.route)
    }

    @Test
    fun allEnabledOnButFgsNotLiveDoesNotReturnOn() {
        val snapshot = readModel(
            sources = listOf(
                SourceStatus("audio", SourceWish.On, SourceState.ON, ReasonCode.NONE),
            )
        )
        val model = derivePhoneIntakeNotification(snapshot = snapshot, fgsLive = false, desiredOn = true)
        assertEquals("setting up", model.stateWord)
        assertNull(model.route)
    }

    @Test
    fun audioOnLocationNeedsAttentionReturnsNeedsAttentionAndRoutesToLocation() {
        val snapshot = readModel(
            sources = listOf(
                SourceStatus("audio", SourceWish.On, SourceState.ON, ReasonCode.NONE),
                SourceStatus("location", SourceWish.On, SourceState.NEEDS_ATTENTION, ReasonCode.PERMISSION_REVOKED),
            )
        )
        val model = derivePhoneIntakeNotification(snapshot = snapshot, fgsLive = true, desiredOn = true)
        assertEquals("needs attention", model.stateWord)
        assertEquals(PhoneRoute.SourceDetail("location"), model.route)
    }

    @Test
    fun locationOnAudioNeedsAttentionReturnsNeedsAttentionAndRoutesToAudio() {
        val snapshot = readModel(
            sources = listOf(
                SourceStatus("audio", SourceWish.On, SourceState.NEEDS_ATTENTION, ReasonCode.PERMISSION_REVOKED),
                SourceStatus("location", SourceWish.On, SourceState.ON, ReasonCode.NONE),
            )
        )
        val model = derivePhoneIntakeNotification(snapshot = snapshot, fgsLive = true, desiredOn = true)
        assertEquals("needs attention", model.stateWord)
        assertEquals(PhoneRoute.SourceDetail("audio"), model.route)
    }

    @Test
    fun multipleNeedsAttentionRoutesToDeterministicMinSourceId() {
        val snapshot = readModel(
            sources = listOf(
                SourceStatus("location", SourceWish.On, SourceState.NEEDS_ATTENTION, ReasonCode.STORAGE_FULL),
                SourceStatus("audio", SourceWish.On, SourceState.NEEDS_ATTENTION, ReasonCode.PERMISSION_REVOKED),
            )
        )
        val model = derivePhoneIntakeNotification(snapshot = snapshot, fgsLive = true, desiredOn = true)
        assertEquals("needs attention", model.stateWord)
        assertEquals(PhoneRoute.SourceDetail("audio"), model.route)
    }

    @Test
    fun lastStartRefusedSurfacedAsForegroundStartNotAllowedReturnsNeedsAttention() {
        val snapshot = readModel(
            sources = listOf(
                SourceStatus("audio", SourceWish.On, SourceState.NEEDS_ATTENTION, ReasonCode.FOREGROUND_START_NOT_ALLOWED),
            )
        )
        val model = derivePhoneIntakeNotification(snapshot = snapshot, fgsLive = false, desiredOn = true)
        assertEquals("needs attention", model.stateWord)
        assertEquals(PhoneRoute.SourceDetail("audio"), model.route)
    }

    @Test
    fun foregroundTypeNotHeldReturnsNeedsAttention() {
        val snapshot = readModel(
            sources = listOf(
                SourceStatus("audio", SourceWish.On, SourceState.NEEDS_ATTENTION, ReasonCode.FOREGROUND_TYPE_NOT_HELD),
            )
        )
        val model = derivePhoneIntakeNotification(snapshot = snapshot, fgsLive = true, desiredOn = true)
        assertEquals("needs attention", model.stateWord)
        assertEquals(PhoneRoute.SourceDetail("audio"), model.route)
    }

    @Test
    fun settingUpSourceReturnsSettingUp() {
        val snapshot = readModel(
            sources = listOf(
                SourceStatus("audio", SourceWish.On, SourceState.SETTING_UP, ReasonCode.NONE),
                SourceStatus("location", SourceWish.On, SourceState.ON, ReasonCode.NONE),
            )
        )
        val model = derivePhoneIntakeNotification(snapshot = snapshot, fgsLive = true, desiredOn = true)
        assertEquals("setting up", model.stateWord)
        assertNull(model.route)
    }

    @Test
    fun pausedSourceReturnsPaused() {
        val snapshot = readModel(
            sources = listOf(
                SourceStatus("audio", SourceWish.On, SourceState.PAUSED, ReasonCode.NONE),
                SourceStatus("location", SourceWish.On, SourceState.ON, ReasonCode.NONE),
            )
        )
        val model = derivePhoneIntakeNotification(snapshot = snapshot, fgsLive = true, desiredOn = true)
        assertEquals("paused", model.stateWord)
        assertNull(model.route)
    }

    private fun readModel(sources: List<SourceStatus>): SourcesReadModel =
        SourcesReadModel(
            observer = ObserverStatus(SourceState.ON, ReasonCode.NONE),
            sources = sources,
        )
}
