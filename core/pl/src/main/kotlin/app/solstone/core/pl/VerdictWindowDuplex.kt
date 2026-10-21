// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The journal's verdict window over one authenticated TLS connection.
 *
 * Build it only once this client has checked the journal's certificate against the pinned CA and
 * finished its side of the TLS 1.3 handshake: that is when the window opens. In TLS 1.3 the journal
 * checks this device's certificate after that, so its refusal arrives as a fatal alert on the first
 * read. The window closes at the journal's first application byte, which is its acceptance.
 *
 * Inside the window a received fatal alert is rethrown as a [JournalRefusalException] and reported
 * to [listener]; nothing else is a refusal. After the window closes nothing is classified.
 *
 * A journal that refuses also closes, so a write can fail before its verdict has been read. A write
 * failure inside the window therefore reads once more, for up to [verdictReadMillis], so a refusal
 * that raced the write is still seen.
 */
class VerdictWindowDuplex(
    private val inner: ByteDuplex,
    private val listener: JournalVerdictListener?,
    private val verdictReadMillis: Long = VERDICT_READ_MILLIS,
) : ByteDuplex {
    private val lock = Any()

    @Volatile
    private var settled = false

    @Volatile
    private var refusal: JournalRefusalException? = null

    override val input: InputStream = WindowInput()
    override val output: OutputStream = WindowOutput()

    /** Whether the journal has sent application data on this connection. */
    val accepted: Boolean
        get() = settled && refusal == null

    override fun close() {
        inner.close()
    }

    private fun accept() {
        val first = synchronized(lock) {
            if (settled) false else {
                settled = true
                true
            }
        }
        if (first) notify { it.onAccepted() }
    }

    /** Classify a failure seen while the window is open; returns what to throw. */
    private fun classify(failure: IOException): IOException {
        val existing = refusal
        if (existing != null) return existing
        if (settled) return failure
        val alert = receivedTlsAlert(failure) ?: return failure
        val verdict = refusalForAlert(alert)
        if (verdict == HandshakeFailure.UNREACHABLE) return failure
        val classified = JournalRefusalException(verdict, alert.code, failure)
        val first = synchronized(lock) {
            if (settled) false else {
                settled = true
                refusal = classified
                true
            }
        }
        if (!first) return refusal ?: failure
        notify { it.onRefused(classified) }
        return classified
    }

    private fun notify(block: (JournalVerdictListener) -> Unit) {
        val current = listener ?: return
        try {
            block(current)
        } catch (_: Throwable) {
            // Bookkeeping must never change what the connection reports.
        }
    }

    /** After a failed write inside the window, read once, briefly, for a verdict already sent. */
    private fun verdictAfterWriteFailure(writeFailure: IOException): IOException {
        if (settled) return refusal ?: writeFailure
        val probe = FutureTask<IOException?> {
            try {
                val n = inner.input.read(ByteArray(1))
                if (n > 0) accept()
                null
            } catch (e: IOException) {
                e
            }
        }
        val thread = Thread(probe, "verdict-read").apply { isDaemon = true }
        thread.start()
        val readFailure = try {
            probe.get(verdictReadMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
        if (readFailure != null) {
            val classified = classify(readFailure)
            if (classified is JournalRefusalException) return classified
        }
        return writeFailure
    }

    private inner class WindowInput : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            refusal?.let { throw it }
            val n = try {
                inner.input.read(b, off, len)
            } catch (e: IOException) {
                throw classify(e)
            }
            if (n > 0) accept()
            return n
        }
    }

    private inner class WindowOutput : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            refusal?.let { throw it }
            try {
                inner.output.write(b, off, len)
            } catch (e: IOException) {
                val direct = classify(e)
                if (direct is JournalRefusalException) throw direct
                throw verdictAfterWriteFailure(e)
            }
        }

        override fun flush() {
            refusal?.let { throw it }
            try {
                inner.output.flush()
            } catch (e: IOException) {
                val direct = classify(e)
                if (direct is JournalRefusalException) throw direct
                throw verdictAfterWriteFailure(e)
            }
        }
    }

    companion object {
        /** How long a failed write waits for the journal's verdict. */
        const val VERDICT_READ_MILLIS: Long = 1_000L
    }
}
