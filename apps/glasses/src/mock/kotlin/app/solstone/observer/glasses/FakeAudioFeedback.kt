// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import androidx.annotation.RawRes
import java.util.concurrent.CopyOnWriteArrayList

class FakeAudioFeedback : AudioFeedback {
    @Volatile
    var lastPlayedResId: Int? = null
        private set
    val played = CopyOnWriteArrayList<Int>()

    override fun play(@RawRes resId: Int) {
        lastPlayedResId = resId
        played.add(resId)
    }
}
