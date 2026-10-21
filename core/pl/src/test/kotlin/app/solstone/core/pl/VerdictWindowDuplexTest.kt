// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.util.ArrayDeque
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VerdictWindowDuplexTest {
    // How each platform reports a received alert. Falsified by narrowing to known codes (80, 200 and
    // the record-layer 10/20/22 would read as no alert) or by reading 49 and 46 the same way.
    @Test
    fun receivedAlertsAreReadFromBothStacks() {
        val cases = mapOf(
            boringssl(0x10000419, "TLSV1_ALERT_ACCESS_DENIED") to 49,
            boringssl(0x10000416, "SSLV3_ALERT_CERTIFICATE_UNKNOWN") to 46,
            boringssl(0x10000438, "TLSV1_ALERT_INTERNAL_ERROR") to 80,
            boringssl(0x100004b0, "reason(1200)") to 200,
            boringssl(0x100003fc, "SSLV3_ALERT_BAD_RECORD_MAC") to 20,
            SSLException("Read error: SSL alert number 22") to 22,
            SSLHandshakeException("(access_denied) Received fatal alert: access_denied") to 49,
            SSLException("Received fatal alert: certificate_unknown") to 46,
            SSLException("Received fatal alert: unexpected_message") to 10,
            SSLException("(unexpected_message) Unknown alert description (-56)") to 200,
        )
        for ((error, code) in cases) {
            assertEquals(ReceivedTlsAlert(code), receivedTlsAlert(IOException("wrapped", error)), "${error.message}")
        }
        assertEquals(HandshakeFailure.TLS_ACCESS_DENIED, refusalForAlert(ReceivedTlsAlert(49)))
        assertEquals(HandshakeFailure.TLS_CERTIFICATE_UNKNOWN, refusalForAlert(ReceivedTlsAlert(46)))
        for (code in listOf(10, 20, 22, 42, 48, 80, 200)) {
            assertEquals(HandshakeFailure.TLS_REFUSED, refusalForAlert(ReceivedTlsAlert(code)))
        }
        // A peer alert the platform names without a usable code is still a counted refusal.
        assertEquals(HandshakeFailure.TLS_REFUSED, refusalForAlert(ReceivedTlsAlert(null)))
    }

    // Falsified by treating any TLS exception as a refusal (the old classifier): a reset, a clean
    // close, a locally detected record error or a relay close reason would all count.
    @Test
    fun nothingButAReceivedAlertIsOne() {
        for (error in listOf(
            SSLException("Read error: ssl=0x7a: I/O error during system call, Connection reset by peer"),
            boringssl(0x10000119, "DECRYPTION_FAILED_OR_BAD_RECORD_MAC"),
            SSLException("TLS application buffer overflow during unwrap"),
            SSLException("Tag mismatch"),
            SSLException("Received close_notify during handshake"),
            SocketException("Connection reset"),
            EOFException("TLS stream closed during unwrap"),
            IOException("relay websocket closed: 4401 Received fatal alert: access_denied"),
        )) {
            assertNull(receivedTlsAlert(error), "${error.message}")
        }
    }

    // Falsified by a window that never closes: an alert after the journal answered would be named
    // a refusal of this device.
    @Test
    fun anAlertAfterTheJournalsFirstByteIsNotCounted() {
        val inner = ScriptedDuplex(reads = listOf(Read.Bytes(byteArrayOf(1, 2, 3)), Read.Fail(alert(80))))
        val heard = Heard()
        val window = VerdictWindowDuplex(inner, heard)
        assertEquals(3, window.input.read(ByteArray(8)))
        val after = assertFailsWith<SSLException> { window.input.read(ByteArray(8)) }
        assertNull(journalRefusalIn(after))
        assertEquals(listOf("accepted"), heard.events)
    }

    @Test
    fun anAlertBeforeTheFirstByteIsTheVerdictAndIsToldOnce() {
        val inner = ScriptedDuplex(reads = listOf(Read.Fail(alert(49))))
        val heard = Heard()
        val window = VerdictWindowDuplex(inner, heard)
        val refusal = assertFailsWith<JournalRefusalException> { window.input.read(ByteArray(8)) }
        assertEquals(HandshakeFailure.TLS_ACCESS_DENIED, refusal.failure)
        assertEquals(49, refusal.alertCode)
        // Every later use reports the same verdict, and the listener heard it once.
        assertSame(refusal, assertFailsWith<JournalRefusalException> { window.output.write(ByteArray(1)) })
        assertEquals(listOf("refused TLS_ACCESS_DENIED"), heard.events)
    }

    // A write failure followed by a pending alert gives the alert. Falsified by settling the
    // attempt on the write: the journal's refusal waiting behind it would never count.
    @Test
    fun aWriteFailureReadsOnceMoreForAPendingAlert() {
        val inner = ScriptedDuplex(reads = listOf(Read.Fail(alert(80))), writeFailure = SocketException("Broken pipe"))
        val heard = Heard()
        val window = VerdictWindowDuplex(inner, heard)
        val refusal = assertFailsWith<JournalRefusalException> { window.output.write(ByteArray(16)) }
        assertEquals(HandshakeFailure.TLS_REFUSED, refusal.failure)
        assertEquals(listOf("refused TLS_REFUSED"), heard.events)
    }

    // The verdict read is bounded. Falsified by an unbounded wait, which never returns here.
    @Test
    fun aWriteFailureWithNoVerdictReportsTheWriteAfterTheBoundedRead() {
        val writeFailure = SocketException("Broken pipe")
        val inner = ScriptedDuplex(reads = listOf(Read.Block), writeFailure = writeFailure)
        val window = VerdictWindowDuplex(inner, Heard(), verdictReadMillis = 200)
        val started = System.nanoTime()
        assertSame(writeFailure, assertFailsWith<SocketException> { window.output.write(ByteArray(16)) })
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
    }

    // Seven non-alert failures after the handshake (reset, EOF, close_notify, a local buffer error)
    // at the spacing never count and never stop. Falsified by a broad TLS-exception classifier.
    @Test
    fun nonAlertFailuresAfterTheHandshakeNeverCount() {
        var now = 0L
        val tracker = HandshakeRefusalTracker(Records(), { now }, { now })
        val failures = listOf<Read>(
            Read.Fail(SSLException("Read error: ssl=0x7a: I/O error during system call, Connection reset by peer")),
            Read.Eof,
            Read.Fail(SocketException("Connection reset")),
            Read.Eof,
            Read.Fail(SSLException("TLS application buffer overflow during unwrap")),
            Read.Fail(boringssl(0x10000119, "DECRYPTION_FAILED_OR_BAD_RECORD_MAC")),
            Read.Fail(SSLException("Tag mismatch")),
        )
        for (failure in failures) {
            val heard = Heard()
            val window = VerdictWindowDuplex(ScriptedDuplex(reads = listOf(failure)), heard)
            val outcome = runCatching { window.input.read(ByteArray(8)) }
            val classified = outcome.exceptionOrNull()?.let(::handshakeFailureOf) ?: HandshakeFailure.UNREACHABLE
            assertEquals(HandshakeFailure.UNREACHABLE, classified)
            assertTrue(heard.events.isEmpty())
            assertNull(tracker.failed("sha256:client", classified))
            now += REFUSAL_SPACING_MILLIS
        }
        assertEquals(0, tracker.current("sha256:client")?.refusals ?: 0)
        assertNull(tracker.stop("sha256:client"))
    }

    // Falsified by keying the classification on any exception's message: only the transport's
    // typed findings are refusals or unknown journals.
    @Test
    fun onlyTypedFindingsClassifyAsRefusalOrUnknownJournal() {
        assertEquals(HandshakeFailure.UNREACHABLE, handshakeFailureOf(SSLHandshakeException("Received fatal alert: access_denied")))
        assertEquals(HandshakeFailure.UNREACHABLE, handshakeFailureOf(SSLException("PKIX path validation failed")))
        assertEquals(
            HandshakeFailure.TLS_REFUSED,
            handshakeFailureOf(IOException("wrapped", JournalRefusalException(HandshakeFailure.TLS_REFUSED, 80))),
        )
        assertEquals(HandshakeFailure.UNKNOWN_JOURNAL, handshakeFailureOf(UnknownJournalException("10.0.0.5:7657", null)))
        assertIs<JournalRefusalException>(journalRefusalIn(IOException(JournalRefusalException(HandshakeFailure.TLS_ACCESS_DENIED, 49))))
    }

    private fun boringssl(packed: Long, reason: String) = SSLException(
        "Read error: ssl=0x7b2c: Failure in SSL library, usually a protocol error\n" +
            "error:%08x:SSL routines:OPENSSL_internal:%s (external/boringssl/src/ssl/tls_record.cc:592 0x7b2c:0x00000001)".format(packed, reason),
    )

    private fun alert(code: Int): SSLException = when (code) {
        49 -> boringssl(0x10000419, "TLSV1_ALERT_ACCESS_DENIED")
        80 -> boringssl(0x10000438, "TLSV1_ALERT_INTERNAL_ERROR")
        else -> boringssl(0x10000000L + 1000 + code, "reason(${1000 + code})")
    }

    private class Heard : JournalVerdictListener {
        val events = mutableListOf<String>()
        override fun onAccepted() {
            events += "accepted"
        }

        override fun onRefused(refusal: JournalRefusalException) {
            events += "refused ${refusal.failure}"
        }
    }

    private sealed interface Read {
        data class Bytes(val bytes: ByteArray) : Read
        data class Fail(val error: IOException) : Read
        data object Eof : Read
        data object Block : Read
    }

    private class ScriptedDuplex(reads: List<Read>, private val writeFailure: IOException? = null) : ByteDuplex {
        private val script = ArrayDeque(reads)

        override val input: InputStream = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                return when (val next = synchronized(script) { script.pollFirst() } ?: Read.Eof) {
                    is Read.Bytes -> {
                        next.bytes.copyInto(b, off)
                        next.bytes.size
                    }
                    is Read.Fail -> throw next.error
                    Read.Eof -> -1
                    Read.Block -> {
                        Thread.sleep(60_000)
                        -1
                    }
                }
            }
        }

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                writeFailure?.let { throw it }
            }
        }

        override fun close() = Unit
    }

    private class Records : RefusalRecordStore {
        private var record: RefusalRecord? = null
        override fun read(): RefusalRecordRead = record?.let { RefusalRecordRead.Present(it) } ?: RefusalRecordRead.Absent
        override fun write(record: RefusalRecord) {
            this.record = record
        }
    }
}
