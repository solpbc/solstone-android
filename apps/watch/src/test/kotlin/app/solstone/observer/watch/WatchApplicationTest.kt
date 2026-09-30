// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import app.solstone.core.identity.JournalConfirmationPolicy
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchApplicationTest {

    @Before
    fun setUp() {
        JournalConfirmationPolicy.consults = true
    }

    @After
    fun tearDown() {
        JournalConfirmationPolicy.consults = true
    }

    @Test
    fun onCreateOptsOutOfJournalConfirmationPolicyBeforeSuper() {
        assertTrue(JournalConfirmationPolicy.consults)
        val app = WatchApplication()
        try {
            app.onCreate()
        } catch (_: Throwable) {
            // Android framework stub throw expected in host unit tests
        }
        assertFalse(JournalConfirmationPolicy.consults)
    }
}
