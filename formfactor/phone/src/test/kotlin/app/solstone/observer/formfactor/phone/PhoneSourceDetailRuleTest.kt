// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PhoneSourceDetailRuleTest {
    @Test
    fun sameSnapshotUsesEachSourceOwnReason() {
        val snapshot = SourcesReadModel(
            observer = observer(ReasonCode.SERVICE_KILLED),
            sources = listOf(
                source("audio", ReasonCode.PERMISSION_REVOKED),
                source("location", ReasonCode.STORAGE_FULL),
            ),
        )

        val reasons = snapshot.sources.associate { status ->
            status.sourceId to resolveSourceDetailReason(status, snapshot.observer)
        }

        assertEquals(ReasonCode.PERMISSION_REVOKED, reasons["audio"])
        assertEquals(ReasonCode.STORAGE_FULL, reasons["location"])
    }

    @Test
    fun rulesMatchTheRuledTable() {
        val expected = listOf(
            ExpectedRule(
                ReasonCode.PERMISSION_REVOKED,
                "permissions needed",
                "grant permissions",
                false,
                SourceDetailActionKind.RETRY,
            ),
            ExpectedRule(
                ReasonCode.AUTH_REVOKED,
                "access to your journal was revoked",
                "pair again",
                false,
                SourceDetailActionKind.RETRY,
            ),
            ExpectedRule(
                ReasonCode.SERVICE_KILLED,
                "observing was stopped by the system",
                "start observing again",
                true,
                SourceDetailActionKind.RETRY,
            ),
            ExpectedRule(
                ReasonCode.STORAGE_FULL,
                "storage is full",
                "manage local storage",
                false,
                SourceDetailActionKind.RETRY,
            ),
            ExpectedRule(
                ReasonCode.UNPAIRED,
                "not paired with your journal",
                "connect a journal",
                false,
                SourceDetailActionKind.CONNECT_JOURNAL,
            ),
            ExpectedRule(ReasonCode.PROVIDER_SILENT, "nothing has come in recently", null, false),
            ExpectedRule(
                ReasonCode.REBOOTED,
                "this device restarted and observing didn't resume on its own",
                "start observing again",
                true,
                SourceDetailActionKind.RETRY,
            ),
        )

        expected.forEach { expectedRule ->
            val actual = sourceDetailRule(expectedRule.reason)
            assertEquals(expectedRule.diagnosis, actual.diagnosis, expectedRule.reason.name)
            assertEquals(expectedRule.action, actual.action?.label, expectedRule.reason.name)
            assertEquals(expectedRule.retryHonest, actual.retryHonest, expectedRule.reason.name)
            assertEquals(expectedRule.actionKind, actual.action?.kind, expectedRule.reason.name)
        }
    }

    @Test
    fun rewordedRulesAreNotLegacyHarnessCopy() {
        // These superseded shipped strings must stay divergent from the phone rules.
        assertNotEquals("phone storage is full", sourceDetailRule(ReasonCode.STORAGE_FULL).diagnosis)
        assertNotEquals("nothing observed recently", sourceDetailRule(ReasonCode.PROVIDER_SILENT).diagnosis)
        assertNotEquals("access was revoked - pair again", sourceDetailRule(ReasonCode.AUTH_REVOKED).diagnosis)
        assertNotEquals("restart observing after reboot", sourceDetailRule(ReasonCode.REBOOTED).diagnosis)
    }

    @Test
    fun observerFallbackRequiresWishOnAndDeviceLevelReason() {
        val ownReason = source("audio", ReasonCode.PERMISSION_REVOKED)
        assertEquals(
            ReasonCode.PERMISSION_REVOKED,
            resolveSourceDetailReason(ownReason, observer(ReasonCode.SERVICE_KILLED)),
        )

        val wishOff = source("audio", ReasonCode.NONE, SourceWish.Off)
        assertEquals(
            ReasonCode.NONE,
            resolveSourceDetailReason(wishOff, observer(ReasonCode.AUTH_REVOKED)),
        )

        listOf(
            ReasonCode.UNPAIRED,
            ReasonCode.AUTH_REVOKED,
            ReasonCode.SERVICE_KILLED,
        ).forEach { deviceReason ->
            assertEquals(
                deviceReason,
                resolveSourceDetailReason(source("audio", ReasonCode.NONE), observer(deviceReason)),
            )
        }

        assertEquals(
            ReasonCode.NONE,
            resolveSourceDetailReason(source("audio", ReasonCode.NONE), observer(ReasonCode.PERMISSION_REVOKED)),
        )
    }
}

private data class ExpectedRule(
    val reason: ReasonCode,
    val diagnosis: String,
    val action: String?,
    val retryHonest: Boolean,
    val actionKind: SourceDetailActionKind? = null,
)

private fun source(
    sourceId: String,
    reason: ReasonCode,
    wish: SourceWish = SourceWish.On,
) = SourceStatus(sourceId, wish, SourceState.NEEDS_ATTENTION, reason)

private fun observer(reason: ReasonCode) = ObserverStatus(SourceState.NEEDS_ATTENTION, reason)
