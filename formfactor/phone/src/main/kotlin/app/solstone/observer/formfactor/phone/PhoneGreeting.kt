// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.i18n.phoneText

fun greetingFor(hour: Int): String = when (hour) {
    in 5..11 -> phoneText("android.setup.good.morning")
    in 12..16 -> phoneText("android.setup.good.afternoon")
    else -> phoneText("android.setup.good.evening")
}
