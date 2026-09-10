// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.reasonDiagnosis
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
                SourceDetailActionKind.GRANT_PERMISSIONS,
            ),
            ExpectedRule(
                ReasonCode.AUTH_REVOKED,
                "access to your journal was revoked",
                "pair again",
                false,
                SourceDetailActionKind.CONNECT_JOURNAL,
            ),
            ExpectedRule(
                ReasonCode.SERVICE_KILLED,
                "intake was stopped by the system",
                "start intake again",
                true,
                SourceDetailActionKind.RETRY,
            ),
            ExpectedRule(
                ReasonCode.STORAGE_FULL,
                "storage is full",
                "manage local storage",
                false,
                SourceDetailActionKind.MANAGE_LOCAL_STORAGE,
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
                "this device restarted and intake didn't resume on its own",
                "start intake again",
                true,
                SourceDetailActionKind.RETRY,
            ),
            ExpectedRule(
                ReasonCode.PERSISTENCE_FAILED,
                "couldn't save journal access on this phone",
                "try again",
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
    fun supersededHarnessCopyIsGoneFromTheSharedTable() {
        // ⚠ This used to assert the phone rules stayed *divergent* from the legacy harness's own
        // copy — a premise that described the defect rather than the fix. There is one table now,
        // so these superseded strings must be absent from IT, not merely different from it.
        listOf(
            "phone storage is full",
            "nothing observed recently",
            "access was revoked - pair again",
            "restart observing after reboot",
            "journal access wasn't saved",
            "observing is turned off",
            "observing was stopped by the system",
            "open sol to resume observing",
            "intake restart needed",
        ).forEach { retired ->
            assertFalse(
                ReasonCode.entries.any { reasonDiagnosis(it) == retired },
                retired,
            )
        }
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
            ReasonCode.PERSISTENCE_FAILED,
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

    /**
     * The invariant that makes the duplication un-reintroducible.
     *
     * 🔴 Two renderers of this table each held their own copy of the strings. They agreed, which is
     * why nobody saw the duplication — until a sweep corrected one and the other kept a deleted
     * product name. ⛔ A renderer supplies its own *action*; the diagnosis has exactly one source.
     */
    @Test
    fun theDetailRuleTakesItsDiagnosisFromTheOneSharedTable() {
        ReasonCode.entries.forEach { reason ->
            assertEquals(reasonDiagnosis(reason), sourceDetailRule(reason).diagnosis, reason.name)
        }
        // Positive control: the table is not uniformly null, so agreement above is a comparison of
        // real values rather than two absences matching.
        assertEquals(
            "intake was stopped by the system",
            sourceDetailRule(ReasonCode.SERVICE_KILLED).diagnosis,
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
