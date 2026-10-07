// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingProvenance
import app.solstone.core.pl.InitialClientDecision
import app.solstone.core.pl.PairingMigrationRecord
import app.solstone.core.pl.PairingMigrationResult
import app.solstone.core.pl.canOpenDeviceChoice

fun freshPairResultIsCurrent(started: PairingGeneration?, current: PairingGeneration?): Boolean =
    started == current

fun phoneDeviceChoicePending(
    journalConfirmed: Boolean,
    provenance: PairingProvenance?,
    migrationInventorySettled: Boolean,
    migrationStoreUnavailable: Boolean,
    migrationRecord: PairingMigrationRecord?,
): Boolean {
    return journalConfirmed &&
        provenance == PairingProvenance.FRESH_LINK &&
        migrationInventorySettled &&
        (migrationStoreUnavailable || migrationRecord?.canOpenDeviceChoice() == true)
}

sealed interface PhoneFreshPairPlan {
    data object DoNothing : PhoneFreshPairPlan
    data object Drop : PhoneFreshPairPlan
    data class ApplySkip(val record: PairingMigrationRecord) : PhoneFreshPairPlan
    data object ClaimAndShow : PhoneFreshPairPlan
    data class FollowExisting(val result: PairingMigrationResult) : PhoneFreshPairPlan
}

fun planPhoneFreshPair(journalConfirmed: Boolean, decision: InitialClientDecision): PhoneFreshPairPlan {
    if (!journalConfirmed) return PhoneFreshPairPlan.DoNothing
    return when (decision) {
        is InitialClientDecision.StaleGeneration -> PhoneFreshPairPlan.Drop
        is InitialClientDecision.Skipped -> PhoneFreshPairPlan.ApplySkip(decision.record)
        is InitialClientDecision.OthersPresent,
        is InitialClientDecision.PreflightFailed -> PhoneFreshPairPlan.ClaimAndShow
        is InitialClientDecision.NotUnseen -> PhoneFreshPairPlan.FollowExisting(decision.result)
    }
}

sealed interface GlassesFreshPairMenuPlan {
    data class SetPending(val pending: Boolean) : GlassesFreshPairMenuPlan
    data object Drop : GlassesFreshPairMenuPlan
}

fun planGlassesFreshPairMenu(decision: InitialClientDecision): GlassesFreshPairMenuPlan {
    return when (decision) {
        is InitialClientDecision.Skipped -> GlassesFreshPairMenuPlan.SetPending(false)
        is InitialClientDecision.OthersPresent,
        is InitialClientDecision.PreflightFailed -> GlassesFreshPairMenuPlan.SetPending(true)
        is InitialClientDecision.StaleGeneration -> GlassesFreshPairMenuPlan.Drop
        is InitialClientDecision.NotUnseen -> when (val result = decision.result) {
            is PairingMigrationResult.NoOffer -> GlassesFreshPairMenuPlan.SetPending(false)
            is PairingMigrationResult.Offer -> GlassesFreshPairMenuPlan.SetPending(result.record.canOpenDeviceChoice())
            else -> GlassesFreshPairMenuPlan.SetPending(true)
        }
    }
}

sealed interface GlassesFreshPairChoicePlan {
    data object ReturnToMenu : GlassesFreshPairChoicePlan
    data object Drop : GlassesFreshPairChoicePlan
    data object ClaimAndShow : GlassesFreshPairChoicePlan
    data class FollowExisting(val result: PairingMigrationResult) : GlassesFreshPairChoicePlan
}

fun planGlassesFreshPairChoice(decision: InitialClientDecision): GlassesFreshPairChoicePlan {
    return when (decision) {
        is InitialClientDecision.Skipped -> GlassesFreshPairChoicePlan.ReturnToMenu
        is InitialClientDecision.StaleGeneration -> GlassesFreshPairChoicePlan.Drop
        is InitialClientDecision.OthersPresent,
        is InitialClientDecision.PreflightFailed -> GlassesFreshPairChoicePlan.ClaimAndShow
        is InitialClientDecision.NotUnseen -> GlassesFreshPairChoicePlan.FollowExisting(decision.result)
    }
}
