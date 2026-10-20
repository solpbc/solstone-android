// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.platform.persistence.room.ImportChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportPaneWordsTest {
    @Test
    fun aSharedItemsTypeIsAnOwnerWordNeverAMediaType() {
        assertEquals("PDF", ownerTypeWord("application/pdf"))
        assertEquals("image", ownerTypeWord("image/heic"))
        assertEquals("audio", ownerTypeWord("audio/mpeg; codecs=mp3"))
        assertEquals("text", ownerTypeWord("text/plain"))
        assertEquals("file", ownerTypeWord("video/mp4"))
        assertEquals("file", ownerTypeWord(null))
    }

    @Test
    fun aRemoveOrRetryThatWentAsAskedSaysNothingAndEveryOtherAnswerIsSaid() {
        assertNull(importChangeText(ImportChange.DONE))
        ImportChange.values().filter { it != ImportChange.DONE }.forEach { change ->
            assert(importChangeText(change) != null) { "$change has no words" }
        }
    }
}
