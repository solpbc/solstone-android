// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingProvenance
import app.solstone.core.pl.InitialClientDecision
import app.solstone.core.pl.PairingMigrationRecord
import app.solstone.core.pl.PairingMigrationResult
import app.solstone.core.pl.PairingMigrationStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreshPairDeviceChoiceTest {
    private val generation = PairingGeneration("journal-1", "sha256:${"a".repeat(64)}")
    private val callerCid = "sha256:${"a".repeat(64)}"

    private fun record(stage: PairingMigrationStage) = PairingMigrationRecord(
        generation = generation,
        callerCid = callerCid,
        stage = stage,
    )

    @Test
    fun phonePendingFormulaTreatsUnsettledAndNullRecordAndSkippedAsNotPending() {
        val shown = record(PairingMigrationStage.SHOWN_DEFERRED)
        val skipped = record(PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT)

        // Unconfirmed is false
        assertFalse(
            phoneDeviceChoicePending(
                journalConfirmed = false,
                provenance = PairingProvenance.FRESH_LINK,
                migrationInventorySettled = true,
                migrationStoreUnavailable = false,
                migrationRecord = shown,
            ),
        )

        // Confirmed but unsettled is false
        assertFalse(
            phoneDeviceChoicePending(
                journalConfirmed = true,
                provenance = PairingProvenance.FRESH_LINK,
                migrationInventorySettled = false,
                migrationStoreUnavailable = false,
                migrationRecord = shown,
            ),
        )

        // Confirmed settled null record is false
        assertFalse(
            phoneDeviceChoicePending(
                journalConfirmed = true,
                provenance = PairingProvenance.FRESH_LINK,
                migrationInventorySettled = true,
                migrationStoreUnavailable = false,
                migrationRecord = null,
            ),
        )

        // Confirmed settled skipped record is false
        assertFalse(
            phoneDeviceChoicePending(
                journalConfirmed = true,
                provenance = PairingProvenance.FRESH_LINK,
                migrationInventorySettled = true,
                migrationStoreUnavailable = false,
                migrationRecord = skipped,
            ),
        )

        // Confirmed settled SHOWN_DEFERRED is true
        assertTrue(
            phoneDeviceChoicePending(
                journalConfirmed = true,
                provenance = PairingProvenance.FRESH_LINK,
                migrationInventorySettled = true,
                migrationStoreUnavailable = false,
                migrationRecord = shown,
            ),
        )

        // Confirmed settled store-unavailable is true even with a null record
        assertTrue(
            phoneDeviceChoicePending(
                journalConfirmed = true,
                provenance = PairingProvenance.FRESH_LINK,
                migrationInventorySettled = true,
                migrationStoreUnavailable = true,
                migrationRecord = null,
            ),
        )

        // Non-fresh provenance is false
        assertFalse(
            phoneDeviceChoicePending(
                journalConfirmed = true,
                provenance = PairingProvenance.UNKNOWN_LEGACY,
                migrationInventorySettled = true,
                migrationStoreUnavailable = false,
                migrationRecord = shown,
            ),
        )
    }

    @Test
    fun phonePlanningRespectsJournalConfirmationGate() {
        val unseen = record(PairingMigrationStage.OFFER_NOT_SHOWN)
        val skipped = record(PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT)

        assertEquals(
            PhoneFreshPairPlan.DoNothing,
            planPhoneFreshPair(journalConfirmed = false, InitialClientDecision.OthersPresent(unseen)),
        )
        assertEquals(
            PhoneFreshPairPlan.ClaimAndShow,
            planPhoneFreshPair(journalConfirmed = true, InitialClientDecision.OthersPresent(unseen)),
        )
        assertEquals(
            PhoneFreshPairPlan.ClaimAndShow,
            planPhoneFreshPair(journalConfirmed = true, InitialClientDecision.PreflightFailed),
        )
        assertEquals(
            PhoneFreshPairPlan.ApplySkip(skipped),
            planPhoneFreshPair(journalConfirmed = true, InitialClientDecision.Skipped(skipped)),
        )
        assertEquals(
            PhoneFreshPairPlan.Drop,
            planPhoneFreshPair(journalConfirmed = true, InitialClientDecision.StaleGeneration),
        )
        assertEquals(
            PhoneFreshPairPlan.FollowExisting(PairingMigrationResult.NoOffer),
            planPhoneFreshPair(journalConfirmed = true, InitialClientDecision.NotUnseen(PairingMigrationResult.NoOffer)),
        )
    }

    @Test
    fun glassesMenuPlanningMapsInitialDecisionsToPendingState() {
        val unseen = record(PairingMigrationStage.OFFER_NOT_SHOWN)
        val skipped = record(PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT)
        val shown = record(PairingMigrationStage.SHOWN_DEFERRED)

        assertEquals(
            GlassesFreshPairMenuPlan.SetPending(false),
            planGlassesFreshPairMenu(InitialClientDecision.Skipped(skipped)),
        )
        assertEquals(
            GlassesFreshPairMenuPlan.SetPending(true),
            planGlassesFreshPairMenu(InitialClientDecision.OthersPresent(unseen)),
        )
        assertEquals(
            GlassesFreshPairMenuPlan.SetPending(true),
            planGlassesFreshPairMenu(InitialClientDecision.PreflightFailed),
        )
        assertEquals(
            GlassesFreshPairMenuPlan.Drop,
            planGlassesFreshPairMenu(InitialClientDecision.StaleGeneration),
        )
        assertEquals(
            GlassesFreshPairMenuPlan.SetPending(false),
            planGlassesFreshPairMenu(InitialClientDecision.NotUnseen(PairingMigrationResult.NoOffer)),
        )
        assertEquals(
            GlassesFreshPairMenuPlan.SetPending(true),
            planGlassesFreshPairMenu(InitialClientDecision.NotUnseen(PairingMigrationResult.Offer(shown))),
        )
    }

    @Test
    fun glassesChoicePlanningDismissesOnSkippedAndStale() {
        val unseen = record(PairingMigrationStage.OFFER_NOT_SHOWN)
        val skipped = record(PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT)

        assertEquals(
            GlassesFreshPairChoicePlan.ReturnToMenu,
            planGlassesFreshPairChoice(InitialClientDecision.Skipped(skipped)),
        )
        assertEquals(
            GlassesFreshPairChoicePlan.Drop,
            planGlassesFreshPairChoice(InitialClientDecision.StaleGeneration),
        )
        assertEquals(
            GlassesFreshPairChoicePlan.ClaimAndShow,
            planGlassesFreshPairChoice(InitialClientDecision.OthersPresent(unseen)),
        )
        assertEquals(
            GlassesFreshPairChoicePlan.ClaimAndShow,
            planGlassesFreshPairChoice(InitialClientDecision.PreflightFailed),
        )
    }

    @Test
    fun freshPairResultIsCurrentDetectsGenerationMismatch() {
        val gen1 = PairingGeneration("journal-1", "sha256:${"a".repeat(64)}")
        val gen2 = PairingGeneration("journal-2", "sha256:${"b".repeat(64)}")

        assertTrue(freshPairResultIsCurrent(gen1, gen1))
        assertTrue(freshPairResultIsCurrent(null, null))
        assertFalse(freshPairResultIsCurrent(gen1, gen2))
        assertFalse(freshPairResultIsCurrent(gen1, null))
        assertFalse(freshPairResultIsCurrent(null, gen1))
    }
}
