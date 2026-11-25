// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.observer.MobileImportSource
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

private const val HASH_BUFFER_BYTES = 64 * 1024
private val HEX_CHARS = "0123456789abcdef".toCharArray()

internal fun bytesToLowerHex(bytes: ByteArray): String {
    val out = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xFF
        out[i * 2] = HEX_CHARS[v ushr 4]
        out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
    }
    return String(out)
}

/**
 * Stream the whole file at [path] in 64 KiB chunks to compute its lowercase hex SHA-256 without
 * loading the file into memory. Returns null if [shouldStop] becomes true before EOF.
 * Throws [IOException] if the local file cannot be opened or read.
 */
internal fun sha256FileHex(
    path: Path,
    shouldStop: () -> Boolean = { false },
): String? {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(HASH_BUFFER_BYTES)
    val byteBuffer = ByteBuffer.wrap(buffer)
    FileChannel.open(path, StandardOpenOption.READ).use { channel ->
        while (true) {
            if (shouldStop()) return null
            byteBuffer.clear()
            val n = channel.read(byteBuffer)
            if (n < 0) break
            if (n > 0) digest.update(buffer, 0, n)
        }
    }
    return bytesToLowerHex(digest.digest())
}

/**
 * Stream exact `[offset, offset + length)` from [path] in 64 KiB chunks to compute its lowercase
 * hex SHA-256. Returns null if [shouldStop] becomes true before completion. Throws [IOException]
 * if the file is unreadable or shorter than `offset + length`.
 */
internal fun sha256SliceHex(
    path: Path,
    offset: Long,
    length: Long,
    shouldStop: () -> Boolean = { false },
): String? {
    require(offset >= 0L && length >= 0L) { "offset ($offset) and length ($length) must be non-negative" }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(HASH_BUFFER_BYTES)
    FileChannel.open(path, StandardOpenOption.READ).use { channel ->
        channel.position(offset)
        var remaining = length
        while (remaining > 0L) {
            if (shouldStop()) return null
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val byteBuffer = ByteBuffer.wrap(buffer, 0, want)
            val n = channel.read(byteBuffer)
            if (n < 0) {
                throw EOFException("Unexpected EOF hashing slice at offset=$offset remaining=$remaining")
            }
            if (n > 0) {
                digest.update(buffer, 0, n)
                remaining -= n
            }
        }
    }
    return bytesToLowerHex(digest.digest())
}

/**
 * O(1)-memory [MobileImportSource] backed by a [FileChannel] slice `[offset, offset + length)`.
 * Records [localReadFailure] separately from transport exceptions so delivery can distinguish
 * an unreadable custody copy from a network interruption, and stops promptly when [shouldStop]
 * returns true.
 */
internal class FileChannelSliceSource(
    private val path: Path,
    private val offset: Long,
    private val length: Long,
    private val shouldStop: () -> Boolean = { false },
) : MobileImportSource {
    private var channel: FileChannel? = null
    private var remaining: Long = length
    private var closed: Boolean = false

    @Volatile
    var localReadFailure: IOException? = null
        private set

    @Volatile
    var stoppedEarly: Boolean = false
        private set

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Slice source closed")
        if (shouldStop()) {
            stoppedEarly = true
            throw IOException("Upload stopped")
        }
        if (remaining <= 0L) return -1
        if (length <= 0) return 0
        val ch = try {
            channel ?: FileChannel.open(path, StandardOpenOption.READ).also {
                it.position(this.offset)
                channel = it
            }
        } catch (e: IOException) {
            localReadFailure = e
            throw e
        }
        val want = minOf(remaining, length.toLong()).toInt()
        val buf = ByteBuffer.wrap(target, offset, want)
        val n = try {
            ch.read(buf)
        } catch (e: IOException) {
            localReadFailure = e
            throw e
        }
        if (n < 0) {
            val eof = EOFException("Unexpected EOF streaming slice at offset=${this.offset} remaining=$remaining")
            localReadFailure = eof
            throw eof
        }
        remaining -= n
        return n
    }

    override fun close() {
        if (!closed) {
            closed = true
            runCatching { channel?.close() }
            channel = null
        }
    }
}

/**
 * Best-effort local cache of the last journal-advertised resumable `threshold_bytes` for one
 * journal instance. Used solely as a scheduling hint for choosing the API 34+ user-initiated
 * data transfer job before a connection is open; the upload protocol path always reads live
 * thresholds and chunk sizes from the journal.
 */
class ResumableThresholdHintStore(private val root: File) {
    private val hintFile: File get() = File(root, ".resumable-threshold-hint.tsv")

    fun record(instanceId: String, thresholdBytes: Long) {
        if (instanceId.isBlank() || thresholdBytes <= 0L) return
        synchronized(this) {
            runCatching {
                root.mkdirs()
                val tmp = File(root, ".resumable-threshold-hint.tmp")
                tmp.writeText("$instanceId\t$thresholdBytes\n", Charsets.UTF_8)
                Files.move(
                    tmp.toPath(),
                    hintFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.onFailure {
                runCatching {
                    root.mkdirs()
                    hintFile.writeText("$instanceId\t$thresholdBytes\n", Charsets.UTF_8)
                }
            }
        }
    }

    fun readFor(instanceId: String?): Long? {
        if (instanceId.isNullOrBlank()) return null
        val file = hintFile
        if (!file.isFile) return null
        return runCatching {
            val parts = file.readText(Charsets.UTF_8).trim().split('\t')
            if (parts.size == 2 && parts[0] == instanceId) {
                parts[1].toLongOrNull()?.takeIf { it > 0L }
            } else {
                null
            }
        }.getOrNull()
    }
}
