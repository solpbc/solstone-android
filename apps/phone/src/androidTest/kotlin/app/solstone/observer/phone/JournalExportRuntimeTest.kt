// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.BrowserRequestBodySource
import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.browser.JournalBrowserUpstream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class JournalExportRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousSingleton: JournalExportTransferManager? = null

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        context.contentResolver.call(TestExportDocumentsProvider.authorityUri(), "reset", null, null)
        ExportDownloadScheduler.cancelJob(context)
        ExportDownloadNotification.cancelStandalone(context)
        ExportDownloadJobService.resetTestCounters()
    }

    @After
    fun tearDown() {
        ExportDownloadScheduler.cancelJob(context)
        ExportDownloadNotification.cancelStandalone(context)
        JournalExportTransferManager.setSingletonForTests(previousSingleton)
        previousSingleton = null
        context.contentResolver.call(TestExportDocumentsProvider.authorityUri(), "reset", null, null)
    }

    private fun awaitCondition(timeoutMs: Long = 10_000L, description: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(25L)
        }
        error("timed out waiting for: $description")
    }

    private fun activeNotificationIds(): Set<Int> {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return emptySet()
        return nm.activeNotifications.map { it.id }.toSet()
    }

    private fun readCapturedBytes(docId: String): ByteArray {
        val uri = TestExportDocumentsProvider.capturedUri(docId)
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("failed to read captured bytes for $docId")
    }

    private class ScriptedRuntimeUpstream(
        private val payload: ByteArray,
        private val etag: String,
        private val interruptFirstAttemptAt: Int? = null,
    ) : JournalBrowserUpstream {
        val attempts = AtomicInteger(0)
        val seenRanges = mutableListOf<Pair<String?, String?>>()
        override val isPoisoned: Boolean get() = false

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): BrowserHttpResponse = error("streaming expected")

        override fun requestStreaming(
            method: String,
            path: String,
            headers: List<Pair<String, String>>,
            bodySource: BrowserRequestBodySource?,
            responseSink: BrowserResponseSink,
        ) {
            val attempt = attempts.incrementAndGet()
            val range = headers.firstOrNull { it.first.equals("range", ignoreCase = true) }?.second
            val ifRange = headers.firstOrNull { it.first.equals("if-range", ignoreCase = true) }?.second
            seenRanges += range to ifRange

            if (range != null && range.startsWith("bytes=") && ifRange == etag) {
                val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                val len = payload.size - start
                responseSink.onStatusAndHeaders(
                    206,
                    "Partial Content",
                    listOf(
                        "Content-Type" to "application/zip",
                        "Content-Length" to len.toString(),
                        "Content-Range" to "bytes $start-${payload.size - 1}/${payload.size}",
                        "ETag" to etag,
                    ),
                )
                responseSink.onBodyChunk(payload, start, len)
                responseSink.onComplete()
                return
            }

            responseSink.onStatusAndHeaders(
                200,
                "OK",
                listOf(
                    "Content-Type" to "application/zip",
                    "Content-Length" to payload.size.toString(),
                    "ETag" to etag,
                ),
            )
            val cut = if (attempt == 1 && interruptFirstAttemptAt != null) interruptFirstAttemptAt else payload.size
            responseSink.onBodyChunk(payload, 0, cut)
            if (cut < payload.size) {
                throw java.io.IOException("simulated disconnect at $cut")
            } else {
                responseSink.onComplete()
            }
        }

        override fun close() = Unit
    }

    private fun buildZipPayload(innerBytes: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("chronicle/20261126/note.txt"))
            val body = ByteArray(innerBytes) { i -> ((i * 29 + 7) and 0xff).toByte() }
            zos.write(body)
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.US, it) }

    private fun waitForPhase(
        manager: JournalExportTransferManager,
        wanted: String,
        timeoutMs: Long = 15_000L,
    ): JournalExportStatusSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snap = manager.currentSnapshot()
            if (snap.phase == wanted) return snap
            Thread.sleep(25L)
        }
        error("timed out waiting for phase=$wanted; last=${manager.currentSnapshot()}")
    }

    @Test
    fun seekableDocumentsProviderSupportsPauseResumeRangeVerifyAndZipReopen() {
        val payload = buildZipPayload(512 * 1024)
        val sha = sha256Hex(payload)
        val etag = "\"$sha\""
        val split = payload.size / 2

        val docId = "seekable/solstone-journal-runtime.zip"
        val uri = TestExportDocumentsProvider.documentUri(docId)
        val displayName = SafExportDestination.queryDisplayName(context, uri, "fallback.zip")
        assertEquals("solstone-journal-runtime.zip", displayName)

        val destination = SafExportDestination(context, uri, displayName)
        val avail = destination.availableSpaceBytesOrNull()
        assertNotNull("Os.fstatvfs must report positive destination capacity on real SAF fd", avail)
        assertTrue("available space must be positive: $avail", (avail ?: 0L) > 0L)

        val upstream = ScriptedRuntimeUpstream(
            payload = payload,
            etag = etag,
            interruptFirstAttemptAt = split,
        )
        val stateFile = File(context.cacheDir, "export-runtime-seekable.json").apply { delete() }
        // Exercise the REAL AndroidExportExecutionScheduler on EVERY API level — zero substitute
        // executor: real JobScheduler UIDT ExportDownloadJobService on API 34+, and real fallback
        // background executor + standalone notification lifecycle on API 26–33.
        val manager = JournalExportTransferManager(
            upstreamOpener = ExportUpstreamOpener { OpenedExportUpstream(upstream, "local") },
            spaceProbe = { 1L }, // Tiny internal probe must NOT block when SAF fstatvfs reports plenty
            destinationReopener = { u, name -> SafExportDestination(context, android.net.Uri.parse(u), name) },
            stateStore = ExportStateStore(stateFile),
            schedulerFactory = { mgr -> AndroidExportExecutionScheduler(context, mgr) },
        )
        previousSingleton = JournalExportTransferManager.setSingletonForTests(manager)

        val scenario = if (Build.VERSION.SDK_INT >= 34) {
            ActivityScenario.launch(PhoneShellActivity::class.java)
        } else {
            null
        }
        try {
            val req = JournalExportStartRequest(
                exportId = "exp_rt_seekable",
                downloadPath = "/app/export/api/ready/exp_rt_seekable/download",
                filename = "solstone-journal-runtime.zip",
                sizeBytes = payload.size.toLong(),
                sha256 = sha,
                etag = etag,
                mode = "full",
            )
            var startSnap: JournalExportStatusSnapshot? = null
            manager.start(
                req,
                pickDestination = { _, cb -> cb(destination) },
                onStarted = { startSnap = it },
            )
            assertNotNull(startSnap)
            assertEquals("transferring", startSnap!!.phase)

            val paused = waitForPhase(manager, "paused", timeoutMs = 30_000L)
            assertEquals("disconnected", paused.pauseReason)
            assertEquals(split.toLong(), paused.bytesWritten)

            if (Build.VERSION.SDK_INT >= 34) {
                awaitCondition(description = "UIDT JobService first run finishes") {
                    ExportDownloadJobService.finishedJobCountForTests.get() >= 1
                }
                assertTrue(
                    "UIDT JobService must run onStartJob on API 34+",
                    ExportDownloadJobService.startedJobCountForTests.get() >= 1,
                )
                assertTrue(
                    "UIDT JobService must post mandatory setNotification on API 34+",
                    ExportDownloadJobService.uidtNotificationsPostedForTests.get() >= 1,
                )
            } else {
                assertEquals(0, ExportDownloadJobService.startedJobCountForTests.get())
            }
            // Both UIDT (post-job repost) and API 26–33 fallback keep the paused notification visible.
            awaitCondition(description = "paused standalone notification visible") {
                ExportDownloadNotification.STANDALONE_NOTIFICATION_ID in activeNotificationIds()
            }

            val resumeSnap = manager.resume("exp_rt_seekable")
            assertEquals("transferring", resumeSnap.phase)

            val saved = waitForPhase(manager, "saved", timeoutMs = 30_000L)
            assertTrue(saved.verified)
            assertEquals(payload.size.toLong(), saved.bytesWritten)
            assertEquals("solstone-journal-runtime.zip", saved.destinationDisplay)
            assertEquals(2, upstream.seenRanges.size)
            assertEquals("bytes=$split-" to etag, upstream.seenRanges[1])

            if (Build.VERSION.SDK_INT >= 34) {
                awaitCondition(description = "UIDT JobService resumed run finishes") {
                    ExportDownloadJobService.finishedJobCountForTests.get() >= 2
                }
                assertTrue(
                    "UIDT JobService must execute both initial and resumed runs",
                    ExportDownloadJobService.startedJobCountForTests.get() >= 2,
                )
                assertTrue(
                    "UIDT JobService must post notifications on both runs",
                    ExportDownloadJobService.uidtNotificationsPostedForTests.get() >= 2,
                )
            }
            val scheduler = context.getSystemService(JobScheduler::class.java)
            assertNull("completed transfer must leave no pending export job", scheduler?.getPendingJob(ExportDownloadScheduler.JOB_ID))
            awaitCondition(description = "export notifications cleared after saved") {
                val ids = activeNotificationIds()
                ExportDownloadNotification.NOTIFICATION_ID !in ids &&
                    ExportDownloadNotification.STANDALONE_NOTIFICATION_ID !in ids
            }
        } finally {
            scenario?.close()
        }

        // Reopen the actual file written through DocumentsProvider (across Binder) as a real ZIP archive.
        val writtenBytes = readCapturedBytes(docId)
        assertArrayEquals(payload, writtenBytes)
        val tmpZip = File(context.cacheDir, "reopen-runtime-seekable.zip")
        tmpZip.writeBytes(writtenBytes)
        try {
            ZipFile(tmpZip).use { zf ->
                val entry = zf.getEntry("chronicle/20261126/note.txt")
                assertNotNull("ZIP entry missing after SAF resume", entry)
                assertEquals(512 * 1024L, entry.size)
            }
        } finally {
            tmpZip.delete()
        }
        stateFile.delete()
    }

    @Test
    fun writeOnlyDocumentsProviderReportsHandedOffUnverifiedAndDeliversExactBytes() {
        val payload = buildZipPayload(128 * 1024)
        val sha = sha256Hex(payload)
        val etag = "\"$sha\""

        val docId = "writeonly/solstone-journal-writeonly.zip"
        val uri = TestExportDocumentsProvider.documentUri(docId)
        val destination = SafExportDestination(context, uri, "solstone-journal-writeonly.zip")
        val upstream = ScriptedRuntimeUpstream(payload = payload, etag = etag)
        val stateFile = File(context.cacheDir, "export-runtime-writeonly.json").apply { delete() }

        val manager = JournalExportTransferManager(
            upstreamOpener = ExportUpstreamOpener { OpenedExportUpstream(upstream, "local") },
            spaceProbe = { 500_000_000L },
            destinationReopener = { u, name -> SafExportDestination(context, android.net.Uri.parse(u), name) },
            stateStore = ExportStateStore(stateFile),
            schedulerFactory = { mgr -> AndroidExportExecutionScheduler(context, mgr) },
        )
        previousSingleton = JournalExportTransferManager.setSingletonForTests(manager)
        val scenario = if (Build.VERSION.SDK_INT >= 34) {
            ActivityScenario.launch(PhoneShellActivity::class.java)
        } else {
            null
        }
        try {
            val req = JournalExportStartRequest(
                exportId = "exp_rt_writeonly",
                downloadPath = "/app/export/api/ready/exp_rt_writeonly/download",
                filename = "solstone-journal-writeonly.zip",
                sizeBytes = payload.size.toLong(),
                sha256 = sha,
                etag = etag,
                mode = "full",
            )
            manager.start(
                req,
                pickDestination = { _, cb -> cb(destination) },
                onStarted = {},
            )

            val handed = waitForPhase(manager, "handed_off", timeoutMs = 30_000L)
            assertFalse("write-only provider must never report verified=true", handed.verified)
            assertEquals(payload.size.toLong(), handed.bytesWritten)
            assertEquals("solstone-journal-writeonly.zip", handed.destinationDisplay)
            assertArrayEquals(payload, readCapturedBytes(docId))
        } finally {
            scenario?.close()
        }
        stateFile.delete()
    }

    @Test
    fun uidtOrFallbackSchedulerMatchesRuntimeApiLevel() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        assertNotNull(scheduler)
        if (Build.VERSION.SDK_INT >= 34) {
            val scheduled = ExportDownloadScheduler.scheduleUidtJob(context, 64L * 1024L * 1024L)
            assertTrue("API 34+ must schedule UIDT job successfully", scheduled)
            val pending = scheduler!!.getPendingJob(ExportDownloadScheduler.JOB_ID)
            assertNotNull("UIDT JobInfo must be visible in JobScheduler", pending)
            assertTrue("UIDT JobInfo must set isUserInitiated=true", pending!!.isUserInitiated)
            ExportDownloadScheduler.cancelJob(context)
            assertNull("cancelJob must remove pending UIDT job", scheduler.getPendingJob(ExportDownloadScheduler.JOB_ID))
        } else {
            val scheduled = ExportDownloadScheduler.scheduleUidtJob(context, 64L * 1024L * 1024L)
            assertFalse("API < 34 must not schedule UIDT job", scheduled)
            assertNull(scheduler!!.getPendingJob(ExportDownloadScheduler.JOB_ID))
        }
    }
}
