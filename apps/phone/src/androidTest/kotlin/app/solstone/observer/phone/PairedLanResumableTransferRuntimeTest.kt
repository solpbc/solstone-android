// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.net.ConnectivityManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.core.diagnostics.PairingFact
import app.solstone.core.identity.JournalVersionRecord
import app.solstone.core.pl.parseJson
import app.solstone.observer.formfactor.phone.ImportRowKind
import app.solstone.platform.persistence.room.IMPORT_STATE_PENDING
import app.solstone.platform.pl.transport.conscrypt.pairAndProbe
import app.solstone.platform.work.JournalBrowserUpstreamAdapter
import app.solstone.platform.work.syncStores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/**
 * End-to-end isolated-LAN runtime proof against a real paired synthetic journal with NO validated
 * internet (`hasValidatedNetwork == false`), exercising BOTH normal app entry points:
 *
 * 1. Large resumable import upload (Job 7401 path): `ImportIntake.get(context).submit(...)` ->
 *    `ImportUploadScheduler.scheduleForImports(context)` -> 17 MiB resumable `/app/import/api/uploads`,
 *    mid-upload interruption, single-owner duplicate suppression on concurrent `scheduleForImports` /
 *    `resumePendingLargeImports`, clean resume, and completion.
 * 2. Export download (Job 7402 path): `JournalExportTransferManager.get(context)` over the real
 *    paired SPL tunnel (`JournalBrowserUpstreamAdapter`) + `SafExportDestination`, mid-download
 *    interruption, resume via `Range` + `If-Range`, SHA-256 verification, and `ZipFile` reopen.
 * 3. Unreachable journal waiting state for BOTH upload (accurate `WAITING` row, `inFlight == false`,
 *    zero stuck sending notification) and download (honest `paused` / `disconnected` at 0 B).
 *
 * Skips cleanly unless invoked with `-e paired_lan_pair_link <link> -e paired_lan_ctrl_url <url>`.
 */
