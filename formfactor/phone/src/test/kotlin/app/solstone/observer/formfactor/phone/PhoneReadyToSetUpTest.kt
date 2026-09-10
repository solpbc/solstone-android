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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A source the owner has never set up, on every phone surface that aggregates attention.
 *
 * 🔴 **There are FOUR aggregates, not one**, and each one had to be checked separately: the deck's
 * `N need attention` count, the ongoing intake notification, the home-screen widget's colour role,
 * and the observer-level `needsAttentionForState`. Fixing the state word and stopping would have
 * left a fresh install rendering `ready to set up` in error colours on the widget — the exact
 * confident-wrongness the honest-state rules exist to prevent, one surface over.
 */
class PhoneReadyToSetUpTest {
    @Test
    fun theStateWordIsTheLockedVocabularyAndTheMarkIsDistinct() {
        assertEquals("ready to set up", sourceStateCopy(SourceState.READY_TO_SET_UP))
        assertEquals(TileDotMark.PLUS, tileDotMark(SourceState.READY_TO_SET_UP))
        // ⚠ Two indicators, not one: the accessible value carries the word and the dot carries the
        // mark, so neither colour nor shape is load-bearing alone.
        assertEquals(
            SourceState.entries.size,
            SourceState.entries.map(::tileDotMark).distinct().size,
        )
    }

    @Test
    fun aFreshInstallNotifiesOffRatherThanNeedsAttention() {
        val model = derivePhoneIntakeNotification(
            snapshot = freshInstall(),
            fgsLive = false,
            desiredOn = true,
        )
        // ⛔ Not `needs attention`, and ⛔ not `setting up` either — nothing has been asked for, so
        // nothing is starting.
        assertEquals("off", model.stateWord)
        assertNull(model.route)
    }

    @Test
    fun anExpressedSourceStillNotifiesNeedsAttention() {
        // ✅ Positive control for the test above: the same function, one field different.
        val model = derivePhoneIntakeNotification(
            snapshot = SourcesReadModel(
                observer = ObserverStatus(SourceState.NEEDS_ATTENTION, ReasonCode.PERMISSION_REVOKED),
                sources = listOf(
                    SourceStatus(
                        "audio",
                        SourceWish.On,
                        SourceState.NEEDS_ATTENTION,
                        ReasonCode.PERMISSION_REVOKED,
                        wishExpressed = true,
                    ),
                ),
            ),
            fgsLive = false,
            desiredOn = true,
        )
        assertEquals("needs attention", model.stateWord)
        assertEquals(PhoneRoute.SourceDetail("audio"), model.route)
    }

    @Test
    fun theDeckCountsOnlyFaultsAndAFreshInstallHasNone() {
        // The deck's count is `count { it.state == NEEDS_ATTENTION }`; this pins the population it
        // runs over rather than the expression, because the expression is what a later edit changes.
        assertEquals(0, freshInstall().sources.count { it.state == SourceState.NEEDS_ATTENTION })
        assertEquals(3, freshInstall().sources.size)
    }

    @Test
    fun theWidgetRendersReadyToSetUpInActiveColoursNotAttention() {
        val model = renderPhoneObserverWidget(
            readModel = freshInstall(),
            statusModel = PhoneStatusModel(paired = true, online = true, pendingCount = 0, hasContentPending = false),
            startOutcome = PhoneWidgetStartOutcome.None,
        )
        assertEquals("ready to set up", model.stateWord)
        assertFalse(model.needsAttention, "⛔ a source nobody asked for is not a fault")
        assertFalse(model.audioChecked)
        assertFalse(model.audioWishOn)
        assertEquals(ReasonCode.NONE, model.reason)
        assertTrue(PhoneObserverWidgetColorRole.ACTIVE in model.colors)
        assertFalse(PhoneObserverWidgetColorRole.ATTENTION in model.colors)
    }

    @Test
    fun anUnreadableReadModelStillRendersAttention() {
        // ✅ Positive control on the same field. ⚠ And the distinction that matters: we could not
        // read the sources, which is NOT the same as reading them and finding nothing wrong.
        val model = renderPhoneObserverWidget(
            readModel = null,
            statusModel = PhoneStatusModel(paired = true, online = true, pendingCount = 0, hasContentPending = false),
            startOutcome = PhoneWidgetStartOutcome.None,
        )
        assertTrue(model.needsAttention)
        assertTrue(PhoneObserverWidgetColorRole.ATTENTION in model.colors)
    }

    @Test
    fun anOwnerWhoTurnedASourceOffStillReadsOffAndNotReadyToSetUp() {
        // ⚠ The rule running the other way. Collapsing these two would tell an owner they never
        // made a choice they did make.
        val model = renderPhoneObserverWidget(
            readModel = SourcesReadModel(
                observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
                sources = listOf(
                    SourceStatus("audio", SourceWish.Off, SourceState.OFF, ReasonCode.NONE, wishExpressed = true),
                ),
            ),
            statusModel = PhoneStatusModel(paired = true, online = true, pendingCount = 0, hasContentPending = false),
            startOutcome = PhoneWidgetStartOutcome.None,
        )
        assertEquals("off", model.stateWord)
        assertFalse(model.needsAttention)
    }

    private fun freshInstall(): SourcesReadModel = SourcesReadModel(
        observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
        sources = listOf("audio", "location", "camera").map {
            SourceStatus(it, SourceWish.Off, SourceState.READY_TO_SET_UP, ReasonCode.NONE, wishExpressed = false)
        },
    )
}
