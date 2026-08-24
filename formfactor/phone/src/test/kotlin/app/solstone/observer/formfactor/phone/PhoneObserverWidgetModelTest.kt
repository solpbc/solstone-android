// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.SilencedFact
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.SourceWish
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneObserverWidgetModelTest {
    @Test
    fun realHarnessAndRegistryFixtureConstructsAndSnapshots() {
        val snapshot = PhoneObserverWidgetHarnessFixture().snapshot(
            providerFresh = true,
            silenced = SilencedFact.NOT_SILENCED,
        )

        assertEquals(SourceState.ON, snapshot.observer.state)
        assertEquals(SourceState.ON, snapshot.sources.single { it.sourceId == "audio" }.state)
    }

    @Test
    fun globalFreshnessAndSilencingMatrixFlowsThroughSourceRegistry() {
        val fixture = PhoneObserverWidgetHarnessFixture()

        val freshNotSilenced = render(fixture, providerFresh = true, silenced = SilencedFact.NOT_SILENCED)
        assertEquals("on", freshNotSilenced.stateWord)
        assertTrue(freshNotSilenced.audioChecked)

        val freshSilenced = render(fixture, providerFresh = true, silenced = SilencedFact.SILENCED)
        assertEquals("paused", freshSilenced.stateWord)
        assertFalse(freshSilenced.audioChecked)

        val staleNotSilenced = render(fixture, providerFresh = false, silenced = SilencedFact.NOT_SILENCED)
        assertEquals("needs attention", staleNotSilenced.stateWord)
        assertFalse(staleNotSilenced.audioChecked)

        val staleSilenced = render(fixture, providerFresh = false, silenced = SilencedFact.SILENCED)
        assertEquals("needs attention", staleSilenced.stateWord)
        assertFalse(staleSilenced.audioChecked)
    }

    @Test
    fun silencedAudioUnderAnOnObserverRendersPausedAndUnchecked() {
        val fixture = PhoneObserverWidgetHarnessFixture()
        val snapshot = fixture.snapshot(
            providerFresh = true,
            silenced = SilencedFact.NOT_SILENCED,
            audioSilenced = SilencedFact.SILENCED,
        )

        assertEquals(SourceState.ON, snapshot.observer.state)
        assertEquals(SourceState.PAUSED, snapshot.sources.single { it.sourceId == "audio" }.state)
        assertEquals(SourceState.ON, snapshot.sources.single { it.sourceId == "location" }.state)

        val model = renderPhoneObserverWidget(
            readModel = snapshot,
            statusModel = status(),
            startOutcome = PhoneWidgetStartOutcome.None,
        )

        assertFalse(model.audioChecked)
        assertEquals("paused", model.stateWord)
    }

    @Test
    fun offWishIsUncheckedAndUsesTheOffStateWord() {
        val fixture = PhoneObserverWidgetHarnessFixture()
        fixture.setAudioWish(SourceWish.Off)

        val model = render(fixture, providerFresh = true, silenced = SilencedFact.NOT_SILENCED)

        assertFalse(model.audioChecked)
        assertFalse(model.audioWishOn)
        assertEquals("off", renderPhoneObserverWidget(
            readModel = fixture.snapshot(providerFresh = true, silenced = SilencedFact.NOT_SILENCED),
            statusModel = status(),
            startOutcome = PhoneWidgetStartOutcome.None,
        ).stateWord)
    }

    @Test
    fun rejectedWidgetStartIsOffWithAttentionWithoutLatchingWish() {
        val fixture = PhoneObserverWidgetHarnessFixture()

        val model = renderPhoneObserverWidget(
            readModel = fixture.snapshot(providerFresh = true, silenced = SilencedFact.NOT_SILENCED),
            statusModel = status(),
            startOutcome = PhoneWidgetStartOutcome.Refused(ReasonCode.FOREGROUND_START_NOT_ALLOWED),
        )

        assertFalse(model.audioChecked)
        assertTrue(model.audioWishOn)
        assertTrue(model.needsAttention)
        assertEquals("off", model.stateWord)
        assertEquals(ReasonCode.FOREGROUND_START_NOT_ALLOWED, model.reason)
    }

    @Test
    fun notificationPresentationUsesApprovedStateWordAndStatusPillText() {
        val model = renderPhoneObserverWidget(
            readModel = PhoneObserverWidgetHarnessFixture().snapshot(true, SilencedFact.NOT_SILENCED),
            statusModel = status(pendingCount = 3),
            startOutcome = PhoneWidgetStartOutcome.None,
        )

        assertEquals("on", model.stateWord)
        assertEquals("3 syncing", model.syncText)
    }

    @Test
    fun pendingCountIsCopiedWithoutInventingProgressCopy() {
        val model = renderPhoneObserverWidget(
            readModel = PhoneObserverWidgetHarnessFixture().snapshot(true, SilencedFact.NOT_SILENCED),
            statusModel = status(pendingCount = 7),
            startOutcome = PhoneWidgetStartOutcome.None,
        )

        assertEquals(7, model.pendingCount)
        assertEquals("7 syncing", model.syncText)
    }

    @Test
    fun lightAndDarkPaletteUseOnlyNamedSolstoneRoles() {
        val normal = renderPhoneObserverWidget(
            readModel = PhoneObserverWidgetHarnessFixture().snapshot(true, SilencedFact.NOT_SILENCED),
            statusModel = status(),
            startOutcome = PhoneWidgetStartOutcome.None,
        )
        val attention = renderPhoneObserverWidget(
            readModel = null,
            statusModel = status(),
            startOutcome = PhoneWidgetStartOutcome.None,
        )

        assertEquals(
            setOf(
                PhoneObserverWidgetColorRole.SURFACE,
                PhoneObserverWidgetColorRole.CONTENT,
                PhoneObserverWidgetColorRole.ACTIVE,
            ),
            normal.colors,
        )
        assertEquals(
            setOf(
                PhoneObserverWidgetColorRole.SURFACE,
                PhoneObserverWidgetColorRole.CONTENT,
                PhoneObserverWidgetColorRole.ATTENTION,
            ),
            attention.colors,
        )
    }

    private fun render(
        fixture: PhoneObserverWidgetHarnessFixture,
        providerFresh: Boolean,
        silenced: SilencedFact,
    ): PhoneObserverWidgetModel =
        renderPhoneObserverWidget(
            readModel = fixture.snapshot(providerFresh, silenced),
            statusModel = status(),
            startOutcome = PhoneWidgetStartOutcome.None,
        )

    private fun status(pendingCount: Int = 0): PhoneStatusModel =
        PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = pendingCount,
            hasContentPending = pendingCount > 0,
        )
}
