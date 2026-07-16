// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.model.QueueState
import app.solstone.core.queue.EvictionInput
import app.solstone.core.queue.EvictionBudget
import app.solstone.core.queue.EvictionResult
import app.solstone.core.queue.QueueSegmentDescriptor
import app.solstone.core.queue.evictionPolicy
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

const val MAX_RESIDUAL_REMOVAL_ATTEMPTS_PER_PASS = 32

data class ConfirmedDirectoryRemoval(val segmentId: String, val bytes: Long)

data class ReclaimedCacheSpace(val removals: List<ConfirmedDirectoryRemoval>) {
    val totalBytes: Long get() = removals.sumOf { it.bytes }
}

enum class JournalCacheBlockedReason {
    MEASUREMENT_FAILED,
    FREE_SPACE_FAILED,
    ARITHMETIC_OVERFLOW,
    TRANSITION_FAILED,
    NO_SAFE_ELIGIBLE_SEGMENT,
    REMOVAL_INCOMPLETE,
}

data class JournalCacheEvictionResult(
    val measuredUsageBytes: Long?,
    val measuredFreeBytes: Long?,
    val configuredLimitBytes: Long,
    val pressureRemains: Boolean,
    val durablyMarkedIds: List<String>,
    val reclaimedSpace: ReclaimedCacheSpace,
    val retryableResidualIds: List<String>,
    val refusedPathIds: List<String>,
    val blockedReason: JournalCacheBlockedReason?,
)

