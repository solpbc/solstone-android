// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import app.solstone.core.segment.sha256
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class RecoveryScanner(private val baseDir: Path) {
    fun scan(nowEpochMs: Long = 0L): List<RecoveryAction> {
        val actions = mutableListOf<RecoveryAction>()
        collect(baseDir.resolve(".draft"), actions) { segmentDir -> actionFor(segmentDir, nowEpochMs) }
        collect(baseDir.resolve(".parked"), actions) { segmentDir ->
            RecoveryAction.Parked(draftDir = segmentDir, atEpochMs = nowEpochMs)
        }
        return actions.sortedBy { it.draftDir().toString() }
    }

    private fun collect(root: Path, actions: MutableList<RecoveryAction>, action: (Path) -> RecoveryAction) {
        if (!Files.isDirectory(root)) return
        Files.newDirectoryStream(root).use { days ->
            days.filter(Files::isDirectory).forEach { dayDir ->
                Files.newDirectoryStream(dayDir).use { streams ->
                    streams.filter(Files::isDirectory).forEach { streamDir ->
                        Files.newDirectoryStream(streamDir).use { segments ->
                            segments.filter(Files::isDirectory).forEach { segmentDir ->
                                actions += action(segmentDir)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun actionFor(draftDir: Path, nowEpochMs: Long): RecoveryAction {
        val manifestPath = draftDir.resolve("manifest")
        if (!Files.isRegularFile(manifestPath)) {
            return discard(draftDir, nowEpochMs, "missing manifest")
        }

        val parsed = runCatching {
            parseManifest(String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8))
        }.getOrElse {
            return discard(draftDir, nowEpochMs, "malformed manifest")
        }

        val invalidFile = parsed.manifest.files.firstOrNull { file ->
            val payload = draftDir.resolve(file.name)
            !Files.isRegularFile(payload) ||
                Files.size(payload) != file.byteSize ||
                sha256(payload) != file.sha256
        }
        if (invalidFile != null) {
            return discard(draftDir, nowEpochMs, "invalid payload ${invalidFile.name}")
        }

        val dayDir = draftDir.parent.parent
        val streamDir = draftDir.parent
        val finalDir = baseDir
            .resolve(dayDir.fileName.toString())
            .resolve(streamDir.fileName.toString())
            .resolve(draftDir.fileName.toString())
        return RecoveryAction.Finalize(
            draftDir = draftDir,
            finalDir = finalDir,
            parsedManifest = parsed,
        )
    }

    private fun discard(draftDir: Path, nowEpochMs: Long, detail: String): RecoveryAction.Discard =
        RecoveryAction.Discard(
            draftDir = draftDir,
            event = SpoolRecoveryEvent(
                kind = "partial_segment",
                atEpochMs = nowEpochMs,
                detail = detail,
            ),
        )
}

private fun RecoveryAction.draftDir(): Path =
    when (this) {
        is RecoveryAction.Discard -> draftDir
        is RecoveryAction.Finalize -> draftDir
        is RecoveryAction.Parked -> draftDir
    }

sealed class RecoveryAction {
    data class Finalize(
        val draftDir: Path,
        val finalDir: Path,
        val parsedManifest: ParsedManifest,
    ) : RecoveryAction()

    data class Discard(
        val draftDir: Path,
        val event: SpoolRecoveryEvent,
    ) : RecoveryAction()

    /** A draft parked because its loss could not be recorded. Recovery never seals it. */
    data class Parked(
        val draftDir: Path,
        val atEpochMs: Long,
    ) : RecoveryAction()
}

data class SpoolRecoveryEvent(
    val kind: String,
    val atEpochMs: Long,
    val detail: String?,
)
