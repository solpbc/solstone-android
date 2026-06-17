// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import android.content.Context
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.QueueState
import app.solstone.core.observer.CapturePipeline
import app.solstone.core.pl.EndpointStore
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.pl.transport.conscrypt.openAuthenticatedClient
import app.solstone.platform.pl.transport.conscrypt.pairAndProbe as conscryptPairAndProbe
import app.solstone.platform.work.SyncScheduler
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class RealObserverLifecycle(
    private val context: Context,
    private val pipelineProvider: () -> CapturePipeline,
) : ObserverLifecycle {
    private var pipeline: CapturePipeline? = null

    override fun start() {
        ObserverForegroundService.startFromVisibleContext(context)
        val current = pipeline ?: pipelineProvider().also { pipeline = it }
        current.start()
    }

    override fun stop() {
        pipeline?.stop()
        pipeline = null
        ObserverForegroundService.stop(context)
    }
}

class RealHeartbeatFreshness : HeartbeatFreshness {
    override fun isFresh(): Boolean = ObserverForegroundService.isHeartbeatFresh()
}

class RealPairProbe(
    private val credentialStore: ClientCredentialStore,
    private val identityStore: IdentityStore,
    private val endpointStore: EndpointStore,
) : PairProbe {
    override fun pairAndProbe(pairLink: String, deviceLabel: String): HarnessPairProbeResult {
        val result = conscryptPairAndProbe(pairLink, deviceLabel, credentialStore, identityStore, endpointStore)
        return HarnessPairProbeResult(
            handshakePinned = result.handshakePinned,
            pairStatus = result.pairStatus,
            statusStatus = result.statusStatus,
            statusBody = result.statusBody,
            homeLabel = identityStore.load()?.homeLabel,
            endpointHost = result.endpoint.host,
            endpointPort = result.endpoint.port,
        )
    }
}

class RealPlStatusProbe(
    private val endpointStore: EndpointStore,
    private val credentialStore: ClientCredentialStore,
    private val identityStore: IdentityStore,
) : PlStatusProbe {
    override fun probe(): HarnessPlStatus {
        val endpoint = endpointStore.load()
        val credential = credentialStore.load()
        val identity = identityStore.load()
        if (endpoint == null && credential == null && identity == null) {
            return HarnessPlStatus.NotPaired
        }
        if (endpoint == null || credential == null || identity == null || identity.state != IdentityState.PAIRED) {
            return HarnessPlStatus.PairedButUnreachable("stored pairing is incomplete or revoked")
        }
        return try {
            openAuthenticatedClient(endpoint, credential).use { client ->
                HarnessPlStatus.Reachable(
                    client.request("GET", "/app/link/api/status", emptyMap(), ByteArray(0)).status,
                )
            }
        } catch (e: IOException) {
            HarnessPlStatus.PairedButUnreachable(e.message)
        } catch (e: Exception) {
            HarnessPlStatus.PairedButUnreachable(e.message)
        }
    }
}

class RealSyncEnqueue(private val context: Context) : SyncEnqueue {
    override fun enqueueNow() {
        SyncScheduler.enqueueNow(context)
    }
}

class RealEvidenceReader(private val dao: SegmentDao) : EvidenceReader {
    override fun listEvidence(): List<HarnessEvidenceSegment> =
        dao.segmentsByState(QueueState.SEALED).map { row ->
            HarnessEvidenceSegment(
                id = row.id,
                day = row.day,
                stream = row.stream,
                segment = row.segment,
                state = row.state,
                byteSize = row.byteSize,
                sealedAt = row.sealedAt,
                files = dao.filesBySegmentId(row.id).map { file ->
                    HarnessEvidenceFile(
                        sourceId = file.sourceId,
                        name = file.name,
                        mediaType = file.mediaType,
                        sha256 = file.sha256,
                        byteSize = file.byteSize,
                    )
                },
            )
        }

    override fun pendingCount(): Int = dao.pendingCount(MAIN_STREAM)

    override fun syncState(): HarnessSyncState {
        val row = dao.syncState()
        return HarnessSyncState(
            pendingCount = row?.pendingCount ?: pendingCount(),
            lastSuccessAt = row?.lastSuccessAt,
            lastFailureAt = row?.lastFailureAt,
        )
    }
}

interface BundleFileOp {
    fun copyDirectory(source: Path, destination: Path): Int
}

class NioBundleFileOp : BundleFileOp {
    override fun copyDirectory(source: Path, destination: Path): Int {
        var count = 0
        if (destination.exists()) {
            destination.deleteRecursively()
        }
        Files.createDirectories(destination)
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = destination.resolve(source.relativize(path).toString())
                if (path.isDirectory()) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                    count += 1
                }
            }
        }
        return count
    }
}

class RealBundleExport(
    private val spoolBaseDir: Path,
    private val externalFilesDir: Path,
    private val fileOp: BundleFileOp = NioBundleFileOp(),
) : BundleExport {
    override fun export(segment: HarnessEvidenceSegment): HarnessExportResult {
        val source = spoolBaseDir.resolve(segment.day).resolve(segment.stream).resolve(segment.segment).normalize()
        require(source.startsWith(spoolBaseDir.normalize())) { "segment path escaped spool base" }
        val destination = externalFilesDir
            .resolve("exports")
            .resolve(segment.day)
            .resolve(segment.stream)
            .resolve(segment.segment)
            .normalize()
        require(destination.startsWith(externalFilesDir.normalize())) { "export path escaped external files dir" }
        val count = fileOp.copyDirectory(source, destination)
        return HarnessExportResult(
            sourcePath = source.toString(),
            destinationPath = destination.toString(),
            copiedFileCount = count,
        )
    }
}

private fun Path.deleteRecursively() {
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}
