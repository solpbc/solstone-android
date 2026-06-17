// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.observer.IngestOutcome
import app.solstone.core.observer.ObserverIngestClient
import app.solstone.core.observer.ObserverRegistration
import app.solstone.core.observer.SegmentReconciler
import app.solstone.core.pl.DirectEndpoint
import app.solstone.platform.identity.file.FileClientCredentialStore
import app.solstone.platform.identity.file.FileIdentityStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VPE-direct on-device validation driver for the Wave-1 observer foundation.
 *
 * Wires [pairAndProbe] / [openAuthenticatedClient] (this Conscrypt mTLS transport) to
 * [ObserverRegistration] / [ObserverIngestClient] / [SegmentReconciler] (core:observer)
 * and runs the full live arc against a real journal:
 *   pair -> PL status -> register -> ingest a synthetic sealed segment -> reconcile,
 * plus an mTLS-after-process-death re-handshake from the persisted credential.
 *
 * Inert by default: every step skips (JUnit Assume) unless `-e pairLink <go.solstone.app/p#...>`
 * is supplied, so this never runs in CI / GMD. Drive it from the build box against the
 * two bench devices (the watch needs Conscrypt for TLS 1.3; the phone uses platform TLS):
 *
 *   adb -s <serial> install -r -t <androidTest.apk>
 *   adb -s <serial> shell am instrument -w \
 *     -e pairLink 'https://go.solstone.app/p#...' \
 *     -e hostname rogbid-validation -e streamType watch \
 *     -e class app.solstone.platform.pl.transport.conscrypt.LiveObserverDriverTest \
 *     app.solstone.platform.pl.transport.conscrypt.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Run t5 (process-death) in a SEPARATE `am instrument` invocation (omit pairLink, target
 * `...LiveObserverDriverTest#t5_mtlsSurvivesProcessDeath`) so it re-handshakes in a fresh
 * process from disk-only state — a genuine process boundary.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LiveObserverDriverTest {

    private val args get() = InstrumentationRegistry.getArguments()
    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun arg(name: String, default: String): String =
        args.getString(name)?.takeIf { it.isNotBlank() } ?: default

    private val pairLink: String? get() = args.getString("pairLink")?.takeIf { it.isNotBlank() }

    private val plDir: File get() = File(ctx.filesDir, "pl-driver").apply { mkdirs() }
    private fun credStore(): ClientCredentialStore = FileClientCredentialStore(File(plDir, "credential.pem"))
    private fun idStore(): IdentityStore = FileIdentityStore(File(plDir, "identity.tsv"))
    private val endpointFile: File get() = File(plDir, "endpoint.txt")
    private val ingestFile: File get() = File(plDir, "ingest.txt")
    private val resultFile: File get() = File(plDir, "driver-result.txt")

    @Test
    fun t1_pairAndProbeStatus() {
        assumeTrue("supply -e pairLink <go.solstone.app/p#...> to run the live driver", pairLink != null)
        val link = pairLink!!
        result("t1.pairLinkHost=${hostOf(link)}")
        try {
            val probe = pairAndProbe(
                pairLink = link,
                deviceLabel = arg("deviceLabel", "android-validation"),
                credentialStore = credStore(),
                identityStore = idStore(),
            )
            endpointFile.writeText("${probe.endpoint.host}\n${probe.endpoint.port}\n")
            result("t1.handshakePinned=${probe.handshakePinned}")
            result("t1.pairStatus=${probe.pairStatus}")
            result("t1.plStatusStatus=${probe.statusStatus}")
            result("t1.endpoint=${probe.endpoint.host}:${probe.endpoint.port}")

            assertTrue("CA-fp pin must hold during the cert-less pair handshake", probe.handshakePinned)
            assertEquals("pair POST must return 200", 200, probe.pairStatus)
            assertEquals("authenticated PL status probe must return 200", 200, probe.statusStatus)
        } catch (t: Throwable) {
            result("t1.ERROR=${t.javaClass.simpleName}: ${t.message}")
            throw t
        }
    }

    @Test
    fun t2_registerObserver() {
        val credential = credStore().load()
        assumeTrue("t1 must pair first (no stored credential)", credential != null)
        try {
            openAuthenticatedClient(endpoint(), credential!!).use { client ->
                val observer = ObserverRegistration(client).register(
                    platform = arg("platform", "android"),
                    hostname = arg("hostname", "android-validation"),
                    streamType = arg("streamType", "watch"),
                    version = arg("version", "0.1-validation"),
                )
                result("t2.handle=${observer.handle}")
                result("t2.handleLen=${observer.handle.length}")
                result("t2.stream=${observer.stream}")
                result("t2.ingestUrl=${observer.ingestUrl}")
                result("t2.protocolVersion=${observer.protocolVersion}")

                assertEquals("observer handle must be the 43-char DL key", 43, observer.handle.length)

                idStore().load()?.let { home ->
                    idStore().save(home.copy(observerHandle = observer.handle))
                }
            }
        } catch (t: Throwable) {
            result("t2.ERROR=${t.javaClass.simpleName}: ${t.message}")
            throw t
        }
    }

    @Test
    fun t3_ingestSyntheticSegment() {
        val credential = credStore().load()
        val handle = idStore().load()?.observerHandle
        assumeTrue("t2 must register first (no stored handle)", credential != null && handle != null)
        try {
            val payload = "solstone-android-observer-validation\n".toByteArray(Charsets.UTF_8)
            val now = Date()
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
            val segmentKey = "${SimpleDateFormat("HHmmss", Locale.US).format(now)}_${payload.size}"
            val fileName = "validation.txt"
            val expectedSha = sha256Hex(payload)
            val manifest = BundleManifest(
                key = SegmentKey(day = day, segment = segmentKey),
                files = listOf(
                    BundleFile(
                        sourceId = "validation",
                        name = fileName,
                        sha256 = expectedSha,
                        byteSize = payload.size.toLong(),
                        mediaType = "text/plain",
                        captureStartEpochMs = now.time - 300_000L,
                        captureEndEpochMs = now.time,
                    ),
                ),
                gaps = emptyList(),
            )

            val outcome = openAuthenticatedClient(endpoint(), credential!!).use { client ->
                ObserverIngestClient(client) { "solstoneAndroidValidation${System.nanoTime()}" }.ingest(
                    manifest = manifest,
                    handle = handle!!,
                    fileBytes = { payload },
                    host = arg("hostname", "android-validation"),
                    platform = arg("platform", "android"),
                )
            }
            result("t3.requestedDay=$day")
            result("t3.requestedSegment=$segmentKey")
            result("t3.expectedSha=$expectedSha")
            result("t3.outcome=${outcome.javaClass.simpleName}")

            val serverSegment = when (outcome) {
                is IngestOutcome.Accepted -> outcome.serverSegment
                is IngestOutcome.Collision -> outcome.serverSegment
                is IngestOutcome.Duplicate -> outcome.existingSegment
                is IngestOutcome.Rejected -> null
            }
            result("t3.serverSegment=$serverSegment")
            if (outcome is IngestOutcome.Rejected) {
                result("t3.rejectStatus=${outcome.status}")
                result("t3.rejectBody=${outcome.body.take(200)}")
            }

            assertTrue(
                "ingest must be accepted (or a key collision) — got ${outcome.javaClass.simpleName}",
                outcome is IngestOutcome.Accepted || outcome is IngestOutcome.Collision,
            )
            assertNotNull("server must return the canonical segment key", serverSegment)

            ingestFile.writeText("day=$day\nsegment=$serverSegment\nfileName=$fileName\nsha=$expectedSha\n")
        } catch (t: Throwable) {
            result("t3.ERROR=${t.javaClass.simpleName}: ${t.message}")
            throw t
        }
    }

    @Test
    fun t4_reconcileSegments() {
        val credential = credStore().load()
        assumeTrue("t3 must ingest first (no ingest record)", credential != null && ingestFile.exists())
        try {
            val record = ingestFile.readLines().filter { it.contains('=') }.associate {
                val (k, v) = it.split('=', limit = 2); k to v
            }
            val day = record.getValue("day")
            val segment = record.getValue("segment")
            val expectedSha = record.getValue("sha")

            val segments = openAuthenticatedClient(endpoint(), credential!!).use { client ->
                SegmentReconciler(client).fetch(day)
            }
            result("t4.day=$day")
            result("t4.segmentCount=${segments.size}")
            result("t4.keys=${segments.joinToString(",") { it.key }}")

            val found = segments.firstOrNull { it.key == segment }
            assertNotNull("reconcile must list the just-ingested segment ($segment)", found)
            result("t4.matchedFiles=${found!!.files}")
            assertTrue(
                "reconciled segment must carry the uploaded file's sha256",
                found.files.values.any { it.equals(expectedSha, ignoreCase = true) },
            )
        } catch (t: Throwable) {
            result("t4.ERROR=${t.javaClass.simpleName}: ${t.message}")
            throw t
        }
    }

    @Test
    fun t5_mtlsSurvivesProcessDeath() {
        // Loads ONLY disk state — in a fresh `am instrument` invocation this process never
        // paired in-memory, so a 200 here proves the persisted credential alone re-handshakes.
        val credential = credStore().load()
        assumeTrue("needs a stored credential + endpoint from a prior pairing", credential != null && endpointFile.exists())
        try {
            val status = openAuthenticatedClient(endpoint(), credential!!).use { client ->
                client.request("GET", "/app/link/api/status", emptyMap(), ByteArray(0))
            }
            result("t5.rehandshakeStatus=${status.status}")
            assertEquals("re-handshake from stored credential must return 200", 200, status.status)
        } catch (t: Throwable) {
            result("t5.ERROR=${t.javaClass.simpleName}: ${t.message}")
            throw t
        }
    }

    private fun endpoint(): DirectEndpoint {
        val lines = endpointFile.readLines()
        return DirectEndpoint(lines[0].trim(), lines[1].trim().toInt())
    }

    private fun hostOf(link: String): String = runCatching { java.net.URL(link).host }.getOrDefault("?")

    private fun result(line: String) {
        Log.i(TAG, line)
        resultFile.appendText(line + "\n")
    }

    private companion object {
        const val TAG = "PlDriver"
    }
}
