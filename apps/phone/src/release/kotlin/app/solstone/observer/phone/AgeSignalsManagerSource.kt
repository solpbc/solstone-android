// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory

/** A release build only ever asks Google Play. */
internal fun ageSignalsManagerFor(context: Context): AgeSignalsManager = AgeSignalsManagerFactory.create(context)
