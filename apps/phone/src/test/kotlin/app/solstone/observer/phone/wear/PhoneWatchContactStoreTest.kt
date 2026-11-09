// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.wear

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneWatchContactStoreTest {
    @Test
    fun tracksContactLifecycle() {
        val dir = Files.createTempDirectory("watch-contact-test").toFile()
        val store = PhoneWatchContactStore(dir)

        assertFalse(store.hasContact())
        assertNull(store.lastContactEpochMs())

        val now = 1762682400000L
        store.recordContact(now)

        assertTrue(store.hasContact())
        assertEquals(now, store.lastContactEpochMs())

        store.clearContact()
        assertFalse(store.hasContact())
        assertNull(store.lastContactEpochMs())
    }
}
