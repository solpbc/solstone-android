// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.GapEvent
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.parseManifest
import app.solstone.core.spool.serializeManifest
import app.solstone.core.sources.PayloadRef
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpoolManifestRoundTripInstrumentedTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var baseDir: Path

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory(ctx.cacheDir.toPath(), "spool-roundtrip")
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun spoolManifestRoundTripsEscapedFieldsOnDevice() {
        val payloads = listOf(
            SegmentPayload(
                sourceId = "source one +\tΩ",
                ref = PayloadRef(
                    name = "audio one.bin",
                    mediaType = "audio/café +\ttype",
                    byteSize = PAYLOAD_ONE.size.toLong(),
                    sha256 = "561910d04683886f53ccd37a58e2b928094308822c0029470fe8bcc76cfa98c0",
                ),
                captureStartEpochMs = 1_800,
                captureEndEpochMs = 1_900,
            ),
            SegmentPayload(
                sourceId = "source two naïve+\ttab",
                ref = PayloadRef(
                    name = "meta two.bin",
                    mediaType = "application/solstone+Ω\tmeta",
                    byteSize = PAYLOAD_TWO.size.toLong(),
                    sha256 = "fceba8d63d30c5c8fd82656acdfa1feed961f7d556a29256b23ae2d3845ffd11",
                ),
                captureStartEpochMs = 1_900,
                captureEndEpochMs = 2_100,
            ),
        )
        val sealed = SealedSegment(
            stream = "audio",
            key = SegmentKey("20260616", "120000_300"),
            wireKeys = WireKeys(
                day = "20260616",
                segment = "120000_300",
                startEpochMs = 1_800,
                endEpochMs = 2_100,
                zoneId = "America/Denver café+\tΩ",
                utcOffsetSeconds = -21_600,
            ),
            payloads = payloads,
            gaps = listOf(
                GapEvent(
                    kind = "capture_gap",
                    atEpochMs = 1_950,
                    detail = "detail space + tab\tcafé Ω",
                ),
            ),
        )

        val result = FileSpoolWriter(baseDir).seal(
            sealed,
            object : PayloadBytesProvider {
                override fun open(payload: SegmentPayload) =
                    ByteArrayInputStream(bytesFor(payload))
            },
        )

        val sealedDir = baseDir.resolve(sealed.key.day).resolve(sealed.stream).resolve(sealed.key.segment)
        assertEquals(sealedDir, result.directory)
        assertTrue(Files.isDirectory(sealedDir))
        assertTrue(Files.isRegularFile(sealedDir.resolve("audio one.bin")))
        assertTrue(Files.isRegularFile(sealedDir.resolve("meta two.bin")))
        assertTrue(Files.isRegularFile(sealedDir.resolve("manifest")))
        assertFalse(Files.exists(baseDir.resolve(".draft").resolve(sealed.key.day).resolve(sealed.stream).resolve(sealed.key.segment)))

        val manifestText = String(Files.readAllBytes(sealedDir.resolve("manifest")), StandardCharsets.UTF_8)
        assertEquals(serializeManifest(sealed, result.manifest), manifestText)
        val parsed = parseManifest(manifestText)
        val expectedManifest = BundleManifest(
            key = sealed.key,
            files = payloads.map { payload ->
                BundleFile(
                    sourceId = payload.sourceId,
                    name = payload.ref.name,
                    sha256 = payload.ref.sha256 ?: error("missing sha256"),
                    byteSize = payload.ref.byteSize,
                    mediaType = payload.ref.mediaType,
                    captureStartEpochMs = payload.captureStartEpochMs,
                    captureEndEpochMs = payload.captureEndEpochMs,
                )
            },
            gaps = sealed.gaps,
        )

        assertEquals(expectedManifest.key, parsed.manifest.key)
        assertEquals(expectedManifest.files, parsed.manifest.files)
        assertEquals(expectedManifest.gaps, parsed.manifest.gaps)
        assertEquals(sealed.wireKeys.startEpochMs, parsed.startEpochMs)
        assertEquals(sealed.wireKeys.endEpochMs, parsed.endEpochMs)
        assertEquals(sealed.wireKeys.zoneId, parsed.zoneId)
        assertEquals(sealed.wireKeys.utcOffsetSeconds, parsed.utcOffsetSeconds)
    }

    private fun bytesFor(payload: SegmentPayload): ByteArray =
        when (payload.ref.name) {
            "audio one.bin" -> PAYLOAD_ONE
            "meta two.bin" -> PAYLOAD_TWO
            else -> error("unexpected payload: ${payload.ref.name}")
        }

    private fun Path.deleteRecursively() {
        if (!Files.exists(this)) return
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private companion object {
        val PAYLOAD_ONE = "alpha bytes".toByteArray(StandardCharsets.UTF_8)
        val PAYLOAD_TWO = "beta bytes + tab\tomega".toByteArray(StandardCharsets.UTF_8)
    }
}
