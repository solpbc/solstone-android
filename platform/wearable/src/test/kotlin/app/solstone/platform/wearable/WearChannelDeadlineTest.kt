// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.wearable

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WearChannelDeadlineTest {
    @Test
    fun deadlineReleasesARealBlockedReadAndClosesTheChannelOnce() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use {
                server.accept().use { receiver ->
                    val closed = AtomicInteger()
                    val done = CountDownLatch(1)
                    WearChannelDeadline(timeoutMs = 500) { closed.incrementAndGet() }.use { deadline ->
                        val input = deadline.own(receiver.getInputStream())
                        val reader = Thread {
                            try {
                                input.read()
                            } catch (_: Exception) {
                            } finally {
                                done.countDown()
                            }
                        }.apply { isDaemon = true; start() }
                        try {
                            assertTrue(done.await(5, TimeUnit.SECONDS), "blocked read did not exit")
                            assertTrue(deadline.timedOut)
                            deadline.close()
                            assertEquals(1, closed.get())
                        } finally {
                            receiver.close()
                            reader.join(1000)
                        }
                    }
                }
            }
        }
    }
}
