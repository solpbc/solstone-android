// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.PayloadRef
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSpoolWriterTest {
    @Test
    fun sealsDstTwinsWithBareAndSuffixedDirLeaves() {
        withTempDir { baseDir ->
            val first = segment(startEpochMs = FIRST_START, payloadName = "first.bin")
            val second = segment(startEpochMs = SECOND_START, payloadName = "second.bin")
            val provider = provider(
                "first.bin" to "first-bytes".toByteArray(),
                "second.bin" to "second-bytes".toByteArray(),
            )
            val writer = FileSpoolWriter(baseDir)

            val firstResult = writer.seal(first, provider)
            val secondResult = writer.seal(second, provider)

            val firstDir = baseDir.resolve(DAY).resolve(STREAM).resolve(WIRE_SEGMENT)
            val secondLeaf = "${WIRE_SEGMENT}__ws$SECOND_START"
            val secondDir = baseDir.resolve(DAY).resolve(STREAM).resolve(secondLeaf)
            assertEquals(firstDir, firstResult.directory)
            assertEquals(secondDir, secondResult.directory)
            assertContentEquals("first-bytes".toByteArray(), Files.readAllBytes(firstDir.resolve("first.bin")))
            assertContentEquals("second-bytes".toByteArray(), Files.readAllBytes(secondDir.resolve("second.bin")))
            assertTrue(String(Files.readAllBytes(firstDir.resolve("manifest")), StandardCharsets.UTF_8).contains("segment=$WIRE_SEGMENT\n"))
            assertTrue(String(Files.readAllBytes(secondDir.resolve("manifest")), StandardCharsets.UTF_8).contains("segment=$WIRE_SEGMENT\n"))
        }
    }

    @Test
    fun resealSameSegmentReturnsExistingFinalWithoutDeleting() {
        withTempDir { baseDir ->
            val segment = segment(startEpochMs = FIRST_START, payloadName = "first.bin")
            val writer = FileSpoolWriter(baseDir)
            val firstResult = writer.seal(segment, provider("first.bin" to "first-bytes".toByteArray()))
            val finalDir = firstResult.directory ?: error("missing final dir")
            Files.write(finalDir.resolve("sentinel"), "keep".toByteArray())

            val secondResult = writer.seal(
                segment,
                object : PayloadBytesProvider {
                    override fun open(payload: SegmentPayload) =
                        error("idempotent reseal must not reopen payload bytes")
                },
            )

            assertEquals(finalDir, secondResult.directory)
            assertEquals("keep", String(Files.readAllBytes(finalDir.resolve("sentinel")), StandardCharsets.UTF_8))
            assertContentEquals("first-bytes".toByteArray(), Files.readAllBytes(finalDir.resolve("first.bin")))
        }
    }

    @Test
    fun sealFsyncsPayloadsAndManifestBeforeMove() {
        withTempDir { baseDir ->
            val segment = segment(startEpochMs = FIRST_START, payloadName = "first.bin")
            val finalDir = baseDir.resolve(DAY).resolve(STREAM).resolve(WIRE_SEGMENT)
            val synced = mutableListOf<Path>()
            val writer = FileSpoolWriter(baseDir) { path ->
                assertFalse(Files.exists(finalDir), "final dir must not exist before fsync completes")
                synced.add(path)
            }

            writer.seal(segment, provider("first.bin" to "first-bytes".toByteArray()))

            assertEquals(
                listOf(
                    baseDir.resolve(".draft").resolve(DAY).resolve(STREAM).resolve(WIRE_SEGMENT).resolve("first.bin"),
                    baseDir.resolve(".draft").resolve(DAY).resolve(STREAM).resolve(WIRE_SEGMENT).resolve("manifest"),
                ),
                synced,
            )
        }
    }

    @Test
    fun rejectsPayloadNamePathTraversal() {
        withTempDir { baseDir ->
            val segment = segment(startEpochMs = FIRST_START, payloadName = "../escape.bin")

            assertFailsWith<IllegalArgumentException> {
                FileSpoolWriter(baseDir).seal(segment, provider("../escape.bin" to "bytes".toByteArray()))
            }
        }
    }

    @Test
    fun cleansDraftDirectoryAfterSeal() {
        withTempDir { baseDir ->
            val segment = segment(startEpochMs = FIRST_START, payloadName = "first.bin")

            FileSpoolWriter(baseDir).seal(segment, provider("first.bin" to "first-bytes".toByteArray()))

            assertFalse(Files.exists(baseDir.resolve(".draft")))
        }
    }

    private fun segment(startEpochMs: Long, payloadName: String): SealedSegment =
        SealedSegment(
            stream = STREAM,
            key = SegmentKey(DAY, WIRE_SEGMENT),
            wireKeys = WireKeys(
                day = DAY,
                segment = WIRE_SEGMENT,
                startEpochMs = startEpochMs,
                endEpochMs = startEpochMs + 300_000L,
                zoneId = "America/New_York",
                utcOffsetSeconds = if (startEpochMs == FIRST_START) -14_400 else -18_000,
            ),
            payloads = listOf(
                SegmentPayload(
                    sourceId = "source",
                    ref = PayloadRef(payloadName, "application/octet-stream", payloadName.length.toLong(), null),
                    captureStartEpochMs = startEpochMs,
                    captureEndEpochMs = startEpochMs + 300_000L,
                ),
            ),
            gaps = emptyList(),
        )

    private fun provider(vararg entries: Pair<String, ByteArray>): PayloadBytesProvider {
        val bytesByName = entries.toMap()
        return object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload): ByteArrayInputStream =
                ByteArrayInputStream(bytesByName.getValue(payload.ref.name))
        }
    }

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("solstone-file-spool-writer")
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun Path.deleteRecursively() {
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private companion object {
        const val DAY = "20261101"
        const val STREAM = "observer"
        const val WIRE_SEGMENT = "011500_300"
        const val FIRST_START = 1_793_515_500_000L
        const val SECOND_START = FIRST_START + 3_600_000L
    }
}
