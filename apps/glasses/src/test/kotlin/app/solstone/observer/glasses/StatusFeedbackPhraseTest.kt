// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.StatusCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusFeedbackPhraseTest {
    @Test
    fun everyStatusCueHasNonBlankPhrase() {
        StatusCue.entries.forEach { cue ->
            assertTrue(phraseFor(cue).isNotBlank(), "Missing phrase for $cue")
        }
    }

    @Test
    fun phrasesAreGenericStateSummaries() {
        assertEquals("on", phraseFor(StatusCue.OBSERVING))
        assertEquals("Not paired", phraseFor(StatusCue.NOT_PAIRED))
        assertEquals("Sync failed", phraseFor(StatusCue.SYNC_FAILED))
        assertEquals("Pairing started", phraseFor(StatusCue.PAIRING_STARTED))
        assertEquals("Network unavailable", phraseFor(StatusCue.NETWORK_UNAVAILABLE))
        assertEquals("Refresh code", phraseFor(StatusCue.REFRESH_CODE))
    }
}
