// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MuxTableHostTest {

    private class PipeDuplex : ByteDuplex {
        val toPeer = ByteArrayOutputStream()
        var fromPeer: InputStream = ByteArrayInputStream(ByteArray(0))

        override val input: InputStream
            get() = fromPeer
        override val output: OutputStream
            get() = toPeer

        override fun close() {}
    }

    @Test
    fun dataAndResetRequestLocal() {
        val classification = MuxClassifier.classify(
            streamId = 1,
            flags = FLAG_DATA or FLAG_RESET,
            payloadLength = 1,
            activeStreamId = 1,
            nextStreamId = 3,
            isPreOpen = false,
        )
        assertEquals(MuxClassification.ACTIVE_INVALID_FLAGS, classification)
    }

    @Test
    fun windowAndCloseRequestLocal() {
        val classification = MuxClassifier.classify(
            streamId = 1,
            flags = FLAG_WINDOW or FLAG_CLOSE,
            payloadLength = 4,
            activeStreamId = 1,
            nextStreamId = 3,
            isPreOpen = false,
        )
        assertEquals(MuxClassification.ACTIVE_INVALID_FLAGS, classification)
    }

    @Test
    fun pastLocalDropNoReset() {
        val classification = MuxClassifier.classify(
            streamId = 1,
            flags = FLAG_DATA,
            payloadLength = 10,
            activeStreamId = 3,
            nextStreamId = 5,
            isPreOpen = false,
        )
        assertEquals(MuxClassification.PAST_LOCAL_DROP, classification)
    }

    @Test
    fun futureLocalSessionTerminal() {
        val classification = MuxClassifier.classify(
            streamId = 7,
            flags = FLAG_DATA,
            payloadLength = 10,
            activeStreamId = 1,
            nextStreamId = 3,
            isPreOpen = false,
        )
        assertEquals(MuxClassification.SESSION_TERMINAL_FUTURE_LOCAL, classification)
    }

    @Test
    fun foreignEvenCloseSendsReset() {
        val classification = MuxClassifier.classify(
            streamId = 2,
            flags = FLAG_CLOSE,
            payloadLength = 0,
            activeStreamId = 1,
            nextStreamId = 3,
            isPreOpen = false,
        )
        assertEquals(MuxClassification.FOREIGN_EVEN_RESET, classification)
    }

    @Test
    fun foreignResetIgnore() {
        val classification = MuxClassifier.classify(
            streamId = 2,
            flags = FLAG_RESET,
            payloadLength = 1,
            activeStreamId = 1,
            nextStreamId = 3,
            isPreOpen = false,
        )
        assertEquals(MuxClassification.FOREIGN_EVEN_IGNORE, classification)
    }

    @Test
    fun pingPongOnControlStream() {
        val ping = MuxClassifier.classify(
            streamId = 0,
            flags = FLAG_PING,
            payloadLength = 8,
            activeStreamId = 1,
            nextStreamId = 3,
            isPreOpen = false,
        )
        assertEquals(MuxClassification.CONTROL_PING, ping)

        val pong = MuxClassifier.classify(
            streamId = 0,
            flags = FLAG_PONG,
            payloadLength = 8,
            activeStreamId = 1,
            nextStreamId = 3,
            isPreOpen = false,
        )
        assertEquals(MuxClassification.CONTROL_PONG, pong)
    }

    @Test
    fun preOpenWindowCreditUnblocksSend() {
        val preOpenWindow = MuxClassifier.classify(
            streamId = 1,
            flags = FLAG_WINDOW,
            payloadLength = 4,
            activeStreamId = 1,
            nextStreamId = 3,
            isPreOpen = true,
        )
        assertEquals(MuxClassification.ACTIVE_WINDOW, preOpenWindow)
    }

    @Test
    fun postOpenDuplicateOpenIsRequestTerminal() {
        val duplicateOpen = MuxClassifier.classify(
            streamId = 1,
            flags = FLAG_OPEN or FLAG_DATA,
            payloadLength = 10,
            activeStreamId = 1,
            nextStreamId = 3,
            isPreOpen = false,
        )
        assertEquals(MuxClassification.ACTIVE_INVALID_FLAGS, duplicateOpen)
    }

    @Test
    fun dialerMaxIntRefuseBeforeWrapAndFreshCarrierStartsAtOne() {
        val dialer = FrameDialer(Int.MAX_VALUE)
        val maxId = dialer.allocate()
        assertEquals(Int.MAX_VALUE, maxId)

        // Refuse subsequent allocation (wrap prevention)
        assertFailsWith<IllegalStateException> {
            dialer.allocate()
        }

        // Fresh dialer starts back at 1
        val freshDialer = FrameDialer(1)
        assertEquals(1, freshDialer.allocate())
        assertEquals(3, freshDialer.allocate())
    }
}
