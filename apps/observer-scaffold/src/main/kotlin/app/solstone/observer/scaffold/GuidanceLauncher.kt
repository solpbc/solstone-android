// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.content.Context
import android.content.Intent
import app.solstone.platform.power.GuidanceAction
import app.solstone.platform.power.GuidanceIntentPreparation
import app.solstone.platform.power.GuidanceLaunchResult
import app.solstone.platform.power.prepareIntent
import app.solstone.platform.power.toIntent

internal fun launchGuidance(context: Context, action: GuidanceAction): GuidanceLaunchResult {
    return when (val prepared = action.prepareIntent(context.packageName)) {
        is GuidanceIntentPreparation.Invalid ->
            GuidanceLaunchResult.Failed("Couldn't open battery settings: ${prepared.reason}")
        is GuidanceIntentPreparation.Ready -> try {
            val intent = prepared.toIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) {
                GuidanceLaunchResult.Failed("Couldn't open battery settings: no settings screen is available")
            } else {
                context.startActivity(intent)
                GuidanceLaunchResult.Launched
            }
        } catch (_: Exception) {
            GuidanceLaunchResult.Failed("Couldn't open battery settings")
        }
    }
}
