// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MuxSessionObserverTest {
    @Test
    fun reportsOrderedDeltasAndSuccessfulTermination() {
        val first = "HTTP/1.1 200 OK\r\ncontent-length: 3\r\n\r\n".toByteArray()
        val second = "abc".toByteArray()
        val duplex = scriptedDuplex(
            encodeFrame(1, FLAG_DATA, first),
            encodeFrame(1, FLAG_DATA or FLAG_CLOSE, second),
        )
        val events = mutableListOf<String>()
        val observer = object : PlStreamObserver {
            override fun onStreamOpened(streamId: Int) {
                events += "open:$streamId"
            }

            override fun onResponseDataConsumed(streamId: Int, deltaBytes: Int, cumulativeBytes: Int) {
                events += "data:$streamId:$deltaBytes:$cumulativeBytes"
            }

            override fun onStreamTerminated(streamId: Int, successful: Boolean) {
                events += "end:$streamId:$successful"
            }
        }

        val response = MuxSession(duplex, observer).request("GET", "/segments", emptyMap(), null)

        assertContentEquals("abc".toByteArray(), response.body)
        assertEquals(
            listOf(
                "open:1",
                "data:1:${first.size}:${first.size}",
                "data:1:${second.size}:${first.size + second.size}",
                "end:1:true",
            ),
            events,
        )
    }

    @Test
    fun terminatesFailedStreamSoObserverActiveCountReturnsToZero() {
        var active = 0
        val events = mutableListOf<String>()
        val observer = object : PlStreamObserver {
            override fun onStreamOpened(streamId: Int) {
                active += 1
                events += "open"
            }

            override fun onResponseDataConsumed(streamId: Int, deltaBytes: Int, cumulativeBytes: Int) {
                events += "data"
            }

            override fun onStreamTerminated(streamId: Int, successful: Boolean) {
                active -= 1
                events += "end:$successful"
            }
        }

        val error = assertFailsWith<IOException> {
            MuxSession(scriptedDuplex(byteArrayOf(1, 2, 3)), observer)
                .request("GET", "/broken", emptyMap(), null)
        }

        assertEquals("socket closed while reading PL frame", error.message)
        assertEquals(0, active)
        assertEquals(listOf("open", "end:false"), events)
    }

    @Test
    fun observerFailureDoesNotChangeTransportResult() {
        val responseBytes = "HTTP/1.1 200 OK\r\ncontent-length: 2\r\n\r\nok".toByteArray()
        val baseline = scriptedDuplex(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, responseBytes))
        val observed = scriptedDuplex(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, responseBytes))
        val throwing = object : PlStreamObserver {
            override fun onStreamOpened(streamId: Int) = error("observer open")
            override fun onResponseDataConsumed(streamId: Int, deltaBytes: Int, cumulativeBytes: Int) = error("observer data")
            override fun onStreamTerminated(streamId: Int, successful: Boolean) = error("observer end")
        }

        val baselineResponse = MuxSession(baseline).request("GET", "/same", emptyMap(), null)
        val observedResponse = MuxSession(observed, throwing).request("GET", "/same", emptyMap(), null)

        assertEquals(baselineResponse.status, observedResponse.status)
        assertEquals(baselineResponse.headers, observedResponse.headers)
        assertContentEquals(baselineResponse.body, observedResponse.body)
        assertContentEquals(baseline.written(), observed.written())
    }

    @Test
    fun explicitNullObserverMatchesDefaultConstructor() {
        val responseBytes = "HTTP/1.1 200 OK\r\ncontent-length: 2\r\n\r\nok".toByteArray()
        val defaultDuplex = scriptedDuplex(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, responseBytes))
        val nullDuplex = scriptedDuplex(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, responseBytes))

        val defaultResponse = MuxSession(defaultDuplex).request("GET", "/parity", emptyMap(), null)
        val nullResponse = MuxSession(nullDuplex, null).request("GET", "/parity", emptyMap(), null)

        assertEquals(defaultResponse.status, nullResponse.status)
        assertEquals(defaultResponse.headers, nullResponse.headers)
        assertContentEquals(defaultResponse.body, nullResponse.body)
        assertContentEquals(defaultDuplex.written(), nullDuplex.written())
    }

    private fun scriptedDuplex(vararg reads: ByteArray): ScriptedDuplex =
        ScriptedDuplex(ByteArrayInputStream(reads.fold(ByteArray(0)) { acc, bytes -> acc + bytes }))

    private class ScriptedDuplex(inputBytes: ByteArrayInputStream) : ByteDuplex {
        private val written = ByteArrayOutputStream()
        override val input = inputBytes
        override val output = written

        fun written(): ByteArray = written.toByteArray()

        override fun close() {
            input.close()
            output.close()
        }
    }
}
