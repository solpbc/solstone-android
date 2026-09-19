// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant

internal data class PhoneProblemReport(
    val id: String,
    val savedAt: String,
)

/** Local, bounded reports the owner explicitly saves from the device pane. */
internal class PhoneProblemReportStore(private val directory: File) {
    fun list(): List<PhoneProblemReport> =
        allReports()
            .take(MAX_REPORTS)

    private fun allReports(): List<PhoneProblemReport> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".txt") }
            .orEmpty()
            .sortedByDescending(File::getName)
            .map { file ->
                PhoneProblemReport(
                    id = file.name,
                    savedAt = file.name.removePrefix("report-").removeSuffix(".txt").replace('_', ':'),
                )
            }

    fun save(body: String, now: Instant = Instant.now()): List<PhoneProblemReport> {
        Files.createDirectories(directory.toPath())
        val stamp = now.toString().replace(':', '_')
        val target = directory.resolve("report-$stamp.txt").toPath()
        val temp = Files.createTempFile(directory.toPath(), "report-", ".tmp")
        try {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            Files.write(temp, if (bytes.size <= MAX_REPORT_BYTES) bytes else bytes.copyOf(MAX_REPORT_BYTES))
            try {
                Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, target, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        allReports().drop(MAX_REPORTS).forEach { report ->
            runCatching { Files.deleteIfExists(directory.resolve(report.id).toPath()) }
        }
        return list()
    }

    private companion object {
        const val MAX_REPORTS = 10
        const val MAX_REPORT_BYTES = 256_000
    }
}