@RunWith(AndroidJUnit4::class)
class PairedLanResumableTransferRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    @Before
    fun setUp() {
        val pairLink = args.getString("paired_lan_pair_link")
        assumeTrue("paired_lan_pair_link instrumentation arg required", !pairLink.isNullOrBlank())
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        context.contentResolver.call(TestExportDocumentsProvider.authorityUri(), "reset", null, null)
        ImportUploadJobService.stopActiveForTests()
        ImportUploadJobService.resetTestCounters()
        ImportUploadScheduler.cancelJob(context)
        ImportUploadNotification.cancelStandalone(context)
        ExportDownloadScheduler.cancelJob(context)
        ExportDownloadNotification.cancelStandalone(context)
        ExportDownloadJobService.resetTestCounters()
        JournalExportTransferManager.setSingletonForTests(null)
    }

    @After
    fun tearDown() {
        ImportUploadJobService.stopActiveForTests()
        ImportUploadScheduler.cancelJob(context)
        ImportUploadNotification.cancelStandalone(context)
        ExportDownloadScheduler.cancelJob(context)
        ExportDownloadNotification.cancelStandalone(context)
        runCatching { JournalExportTransferManager.get(context).cancel(null) }
        JournalExportTransferManager.setSingletonForTests(null)
        context.contentResolver.call(TestExportDocumentsProvider.authorityUri(), "reset", null, null)
    }

    private fun ctrlPost(pathAndQuery: String) {
        val base = args.getString("paired_lan_ctrl_url")?.trimEnd('/') ?: return
        val url = URL("$base$pathAndQuery")
        val host = url.host
        val port = if (url.port > 0) url.port else 80
        val target = buildString {
            append(url.path.ifEmpty { "/" })
            if (!url.query.isNullOrEmpty()) {
                append('?')
                append(url.query)
            }
        }
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress(host, port), 5_000)
            sock.soTimeout = 5_000
            val req = "POST $target HTTP/1.1\r\nHost: $host:$port\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            sock.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
            sock.getOutputStream().flush()
            val statusLine = sock.getInputStream().bufferedReader(Charsets.US_ASCII).readLine() ?: ""
            assertTrue("proxy control $pathAndQuery failed: $statusLine", statusLine.contains(" 200 "))
        }
    }

    private fun awaitCondition(
        timeoutMs: Long,
        description: String,
        diag: (() -> String)? = null,
        check: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(50L)
        }
        val extra = diag?.let { " | diag=${runCatching(it).getOrElse { e -> e.toString() }}" } ?: ""
        error("timed out waiting for: $description$extra")
    }

    private fun activeNotificationIds(): Set<Int> {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return emptySet()
        return nm.activeNotifications.map { it.id }.toSet()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.US, it) }

    private fun buildLargeSyntheticMarkdown(targetBytes: Int): ByteArray {
        val header = "# Synthetic Isolated LAN Resumable Import Proof\n\n"
        val line = "2026-11-26 isolated-lan-resumable-import-verification-block-0123456789abcdef\n"
        val sb = StringBuilder(targetBytes + 128)
        sb.append(header)
        var n = 0
        while (sb.length < targetBytes) {
            sb.append(n++).append(':').append(line)
        }
        return sb.toString().toByteArray(Charsets.UTF_8).copyOf(targetBytes)
    }

    @Test
    fun isolatedLanUploadAndDownloadBothInterruptResumeAndReportUnreachableHonestly() {
        val pairLink = args.getString("paired_lan_pair_link")!!
        val journalVersion = args.getString("paired_lan_journal_version") ?: "2.0.92"

        // 0. Capture actual Android network capabilities — must have NO validated internet.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val activeNet = cm?.activeNetwork
        val caps = activeNet?.let { cm.getNetworkCapabilities(it) }
        val hasValidated = ExportDownloadScheduler.hasValidatedNetwork(context)
        assertFalse(
            "expected isolated LAN without validated internet; activeNet=$activeNet caps=$caps",
            hasValidated,
        )

        // 1. Pair with the real synthetic solstone-core journal over direct isolated LAN.
        ctrlPost("/online")
        resetPersistence(context)
        val stores = syncStores(context)
        val paired = pairAndProbe(
            pairLink = pairLink,
            deviceLabel = "isolated-lan-android-proof",
            credentialStore = stores.credentialStore,
            identityStore = stores.identityStore,
            endpointStore = stores.endpointStore,
            journalMarkStore = stores.journalMarkStore,
            journalIdentityCoordinator = stores.journalIdentityCoordinator,
            publisher = stores.publisher,
            confirmation = stores.journalConfirmationStore,
        )
        assertEquals("direct pairing failed", 200, paired.pairStatus)
        val identity = checkNotNull(stores.identityStore.load()) { "paired identity missing" }
        assertNotNull("paired credential missing", stores.credentialStore.load())
        assertTrue(
            "journal mark confirmation failed",
            performPhoneConfirm(context, stores, identity.clientCertFingerprint),
        )
        stores.journalVersionStore.save(
            JournalVersionRecord(identity.instanceId, identity.caChainFingerprint, journalVersion),
        )

        val js = context.getSystemService(JobScheduler::class.java)
        val receiptLines = mutableListOf<String>()
        receiptLines += "NETWORK hasValidated=$hasValidated activeNet=$activeNet caps=$caps"
        receiptLines += "PAIRED instanceId=${identity.instanceId} journalVersion=$journalVersion"

        // =========================================================================
        // PART A: LARGE RESUMABLE IMPORT UPLOAD (JOB 7401 PATH) — NORMAL APP ENTRY
        // =========================================================================
        val uploadSize = 17 * 1024 * 1024 // >= 16 MiB resumable threshold (5 chunks: 4x4 MiB + 1 MiB)
        val uploadBytes = buildLargeSyntheticMarkdown(uploadSize)
        val uploadSha = sha256Hex(uploadBytes)
        val uploadName = "isolated-lan-resumable-17m.md"

        // Arm a single mid-upload TCP disconnect after ~5 MiB client->server bytes with hold_offline=1
        // so chunk 1 commits (>= 4 MiB confirmed offset on the real journal), chunk 2 interrupts,
        // and the fallback exits cleanly into the paused state for deterministic resume + single-owner verification.
        ctrlPost("/arm-drop?dir=c2s&after_bytes=5242880&hold_offline=1")

        val intake = ImportIntake.get(context)
        val intakeDone = CountDownLatch(1)
        val intakeOutcome = AtomicReference<IntakeOutcome>()
        intake.submit(
            listOf(
                IntakeItem.Stream(
                    displayName = uploadName,
                    mime = "text/markdown",
                    scheme = "content",
                    reportedSize = uploadBytes.size.toLong(),
                    input = ByteArrayInputStream(uploadBytes),
                ),
            ),
        ) { outcome ->
            intakeOutcome.set(outcome)
            intakeDone.countDown()
        }
        assertTrue("ImportIntake did not finish", intakeDone.await(30, TimeUnit.SECONDS))
        assertEquals("expected 1 accepted import item", 1, intakeOutcome.get().accepted)
        val uploadItemId = intakeOutcome.get().saved.single().id

        // Wait until chunk 1 committed (>= 4 MiB confirmed offset) and the armed interruption
        // paused the upload cleanly with zero pending UIDT Job 7401.
        awaitCondition(60_000L, "resumable upload pauses with confirmed intermediate offset after interruption") {
            val row = intake.store.items(identity.instanceId, journalVersion).firstOrNull { it.id == uploadItemId }
            !ImportUploadJobService.isActive() &&
                row != null &&
                row.state == IMPORT_STATE_PENDING &&
                row.uploadResumable &&
                row.uploadOffset in (4L * 1024L * 1024L) until uploadSize.toLong()
        }
        val pausedUploadRow = intake.store.items(identity.instanceId, journalVersion).first { it.id == uploadItemId }
        val observedIntermediateOffset = pausedUploadRow.uploadOffset
        assertEquals("paused", pausedUploadRow.uploadPhase)
        assertNull("no unsatisfied Job 7401 may be queued while paused on unvalidated LAN", js?.getPendingJob(ImportUploadScheduler.JOB_ID))
        awaitCondition(5_000L, "standalone paused upload notification visible") {
            ImportUploadNotification.STANDALONE_NOTIFICATION_ID in activeNotificationIds()
        }

        // Bring the paired journal back online and invoke BOTH normal entry points back-to-back,
        // plus direct startLocalFallback while active, proving strictly one runner owns each offset.
        ctrlPost("/online")
        ImportUploadScheduler.resumePendingLargeImports(context)
        ImportUploadScheduler.scheduleForImports(context)
        ImportUploadScheduler.resumePendingLargeImports(context)
        assertFalse(
            "CAS single-owner guard must refuse concurrent startLocalFallback while active",
            ImportUploadJobService.startLocalFallback(context),
        )

        awaitCondition(
            timeoutMs = 45_000L,
            description = "resumed 17 MiB upload completes and records recent receipt",
            diag = {
                val allRows = intake.store.items(identity.instanceId, journalVersion)
                val row = allRows.firstOrNull { it.id == uploadItemId }
                val rec = intake.store.recentReceipts(identity.instanceId)
                "active=${ImportUploadJobService.isActive()} starts=${ImportUploadJobService.startedLocalFallbackCountForTests.get()} fin=${ImportUploadJobService.finishedRunsCountForTests.get()} row=$row receipts=$rec"
            },
        ) {
            val row = intake.store.items(identity.instanceId, journalVersion).firstOrNull { it.id == uploadItemId }
            row == null && intake.store.recentReceipts(identity.instanceId).any { it.filename == uploadName }
        }

        val finalReceipts = intake.store.recentReceipts(identity.instanceId)
        val deliveredReceipt = finalReceipts.firstOrNull { it.filename == uploadName }
        assertNotNull("large resumable import did not deliver", deliveredReceipt)
        assertEquals("UIDT Job 7401 must not run on unvalidated LAN", 0, ImportUploadJobService.startedUidtJobCountForTests.get())
        assertEquals("expected exactly 2 fallback runs (initial + resumed, zero duplicates)", 2, ImportUploadJobService.startedLocalFallbackCountForTests.get())
        assertTrue(
            "concurrent schedule/fallback calls while active must suppress duplicates; got ${ImportUploadJobService.duplicateSuppressedCountForTests.get()}",
            ImportUploadJobService.duplicateSuppressedCountForTests.get() >= 1,
        )
        assertNull("no unsatisfied Job 7401 may remain queued", js?.getPendingJob(ImportUploadScheduler.JOB_ID))
        awaitCondition(10_000L, "import fallback runner finishes and clears notification") {
            !ImportUploadJobService.isActive() &&
                ImportUploadNotification.STANDALONE_NOTIFICATION_ID !in activeNotificationIds()
        }
        receiptLines += "UPLOAD_PASS itemId=$uploadItemId size=$uploadSize sha256=$uploadSha intermediateOffset=$observedIntermediateOffset importId=${deliveredReceipt!!.importId} fallbackStarts=${ImportUploadJobService.startedLocalFallbackCountForTests.get()} duplicatesSuppressed=${ImportUploadJobService.duplicateSuppressedCountForTests.get()}"

        // =========================================================================
        // PART B: JOURNAL EXPORT DOWNLOAD (JOB 7402 PATH) — NORMAL APP ENTRY
        // =========================================================================
        // Prepare a real export under this paired device's own mTLS identity.
        ctrlPost("/online")
        val adapter = JournalBrowserUpstreamAdapter(stores.publisher, stores.handshakeGuard)
        val prepResp = adapter.open().use { up ->
            up.request(
                method = "POST",
                path = "/app/export/api/jobs",
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                body = """{"mode":"full"}""".toByteArray(Charsets.UTF_8),
            )
        }
        assertTrue("export prepare failed status=${prepResp.status}", prepResp.status in 200..202)

        var readyMap: Map<*, *>? = null
        awaitCondition(60_000L, "paired journal export becomes ready") {
            val st = adapter.open().use { up ->
                up.request("GET", "/app/export/api/state", mapOf("Accept" to "application/json"), null)
            }
            val parsed = parseJson(st.body.toString(Charsets.UTF_8)) as? Map<*, *>
            val status = parsed?.get("status") as? String
            if (status == "ready") {
                readyMap = parsed["ready"] as? Map<*, *>
                true
            } else {
                false
            }
        }
        val ready = checkNotNull(readyMap) { "ready export metadata missing" }
        val exportId = ready["export_id"] as String
        val downloadPath = ready["download_path"] as String
        val filename = ready["filename"] as String
        val exportSize = (ready["size_bytes"] as BigDecimal).longValueExact()
        val exportSha = ready["sha256"] as String
        val exportEtag = ready["etag"] as String
        assertTrue("expected non-trivial prepared export ZIP >= 10 MiB; got $exportSize", exportSize >= 10L * 1024L * 1024L)

        // Arm mid-download TCP disconnect after ~4 MiB server->client bytes.
        val dropDownloadAt = 4 * 1024 * 1024
        ctrlPost("/arm-drop?dir=s2c&after_bytes=$dropDownloadAt")

        val docId = "seekable/$filename"
        val docUri = TestExportDocumentsProvider.documentUri(docId)
        val safDest = SafExportDestination(context, docUri, filename)

        // Use the REAL production singleton JournalExportTransferManager.get(context)!
        val exportMgr = JournalExportTransferManager.get(context)
        val exportReq = JournalExportStartRequest(
            exportId = exportId,
            downloadPath = downloadPath,
            filename = filename,
            sizeBytes = exportSize,
            sha256 = exportSha,
            etag = exportEtag,
            mode = "full",
        )
        exportMgr.start(exportReq, pickDestination = { _, cb -> cb(safDest) }, onStarted = {})

        awaitCondition(30_000L, "export pauses honestly on mid-stream LAN interruption") {
            exportMgr.currentSnapshot().phase == "paused"
        }
        val pausedSnap = exportMgr.currentSnapshot()
        assertEquals("disconnected", pausedSnap.pauseReason)
        assertTrue(
            "expected partial bytes written before interruption; got ${pausedSnap.bytesWritten} of $exportSize",
            pausedSnap.bytesWritten in 1L until exportSize,
        )
        assertNull("no unsatisfied Job 7402 may remain queued", js?.getPendingJob(ExportDownloadScheduler.JOB_ID))
        assertEquals(0, ExportDownloadJobService.startedJobCountForTests.get())
        awaitCondition(10_000L, "export paused standalone notification visible") {
            ExportDownloadNotification.STANDALONE_NOTIFICATION_ID in activeNotificationIds()
        }

        // Resume via normal manager.resume(...) and verify Range/If-Range completion + ZIP reopen.
        ctrlPost("/online")
        exportMgr.resume(exportId)
        awaitCondition(60_000L, "resumed export completes and verifies") {
            exportMgr.currentSnapshot().phase in setOf("saved", "error")
        }
        val savedSnap = exportMgr.currentSnapshot()
        assertEquals("expected saved after resume: $savedSnap", "saved", savedSnap.phase)
        assertTrue("expected verified=true", savedSnap.verified)
        assertEquals(exportSize, savedSnap.bytesWritten)
        assertNull(js?.getPendingJob(ExportDownloadScheduler.JOB_ID))
        awaitCondition(10_000L, "export notifications cleared after saved") {
            val ids = activeNotificationIds()
            ExportDownloadNotification.NOTIFICATION_ID !in ids &&
                ExportDownloadNotification.STANDALONE_NOTIFICATION_ID !in ids
        }

        val capturedZipBytes = context.contentResolver.openInputStream(TestExportDocumentsProvider.capturedUri(docId))
            ?.use { it.readBytes() }
            ?: error("missing captured export bytes")
        assertEquals(exportSize, capturedZipBytes.size.toLong())
        assertEquals(exportSha, sha256Hex(capturedZipBytes))
        val tmpZip = File(context.cacheDir, "paired-lan-reopen.zip")
        tmpZip.writeBytes(capturedZipBytes)
        val zipEntryCount = try {
            ZipFile(tmpZip).use { zf -> zf.size() }
        } finally {
            tmpZip.delete()
        }
        assertTrue("reopened ZIP must contain entries", zipEntryCount > 0)
        receiptLines += "DOWNLOAD_PASS exportId=$exportId size=$exportSize sha256=$exportSha pausedAt=${pausedSnap.bytesWritten} zipEntries=$zipEntryCount"

        // =========================================================================
        // PART C: TRULY UNREACHABLE JOURNAL ON ISOLATED LAN — ACCURATE WAITING STATE
        // =========================================================================
        ctrlPost("/offline")

        // C1. Unreachable upload: enters local fallback, fails cleanly, leaves accurate WAITING state.
        val offlineUploadName = "offline-waiting-17m.md"
        val offlineDone = CountDownLatch(1)
        val offlineOutcome = AtomicReference<IntakeOutcome>()
        intake.submit(
            listOf(
                IntakeItem.Stream(
                    displayName = offlineUploadName,
                    mime = "text/markdown",
                    scheme = "content",
                    reportedSize = uploadBytes.size.toLong(),
                    input = ByteArrayInputStream(uploadBytes),
                ),
            ),
        ) { outcome ->
            offlineOutcome.set(outcome)
            offlineDone.countDown()
        }
        assertTrue(offlineDone.await(30, TimeUnit.SECONDS))
        val offlineItemId = offlineOutcome.get().saved.single().id
        awaitCondition(20_000L, "unreachable upload exits fallback cleanly into WAITING state") {
            !ImportUploadJobService.isActive() &&
                intake.store.items(identity.instanceId, journalVersion).any {
                    it.id == offlineItemId && it.state == IMPORT_STATE_PENDING && !it.inFlight
                }
        }
        val pane = readImportPane(intake, stores, PairingFact.PAIRED, awaitingConfirmation = false, online = false)
        val offlineRow = pane.rows.firstOrNull { it.id == offlineItemId }
        assertNotNull("offline import row missing in pane", offlineRow)
        assertEquals("unreachable import must show WAITING, not SENDING", ImportRowKind.WAITING, offlineRow!!.kind)
        assertNull("no unsatisfied Job 7401 when unreachable", js?.getPendingJob(ImportUploadScheduler.JOB_ID))
        awaitCondition(5_000L, "unreachable 0-byte import clears sending notification") {
            ImportUploadNotification.STANDALONE_NOTIFICATION_ID !in activeNotificationIds()
        }
        ImportUploadCancelReceiver.cancelImportItem(context, offlineItemId)

        // C2. Unreachable download: pauses honestly at 0 B with pauseReason=disconnected.
        val offDocUri = TestExportDocumentsProvider.documentUri("seekable/offline-export.zip")
        val offSafDest = SafExportDestination(context, offDocUri, "offline-export.zip")
        exportMgr.start(
            exportReq.copy(exportId = exportId, filename = "offline-export.zip"),
            pickDestination = { _, cb -> cb(offSafDest) },
            onStarted = {},
        )
        awaitCondition(15_000L, "unreachable export transitions honestly to paused/disconnected") {
            exportMgr.currentSnapshot().phase == "paused"
        }
        val offExportSnap = exportMgr.currentSnapshot()
        assertEquals("disconnected", offExportSnap.pauseReason)
        assertEquals(0L, offExportSnap.bytesWritten)
        assertNull("no unsatisfied Job 7402 when unreachable", js?.getPendingJob(ExportDownloadScheduler.JOB_ID))
        exportMgr.cancel(exportId)
        receiptLines += "UNREACHABLE_PASS uploadRowKind=${offlineRow.kind} downloadPhase=${offExportSnap.phase}/${offExportSnap.pauseReason}@${offExportSnap.bytesWritten}B"

        // Write structured receipt into cache for host collection.
        File(context.cacheDir, "paired-lan-receipt.txt").writeText(receiptLines.joinToString("\n") + "\n")
        receiptLines.forEach { android.util.Log.i("PairedLanProof", it) }
    }
}
