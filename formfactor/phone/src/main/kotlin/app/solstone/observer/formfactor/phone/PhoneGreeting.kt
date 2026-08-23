// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

fun greetingFor(hour: Int): String = when (hour) {
    in 5..11 -> "good morning"
    in 12..16 -> "good afternoon"
    else -> "good evening"
}
