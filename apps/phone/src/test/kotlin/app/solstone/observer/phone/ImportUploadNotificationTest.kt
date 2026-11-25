// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.observer.RESUMABLE_UPLOAD_PHASE_CHECKING
import app.solstone.core.observer.RESUMABLE_UPLOAD_PHASE_PAUSED
import app.solstone.core.observer.RESUMABLE_UPLOAD_PHASE_SENDING
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportUploadNotificationTest {
    @Test
    fun notificationCopyMatchesCatalogForSendingCheckingAndPausedStates() {
        assertEquals("sending imports", ImportUploadNotification.CHANNEL_NAME)
        assertEquals("sending to your journal", ImportUploadNotification.TITLE)

        assertEquals(
            "export.zip · 1.2 GB of 4.0 GB",
            ImportUploadNotification.contentText(
                context = null,
                filename = "export.zip",
                bytesSent = 1_200_000_000L,
                bytesTotal = 4_000_000_000L,
                phase = RESUMABLE_UPLOAD_PHASE_SENDING,
            ),
        )
        assertEquals(
            "export.zip · checking file",
            ImportUploadNotification.contentText(
                context = null,
                filename = "export.zip",
                bytesSent = 4_000_000_000L,
                bytesTotal = 4_000_000_000L,
                phase = RESUMABLE_UPLOAD_PHASE_CHECKING,
            ),
        )
        assertEquals(
            "paused at 1.2 GB of 4.0 GB · waiting for your journal",
            ImportUploadNotification.contentText(
                context = null,
                filename = "export.zip",
                bytesSent = 1_200_000_000L,
                bytesTotal = 4_000_000_000L,
                phase = RESUMABLE_UPLOAD_PHASE_PAUSED,
            ),
        )
    }

    @Test
    fun scaledProgressClampsSafelyAcrossZeroAndFourGiB() {
        assertEquals(0, ImportUploadNotification.scaledProgress(0L, 0L))
        assertEquals(0, ImportUploadNotification.scaledProgress(0L, 4_294_967_296L))
        assertEquals(500, ImportUploadNotification.scaledProgress(2_147_483_648L, 4_294_967_296L))
        assertEquals(1000, ImportUploadNotification.scaledProgress(4_294_967_296L, 4_294_967_296L))
        assertEquals(1000, ImportUploadNotification.scaledProgress(5_000_000_000L, 4_294_967_296L))
    }
}
