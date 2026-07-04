// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GlassesMutationFunnelTest {
    @Test
    fun postCloseSubmitNoOpsAndEmitsOneDiag() {
        val diag = mutableListOf<String>()
        val funnel = GlassesMutationFunnel(
            executor = Executors.newSingleThreadExecutor(),
            diag = diag::add,
        )

        funnel.closeAndAwait(1, TimeUnit.SECONDS)

        val first = funnel.execute("first") { error("must not run") }
        val second = funnel.execute("second") { error("must not run") }

        assertEquals(false, first)
        assertEquals(false, second)
        assertEquals(listOf("funnel-noop site=first reason=closed"), diag)
    }

    @Test
    fun visibleTeardownRunsOnFunnelAndAlwaysStopsForegroundAndReleases() {
        val callerThread = Thread.currentThread().name
        val finished = CountDownLatch(1)
        val teardownThread = arrayOfNulls<String>(1)
        var foregroundStops = 0
        var releases = 0
        val diag = mutableListOf<String>()
        val funnel = GlassesMutationFunnel(
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "test-glasses-funnel").also { it.isDaemon = true }
            },
            diag = diag::add,
        )

        val enqueued = funnel.execute("visible-stop") {
            teardownThread[0] = Thread.currentThread().name
            runVisibleCaptureTeardown(
                stopController = { throw IllegalStateException("stop failed") },
                stopForeground = { foregroundStops += 1 },
                releaseOwner = { releases += 1 },
                diag = { site, throwable -> diag += "$site:${throwable.javaClass.simpleName}" },
            )
            finished.countDown()
        }

        assertEquals(true, enqueued)
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        funnel.closeAndAwait(1, TimeUnit.SECONDS)

        assertNotEquals(callerThread, teardownThread[0])
        assertEquals(1, foregroundStops)
        assertEquals(1, releases)
        assertEquals(listOf("visible-stop:IllegalStateException"), diag)
    }
}
