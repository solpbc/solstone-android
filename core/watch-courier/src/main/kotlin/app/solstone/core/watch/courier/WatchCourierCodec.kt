// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class CourierFilePayload(
    val name: String,
    val mediaType: String,
    val sha256: String,
    val byteSize: Long,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CourierFilePayload) return false
        return name == other.name && mediaType == other.mediaType &&
            sha256 == other.sha256 && byteSize == other.byteSize &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + sha256.hashCode()
        result = 31 * result + byteSize.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

data class CourierBundleHeader(
    val protocolVersion: Int,
    val bundleId: String,
    val day: String,
    val segment: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val zoneId: String,
    val utcOffsetSeconds: Int,
    val sealGeneration: Long,
)

data class DecodedBundle(
    val header: CourierBundleHeader,
    val bundleSha256: String,
    val files: List<CourierFilePayload>,
)

data class WatchBundleReceipt(
    val protocolVersion: Int,
    val bundleId: String,
    val sha256: String,
    val sealGeneration: Long,
    val segmentId: String,
    val committedAt: Long,
)

sealed interface CodecResult<out T> {
    data class Success<T>(val value: T) : CodecResult<T>
    data class VersionMismatch(val version: Int) : CodecResult<Nothing>
    data class Malformed(val reason: String) : CodecResult<Nothing>
}

object WatchCourierCodec {
    private const val BUNDLE_MAGIC = 0x534F4C57 // "SOLW"
    private const val MAX_BUNDLE_HEADER_BYTES = 4096
    private const val MAX_FILE_SIZE_BYTES = 16L * 1024L * 1024L // 16 MiB
    private const val MAX_FILE_COUNT = 2

    fun encodeBundle(
        header: CourierBundleHeader,
        files: List<CourierFilePayload>,
        output: OutputStream,
    ) {
        require(files.isNotEmpty() && files.size <= MAX_FILE_COUNT) {
            "Files count must be between 1 and $MAX_FILE_COUNT"
        }
        val dos = DataOutputStream(output)
        dos.writeInt(BUNDLE_MAGIC)
        dos.writeInt(header.protocolVersion)
        dos.writeUTF(header.bundleId)
        dos.writeUTF(header.day)
        dos.writeUTF(header.segment)
        dos.writeLong(header.startEpochMs)
        dos.writeLong(header.endEpochMs)
        dos.writeUTF(header.zoneId)
        dos.writeInt(header.utcOffsetSeconds)
        dos.writeLong(header.sealGeneration)
        dos.writeInt(files.size)

        for (file in files) {
            require(file.name == WatchCourierProtocol.FILE_NAME_AUDIO || file.name == WatchCourierProtocol.FILE_NAME_LOCATION) {
                "Disallowed file name: ${file.name}"
            }
            require(file.bytes.size.toLong() == file.byteSize) { "Declared size mismatch for ${file.name}" }
            require(file.byteSize <= MAX_FILE_SIZE_BYTES) { "File exceeds maximum size: ${file.byteSize}" }
            dos.writeUTF(file.name)
            dos.writeUTF(file.mediaType)
            dos.writeUTF(file.sha256)
            dos.writeLong(file.byteSize)
            dos.write(file.bytes)
        }
        dos.flush()
    }

    fun decodeBundle(input: InputStream): CodecResult<DecodedBundle> = runCatching {
        val dis = DataInputStream(input)
        val magic = dis.readInt()
        if (magic != BUNDLE_MAGIC) {
            return CodecResult.Malformed("Invalid bundle magic: $magic")
        }
        val version = dis.readInt()
        if (version != WatchCourierProtocol.PROTOCOL_VERSION) {
            return CodecResult.VersionMismatch(version)
        }
        val bundleId = dis.readUTF()
        if (bundleId.isBlank()) return CodecResult.Malformed("Blank bundle ID")
        val day = dis.readUTF()
        val segment = dis.readUTF()
        val startEpochMs = dis.readLong()
        val endEpochMs = dis.readLong()
        val zoneId = dis.readUTF()
        val utcOffsetSeconds = dis.readInt()
        val sealGeneration = dis.readLong()

        val header = CourierBundleHeader(
            protocolVersion = version,
            bundleId = bundleId,
            day = day,
            segment = segment,
            startEpochMs = startEpochMs,
            endEpochMs = endEpochMs,
            zoneId = zoneId,
            utcOffsetSeconds = utcOffsetSeconds,
            sealGeneration = sealGeneration,
        )

        val fileCount = dis.readInt()
        if (fileCount <= 0 || fileCount > MAX_FILE_COUNT) {
            return CodecResult.Malformed("Invalid file count: $fileCount")
        }

        val files = mutableListOf<CourierFilePayload>()
        val digest = MessageDigest.getInstance("SHA-256")
        val fileNames = mutableSetOf<String>()

        for (i in 0 until fileCount) {
            val name = dis.readUTF()
            if (name != WatchCourierProtocol.FILE_NAME_AUDIO && name != WatchCourierProtocol.FILE_NAME_LOCATION) {
                return CodecResult.Malformed("Disallowed file name: $name")
            }
            if (!fileNames.add(name)) {
                return CodecResult.Malformed("Duplicate file name: $name")
            }
            val mediaType = dis.readUTF()
            val declaredSha = dis.readUTF()
            val size = dis.readLong()
            if (size < 0 || size > MAX_FILE_SIZE_BYTES) {
                return CodecResult.Malformed("File size out of bounds: $size")
            }
            val bytes = ByteArray(size.toInt())
            dis.readFully(bytes)
            val fileDigest = sha256Hex(bytes)
            if (!fileDigest.equals(declaredSha, ignoreCase = true)) {
                return CodecResult.Malformed("File hash mismatch for $name")
            }
            digest.update(bytes)
            files += CourierFilePayload(
                name = name,
                mediaType = mediaType,
                sha256 = fileDigest,
                byteSize = size,
                bytes = bytes,
            )
        }

        val bundleSha = digest.digest().joinToString("") { "%02x".format(it) }
        CodecResult.Success(DecodedBundle(header, bundleSha, files))
    }.getOrElse { e ->
        CodecResult.Malformed("Failed to parse bundle: ${e.message}")
    }

    fun encodeReceipt(receipt: WatchBundleReceipt): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(WatchCourierProtocol.KIND_RECEIPT)
        dos.writeInt(receipt.protocolVersion)
        dos.writeUTF(receipt.bundleId)
        dos.writeUTF(receipt.sha256)
        dos.writeLong(receipt.sealGeneration)
        dos.writeUTF(receipt.segmentId)
        dos.writeLong(receipt.committedAt)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeReceipt(bytes: ByteArray): CodecResult<WatchBundleReceipt> = runCatching {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val kind = dis.readInt()
        if (kind != WatchCourierProtocol.KIND_RECEIPT) {
            return CodecResult.Malformed("Unexpected message kind: $kind")
        }
        val version = dis.readInt()
        if (version != WatchCourierProtocol.PROTOCOL_VERSION) {
            return CodecResult.VersionMismatch(version)
        }
        val bundleId = dis.readUTF()
        val sha256 = dis.readUTF()
        val sealGeneration = dis.readLong()
        val segmentId = dis.readUTF()
        val committedAt = dis.readLong()
        CodecResult.Success(
            WatchBundleReceipt(
                protocolVersion = version,
                bundleId = bundleId,
                sha256 = sha256,
                sealGeneration = sealGeneration,
                segmentId = segmentId,
                committedAt = committedAt,
            ),
        )
    }.getOrElse { e ->
        CodecResult.Malformed("Receipt decode failed: ${e.message}")
    }

    fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
