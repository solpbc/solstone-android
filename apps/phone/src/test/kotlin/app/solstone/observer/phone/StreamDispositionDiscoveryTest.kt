// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.sources.CALENDAR_STREAM
import app.solstone.core.sources.GLASSES_STREAM
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PHONE_STREAM
import app.solstone.core.sources.WATCH_STREAM
import app.solstone.core.spool.gapSourceFor
import app.solstone.core.watch.courier.WatchCourierProtocol
import app.solstone.observer.formfactor.phone.sourceEarnsSwitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StreamDispositionDiscoveryTest {
    @Test
    fun streamsAreUniqueAndDistinct() {
        val streams = listOf(MAIN_STREAM, PHONE_STREAM, WATCH_STREAM, CALENDAR_STREAM, GLASSES_STREAM)
        assertEquals(streams.size, streams.distinct().size)
        assertEquals("watch", WATCH_STREAM)
        assertEquals("watch-audio", WatchCourierProtocol.SOURCE_ID_WATCH_AUDIO)
    }

    @Test
    fun watchSourceDispositionRules() {
        // Gap classifier preserves watch-audio
        assertEquals("watch-audio", gapSourceFor("watch-audio"))

        // Watch does not earn a toggle switch on phone
        assertFalse(sourceEarnsSwitch("watch"))
    }
}