class JournalCacheEvictionService(
    private val spoolRoot: Path,
    private val dao: SegmentDao,
    private val limitStore: JournalCacheLimitStore,
    private val usageMeasurer: SpoolUsageMeasurer = NioSpoolUsageMeasurer(),
    private val freeSpaceProvider: SpoolFreeSpaceProvider = FileStoreFreeSpaceProvider(),
    private val directoryRemover: SpoolDirectoryRemover = NioSpoolDirectoryRemover(),
) {
    fun snapshot(): JournalCacheSnapshot = limitStore.snapshot()

    fun runPass(decidedAtEpochMs: Long): JournalCacheEvictionResult {
        val configuredLimit = limitStore.snapshot().configuredLimitBytes
        val marked = mutableListOf<String>()
        val removals = mutableListOf<ConfirmedDirectoryRemoval>()
        val residuals = linkedSetOf<String>()
        val refused = linkedSetOf<String>()
        var removalIncomplete = false

        retryResiduals(removals, residuals, refused).also { removalIncomplete = it }

        var measurement = measureOrNull()
            ?: return result(configuredLimit, null, null, true, marked, removals, residuals, refused, JournalCacheBlockedReason.MEASUREMENT_FAILED)
        var freeBytes = freeBytesOrNull()
            ?: return result(configuredLimit, measurement.totalBytes, null, true, marked, removals, residuals, refused, JournalCacheBlockedReason.FREE_SPACE_FAILED)

        while (isUnderPressure(measurement.totalBytes, freeBytes, configuredLimit)) {
            val rows = dao.segmentsByState(QueueState.UPLOADED).filterNot { it.id in refused }
            val policyResult = try {
                select(rows, measurement, freeBytes, configuredLimit, decidedAtEpochMs)
            } catch (_: ArithmeticException) {
                return result(configuredLimit, measurement.totalBytes, freeBytes, true, marked, removals, residuals, refused, JournalCacheBlockedReason.ARITHMETIC_OVERFLOW)
            }
            if (policyResult.evictions.isEmpty()) {
                val reason = if (removalIncomplete) JournalCacheBlockedReason.REMOVAL_INCOMPLETE else JournalCacheBlockedReason.NO_SAFE_ELIGIBLE_SEGMENT
                return result(configuredLimit, measurement.totalBytes, freeBytes, true, marked, removals, residuals, refused, reason)
            }

            val rowById = rows.associateBy { it.id }
            val proven = linkedMapOf<String, SegmentDirectoryProof.Proven>()
            policyResult.evictions.forEach { eviction ->
                val row = rowById[eviction.segmentId]
                val structural = row?.let { proveSegmentDirectory(spoolRoot, it) }
                if (row == null || structural !is SegmentDirectoryProof.Proven || proveManifestIdentity(structural, row) != null) {
                    refused += eviction.segmentId
                } else {
                    proven[eviction.segmentId] = structural
                }
            }
            if (proven.isEmpty()) continue

            val acceptedIds = proven.keys
            val accepted = EvictionResult(
                evictions = policyResult.evictions.filter { it.segmentId in acceptedIds },
                events = policyResult.events.filter { it.segmentId in acceptedIds },
            )
            try {
                dao.applyEvictions(accepted)
            } catch (_: Exception) {
                return result(configuredLimit, measurement.totalBytes, freeBytes, true, marked, removals, residuals, refused, JournalCacheBlockedReason.TRANSITION_FAILED)
            }
            marked += accepted.evictions.map { it.segmentId }

            accepted.evictions.forEach { eviction ->
                val path = requireNotNull(proven[eviction.segmentId]).path
                if (directoryRemover.remove(path) == DirectoryRemovalResult.ConfirmedAbsent && !Files.exists(path, NOFOLLOW_LINKS)) {
                    removals += ConfirmedDirectoryRemoval(eviction.segmentId, eviction.byteSize)
                    residuals -= eviction.segmentId
                } else {
                    residuals += eviction.segmentId
                    removalIncomplete = true
                }
            }

            measurement = measureOrNull()
                ?: return result(configuredLimit, null, null, true, marked, removals, residuals, refused, JournalCacheBlockedReason.MEASUREMENT_FAILED)
            freeBytes = freeBytesOrNull()
                ?: return result(configuredLimit, measurement.totalBytes, null, true, marked, removals, residuals, refused, JournalCacheBlockedReason.FREE_SPACE_FAILED)
        }

        return result(configuredLimit, measurement.totalBytes, freeBytes, false, marked, removals, residuals, refused, null)
    }

    private fun retryResiduals(
        removals: MutableList<ConfirmedDirectoryRemoval>,
        residuals: MutableSet<String>,
        refused: MutableSet<String>,
    ): Boolean {
        var attempts = 0
        var incomplete = false
        dao.segmentsByState(QueueState.EVICTED).forEach { row ->
            val proof = proveSegmentDirectory(spoolRoot, row)
            if (proof is SegmentDirectoryProof.Refused) {
                if (proof.reason != JournalCachePathRefusal.MISSING_DIRECTORY) refused += row.id
                return@forEach
            }
            proof as SegmentDirectoryProof.Proven
            if (attempts >= MAX_RESIDUAL_REMOVAL_ATTEMPTS_PER_PASS) {
                residuals += row.id
                return@forEach
            }
            attempts += 1
            val bytes = runCatching { usageMeasurer.measure(proof.path).totalBytes }.getOrNull()
            if (bytes == null || directoryRemover.remove(proof.path) != DirectoryRemovalResult.ConfirmedAbsent || Files.exists(proof.path, NOFOLLOW_LINKS)) {
                residuals += row.id
                incomplete = true
            } else {
                removals += ConfirmedDirectoryRemoval(row.id, bytes)
            }
        }
        return incomplete
    }

    private fun select(
        rows: List<SegmentRow>,
        measurement: SpoolUsageMeasurement,
        freeBytes: Long,
        configuredLimit: Long,
        decidedAtEpochMs: Long,
    ): EvictionResult {
        val descriptors = rows.map { row ->
            val path = spoolRoot.toAbsolutePath().normalize().resolve(row.day).resolve(row.stream).resolve(row.dirSegment).normalize()
            // An absent/unmeasured directory contributes zero; preflight will refuse it if policy selects it.
            QueueSegmentDescriptor(row.id, row.state, measurement.sealedDirectoryBytes[path] ?: 0L, row.sealedAt)
        }
        val reclaimable = descriptors.fold(0L) { sum, descriptor -> Math.addExact(sum, descriptor.byteSize) }
        val unreclaimable = Math.subtractExact(measurement.totalBytes, reclaimable)
        if (unreclaimable < 0L) throw ArithmeticException("candidate bytes exceed measured spool usage")
        val adjustedMaximum = Math.subtractExact(configuredLimit, unreclaimable)
        // Pre-check the unchanged policy's projected free-space additions so they fail closed instead of wrapping.
        val projectedFreeSpaceOverflowGuard = descriptors.fold(freeBytes) { projected, descriptor ->
            Math.addExact(projected, descriptor.byteSize)
        }
        check(projectedFreeSpaceOverflowGuard >= freeBytes)
        return evictionPolicy(
            EvictionInput(
                segments = descriptors,
                budget = EvictionBudget(adjustedMaximum, JOURNAL_CACHE_FREE_SPACE_FLOOR_BYTES, freeBytes),
                emergency = false,
                decidedAtEpochMs = decidedAtEpochMs,
            ),
        )
    }

    private fun measureOrNull(): SpoolUsageMeasurement? = runCatching { usageMeasurer.measure(spoolRoot) }.getOrNull()

    private fun freeBytesOrNull(): Long? = runCatching { freeSpaceProvider.usableBytes(spoolRoot) }
        .getOrNull()?.takeIf { it >= 0L }

    private fun isUnderPressure(usage: Long, free: Long, limit: Long): Boolean =
        usage > limit || free < JOURNAL_CACHE_FREE_SPACE_FLOOR_BYTES

    private fun result(
        limit: Long,
        usage: Long?,
        free: Long?,
        pressure: Boolean,
        marked: List<String>,
        removals: List<ConfirmedDirectoryRemoval>,
        residuals: Set<String>,
        refused: Set<String>,
        reason: JournalCacheBlockedReason?,
    ) = JournalCacheEvictionResult(
        measuredUsageBytes = usage,
        measuredFreeBytes = free,
        configuredLimitBytes = limit,
        pressureRemains = pressure,
        durablyMarkedIds = marked.toList(),
        reclaimedSpace = ReclaimedCacheSpace(removals.toList()),
        retryableResidualIds = residuals.toList(),
        refusedPathIds = refused.toList(),
        blockedReason = reason,
    )
}
