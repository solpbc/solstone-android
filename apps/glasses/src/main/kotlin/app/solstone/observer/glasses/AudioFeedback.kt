// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import androidx.annotation.RawRes

fun interface AudioFeedback {
    fun play(@RawRes resId: Int)
}
