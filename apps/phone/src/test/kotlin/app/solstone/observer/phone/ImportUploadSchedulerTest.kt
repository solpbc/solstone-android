// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportUploadSchedulerTest {
    @Test
    fun decideScheduleRoutePreservesUidtOnValidatedNetworksAndUsesLocalFallbackOnUnvalidatedLan() {
        // 1. API 35 with validated network + large/resumable pending import -> UIDT Job 7401.
        assertEquals(
            ImportScheduleRoute.UIDT_JOB,
            ImportUploadScheduler.decideScheduleRoute(
                sdkInt = 35,
                hasValidatedNetwork = true,
                hasPending = true,
                hasLargeOrResumable = true,
                isActive = false,
                isResumeOnly = false,
            ),
        )
        assertEquals(
            ImportScheduleRoute.UIDT_JOB,
            ImportUploadScheduler.decideScheduleRoute(
                sdkInt = 35,
                hasValidatedNetwork = true,
                hasPending = true,
                hasLargeOrResumable = true,
                isActive = false,
                isResumeOnly = true,
            ),
        )

        // 2. API 35 on Wi-Fi / LAN without validated internet -> single-owner local fallback
        //    (avoids both JobScheduler NETWORK_TYPE_ANY and WorkManager NetworkType.CONNECTED stalls).
        assertEquals(
            ImportScheduleRoute.LOCAL_FALLBACK,
            ImportUploadScheduler.decideScheduleRoute(
                sdkInt = 35,
                hasValidatedNetwork = false,
                hasPending = true,
                hasLargeOrResumable = true,
                isActive = false,
                isResumeOnly = false,
            ),
        )
        assertEquals(
            ImportScheduleRoute.LOCAL_FALLBACK,
            ImportUploadScheduler.decideScheduleRoute(
                sdkInt = 35,
                hasValidatedNetwork = false,
                hasPending = true,
                hasLargeOrResumable = true,
                isActive = false,
                isResumeOnly = true,
            ),
        )

        // 3. Single-owner guarantee: when a runner is already active, both scheduleForImports
        //    and resumePendingLargeImports only wake it and never start a duplicate transfer.
        for (validated in listOf(true, false)) {
            for (resumeOnly in listOf(false, true)) {
                assertEquals(
                    ImportScheduleRoute.WAKE_ACTIVE,
                    ImportUploadScheduler.decideScheduleRoute(
                        sdkInt = 35,
                        hasValidatedNetwork = validated,
                        hasPending = true,
                        hasLargeOrResumable = true,
                        isActive = true,
                        isResumeOnly = resumeOnly,
                    ),
                )
            }
        }

        // 4. API 33 validated preserves WorkManager on schedule and no-ops on resume;
        //    API 33 unvalidated uses local fallback so direct-LAN imports do not stall on WorkManager.
        assertEquals(
            ImportScheduleRoute.WORK_MANAGER,
            ImportUploadScheduler.decideScheduleRoute(
                sdkInt = 33,
                hasValidatedNetwork = true,
                hasPending = true,
                hasLargeOrResumable = true,
                isActive = false,
                isResumeOnly = false,
            ),
        )
        assertEquals(
            ImportScheduleRoute.NONE,
            ImportUploadScheduler.decideScheduleRoute(
                sdkInt = 33,
                hasValidatedNetwork = true,
                hasPending = true,
                hasLargeOrResumable = true,
                isActive = false,
                isResumeOnly = true,
            ),
        )
        assertEquals(
            ImportScheduleRoute.LOCAL_FALLBACK,
            ImportUploadScheduler.decideScheduleRoute(
                sdkInt = 33,
                hasValidatedNetwork = false,
                hasPending = true,
                hasLargeOrResumable = false,
                isActive = false,
                isResumeOnly = false,
            ),
        )

        // 5. Idle queue schedules nothing.
        assertEquals(
            ImportScheduleRoute.NONE,
            ImportUploadScheduler.decideScheduleRoute(
                sdkInt = 35,
                hasValidatedNetwork = false,
                hasPending = false,
                hasLargeOrResumable = false,
                isActive = false,
                isResumeOnly = false,
            ),
        )
    }
}
