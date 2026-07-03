// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.ByteDuplex
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

class TlsEngineDuplex(
    private val engine: SSLEngine,
    private val raw: ByteDuplex,
) : ByteDuplex {
    private val outboundLock = Any()
    private var netIn = ByteBuffer.allocate(engine.session.packetBufferSize).apply { limit(0) }
    private var appIn = ByteBuffer.allocate(engine.session.applicationBufferSize).apply { limit(0) }
    private var closed = false

    override val input: InputStream = TlsInputStream()
    override val output: OutputStream = TlsOutputStream()
    val peerLeafCertificateDer: ByteArray?
    val peerCertificateChainDer: List<ByteArray>?

    init {
        handshake()
        val chain = try {
            engine.session.peerCertificates
        } catch (_: SSLPeerUnverifiedException) {
            null
        }
        peerLeafCertificateDer = chain?.firstOrNull()?.encoded
        peerCertificateChainDer = chain?.map { it.encoded }
    }

    private fun handshake() {
        engine.beginHandshake()
        var status = engine.handshakeStatus
        while (true) {
            status = when (status) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val result = wrapBytes(ByteBuffer.allocate(0))
                    result.handshakeStatus
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val handshakeApp = ByteBuffer.allocate(engine.session.applicationBufferSize)
                    val result = unwrapInto(handshakeApp)
                    handshakeApp.flip()
                    if (handshakeApp.hasRemaining()) {
                        appIn = copyRemaining(handshakeApp)
                    }
                    result.handshakeStatus
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks()
                    engine.handshakeStatus
                }
                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
                -> return
            }
        }
    }

    private fun unwrapInto(destination: ByteBuffer): SSLEngineResult {
        while (true) {
            val result = engine.unwrap(netIn, destination)
            when (result.status) {
                SSLEngineResult.Status.OK -> return result
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    throw SSLException("TLS application buffer overflow during unwrap")
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    if (!readMoreNetworkBytes()) {
                        throw EOFException("TLS stream closed during unwrap")
                    }
                    netIn = ensurePacketCapacity(netIn)
                }
                SSLEngineResult.Status.CLOSED -> return result
            }
            if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                runDelegatedTasks()
            }
        }
    }

    private fun readMoreNetworkBytes(): Boolean {
        netIn.compact()
        if (!netIn.hasRemaining()) {
            netIn = growForWrite(netIn)
        }
        val read = raw.input.read(netIn.array(), netIn.position(), netIn.remaining())
        if (read < 0) {
            netIn.flip()
            return false
        }
        netIn.position(netIn.position() + read)
        netIn.flip()
        return true
    }

    private fun wrapBytes(source: ByteBuffer): SSLEngineResult {
        synchronized(outboundLock) {
            var netOut = ByteBuffer.allocate(engine.session.packetBufferSize)
            while (true) {
                netOut.clear()
                val result = engine.wrap(source, netOut)
                if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    runDelegatedTasks()
                }
                when (result.status) {
                    SSLEngineResult.Status.OK,
                    SSLEngineResult.Status.CLOSED,
                    -> {
                        writeFlipped(netOut)
                        return result
                    }
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                    }
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                        throw SSLException("TLS underflow during wrap")
                    }
                }
            }
        }
    }

    private fun writeFlipped(buffer: ByteBuffer) {
        buffer.flip()
        if (buffer.hasRemaining()) {
            raw.output.write(buffer.array(), buffer.position(), buffer.remaining())
            raw.output.flush()
        }
    }

    private fun runDelegatedTasks() {
        while (true) {
            val task = engine.delegatedTask ?: return
            task.run()
        }
    }

    private fun ensurePacketCapacity(buffer: ByteBuffer): ByteBuffer {
        val needed = engine.session.packetBufferSize
        return if (buffer.capacity() >= needed) buffer else copyReadable(buffer, needed)
    }

    private fun growForWrite(buffer: ByteBuffer): ByteBuffer {
        buffer.flip()
        val out = ByteBuffer.allocate(buffer.capacity() * 2)
        out.put(buffer)
        return out
    }

    private fun copyReadable(source: ByteBuffer, capacity: Int): ByteBuffer {
        val out = ByteBuffer.allocate(capacity)
        out.put(source)
        out.flip()
        return out
    }

    private fun copyRemaining(source: ByteBuffer): ByteBuffer {
        val out = ByteBuffer.allocate(maxOf(engine.session.applicationBufferSize, source.remaining()))
        out.put(source)
        out.flip()
        return out
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        try {
            engine.closeOutbound()
            synchronized(outboundLock) {
                val empty = ByteBuffer.allocate(0)
                var netOut = ByteBuffer.allocate(engine.session.packetBufferSize)
                while (!engine.isOutboundDone) {
                    netOut.clear()
                    val result = engine.wrap(empty, netOut)
                    if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                        runDelegatedTasks()
                    }
                    if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                        continue
                    }
                    writeFlipped(netOut)
                    if (result.bytesProduced() == 0) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            raw.close()
        }
    }

    private inner class TlsOutputStream : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) {
                throw IOException("TLS duplex is closed")
            }
            val source = ByteBuffer.wrap(b, off, len)
            while (source.hasRemaining()) {
                val before = source.position()
                val result = wrapBytes(source)
                if (result.status == SSLEngineResult.Status.CLOSED) {
                    throw IOException("TLS engine closed during write")
                }
                if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    runDelegatedTasks()
                }
                if (source.position() == before && result.bytesProduced() == 0) {
                    throw IOException("TLS engine made no write progress")
                }
            }
        }

        override fun flush() {
            raw.output.flush()
        }
    }

    private inner class TlsInputStream : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val read = read(one, 0, 1)
            return if (read < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) {
                return 0
            }
            while (!appIn.hasRemaining()) {
                if (engine.isInboundDone) {
                    return -1
                }
                appIn.clear()
                val result = try {
                    unwrapInto(appIn)
                } catch (_: EOFException) {
                    return -1
                }
                appIn.flip()
                if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    runDelegatedTasks()
                }
                if (result.status == SSLEngineResult.Status.CLOSED) {
                    return -1
                }
                if (!appIn.hasRemaining() && result.bytesConsumed() == 0 && result.bytesProduced() == 0) {
                    if (!readMoreNetworkBytes()) {
                        return -1
                    }
                }
            }
            val count = minOf(len, appIn.remaining())
            appIn.get(b, off, count)
            return count
        }
    }
}
