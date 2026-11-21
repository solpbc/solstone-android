// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import com.google.android.play.agesignals.AgeSignalsAccessResult
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsResult
import com.google.android.play.agesignals.ApplicableAgeFeaturesResult
import com.google.android.play.agesignals.model.AgeRangeSource
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import com.google.android.play.agesignals.model.SignificantChangeStatus
import com.google.android.play.agesignals.testing.FakeAgeSignalsManager
import java.io.File

/**
 * Debug builds ask Google Play, unless a proof scenario is staged on the device, in which case they
 * answer from the library's [FakeAgeSignalsManager]. Release builds have no such path.
 *
 * Stage one with `adb shell run-as <package> sh -c 'echo <scenario> > files/age-signal-proof'`, and
 * remove the file to go back to Play.
 */
internal fun ageSignalsManagerFor(context: Context): AgeSignalsManager {
    val scenario = runCatching { File(context.filesDir, PROOF_FILE).readText().trim() }.getOrNull()
        ?: return AgeSignalsManagerFactory.create(context)
    return proofManager(scenario)
}

private const val PROOF_FILE = "age-signal-proof"

private fun proofManager(scenario: String): AgeSignalsManager {
    val fake = FakeAgeSignalsManager()
    val mandatory = setOf(ApplicableAgeFeature.MANDATORY_SHARING)
    fun features(set: Set<Int>) = fake.setNextApplicableAgeFeaturesResult(ApplicableAgeFeaturesResult.builder().setFeatures(set).build())
    fun access(status: Int) = fake.setNextAgeSignalsAccessResult(AgeSignalsAccessResult.builder().setAgeSignalsStatus(status).build())
    fun signal(lower: Int, upper: Int?, change: Int? = null) {
        val builder = AgeSignalsResult.builder()
            .setAgeRangeSource(if (upper == null) AgeRangeSource.TIER_A else AgeRangeSource.TIER_B)
            .setAgeLower(lower)
        if (upper != null) builder.setAgeUpper(upper)
        if (change != null) builder.setSignificantChangeStatus(change)
        fake.setNextAgeSignalsResult(builder.build())
    }
    when (scenario) {
        "not-required" -> features(emptySet())
        "not-shared" -> { features(mandatory); access(AgeSignalsStatus.NOT_SHARED) }
        "verify" -> { features(mandatory); access(AgeSignalsStatus.VERIFICATION_REQUIRED) }
        "adult" -> { features(mandatory); access(AgeSignalsStatus.SHARED); signal(18, null) }
        "teen" -> { features(mandatory); access(AgeSignalsStatus.SHARED); signal(13, 15) }
        "teen-pending" -> { features(mandatory); access(AgeSignalsStatus.SHARED); signal(16, 17, SignificantChangeStatus.PENDING) }
        "teen-declined" -> { features(mandatory); access(AgeSignalsStatus.SHARED); signal(13, 15, SignificantChangeStatus.DECLINED) }
        "teen-approved" -> { features(mandatory); access(AgeSignalsStatus.SHARED); signal(13, 15, SignificantChangeStatus.APPROVED) }
        "under13" -> { features(mandatory); access(AgeSignalsStatus.SHARED); signal(0, 12) }
        "error-transient" -> fake.setNextApplicableAgeFeaturesException(AgeSignalsException(AgeSignalsErrorCode.NETWORK_ERROR))
        "error-not-owned" -> fake.setNextApplicableAgeFeaturesException(AgeSignalsException(AgeSignalsErrorCode.APP_NOT_OWNED))
        else -> features(emptySet())
    }
    return fake
}
