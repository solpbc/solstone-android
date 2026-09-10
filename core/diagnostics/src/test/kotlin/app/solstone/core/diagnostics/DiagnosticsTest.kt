// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.diagnostics

import app.solstone.core.model.IdentityState
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticsTest {
    /**
     * 🔴 **The branch ORDER, and it is the requirement rather than a detail.**
     *
     * Founder, 2026-09-10: *"a source whose permission the owner declined stays `ready to set up` —
     * never a fault, because the owner has not asked for it."* Four fault branches below carry no
     * `desiredOn` gate — `permissionGranted`, `pairing == REVOKED`, `identityPersistenceOk`,
     * `storageOk` — so anything short of FIRST lets one of them claim a source nobody chose and
     * then blame the owner for it.
     *
     * ⚠ This has to be asserted at [reduce], not through `SourceRegistry.status()`. That path hands
     * an unexpressed source a hand-built neutral `SourceFacts` twin in which `permissionGranted` is
     * `true`, so every fault input is laundered before the reducer sees it and the ordering
     * requirement is **untestable from there**. Verified: swapping the first two branches left the
     * entire unit suite green.
     */
    @Test
    fun anUnexpressedWishOutranksEveryFaultBranch() {
        val faults = mapOf(
            "permission" to healthy().copy(permissionGranted = false),
            "auth revoked" to healthy().copy(pairing = PairingFact.REVOKED),
            "unpaired" to healthy().copy(pairing = PairingFact.UNPAIRED),
            "persistence" to healthy().copy(identityPersistenceOk = false),
            "storage" to healthy().copy(storageOk = false),
            "service killed" to healthy().copy(fgsHeartbeatFresh = false),
            "provider silent" to healthy().copy(providerEmitting = false),
            "rebooted" to healthy().copy(engineRunning = false),
            "type not held" to healthy().copy(foregroundTypeHeld = false),
            "start refused" to healthy().copy(startRefused = true),
            "condition" to healthy().copy(conditionNeedsAttention = true),
        )
        faults.forEach { (name, facts) ->
            // ✅ Positive control first, in the same loop: each fault really does reduce to a fault
            // when the wish IS expressed. Without this the block below would pass over a fixture
            // that had stopped injecting anything.
            assertEquals(
                SourceState.NEEDS_ATTENTION,
                reduce(facts).first,
                "$name must still be a fault for a source the owner asked for",
            )
            assertEquals(
                SourceState.READY_TO_SET_UP to ReasonCode.NONE,
                reduce(facts.copy(wishExpressed = false)),
                "$name claimed a source the owner never asked for",
            )
        }
    }

    @Test
    fun anExpressedOffIsOffAndAnUnexpressedOneIsReadyToSetUp() {
        // ⚠ The rule running the other way. An owner who turned a source off made a choice, and
        // telling them they never made it is the same defect pointing the other direction.
        assertEquals(
            SourceState.OFF to ReasonCode.NONE,
            reduce(healthy().copy(desiredOn = false)),
        )
        assertEquals(
            SourceState.READY_TO_SET_UP to ReasonCode.NONE,
            reduce(healthy().copy(desiredOn = false, wishExpressed = false)),
        )
        // And a healthy, expressed, desired-on source is untouched by any of this.
        assertEquals(SourceState.ON to ReasonCode.NONE, reduce(healthy()))
    }

    @Test
    fun reduceMapsFailureFactsInPrecedenceOrder() {
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.PERMISSION_REVOKED, reduce(healthy().copy(permissionGranted = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.FOREGROUND_TYPE_NOT_HELD, reduce(healthy().copy(foregroundTypeHeld = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.FOREGROUND_START_NOT_ALLOWED, reduce(healthy().copy(startRefused = true)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.SERVICE_KILLED, reduce(healthy().copy(fgsHeartbeatFresh = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.PERSISTENCE_FAILED, reduce(healthy().copy(identityPersistenceOk = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.REBOOTED, reduce(healthy().copy(engineRunning = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.UNPAIRED, reduce(healthy().copy(pairing = PairingFact.UNPAIRED)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.STORAGE_FULL, reduce(healthy().copy(storageOk = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.PROVIDER_SILENT, reduce(healthy().copy(providerEmitting = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.AUTH_REVOKED, reduce(healthy().copy(pairing = PairingFact.REVOKED)))
    }

    @Test
    fun foregroundTypeNotHeldReducesToNeedsAttentionForegroundTypeNotHeld() {
        assertEquals(
            SourceState.NEEDS_ATTENTION to ReasonCode.FOREGROUND_TYPE_NOT_HELD,
            reduce(healthy().copy(foregroundTypeHeld = false)),
        )
        assertEquals(
            SourceState.OFF to ReasonCode.NONE,
            reduce(healthy().copy(desiredOn = false, foregroundTypeHeld = false)),
        )
        assertEquals(
            SourceState.NEEDS_ATTENTION to ReasonCode.PERMISSION_REVOKED,
            reduce(healthy().copy(permissionGranted = false, foregroundTypeHeld = false)),
        )
    }

    @Test
    fun startRefusedReducesToNeedsAttentionForegroundStartNotAllowed() {
        assertEquals(
            SourceState.NEEDS_ATTENTION to ReasonCode.FOREGROUND_START_NOT_ALLOWED,
            reduce(healthy().copy(startRefused = true)),
        )
        assertEquals(
            SourceState.OFF to ReasonCode.NONE,
            reduce(healthy().copy(desiredOn = false, startRefused = true)),
        )
        assertEquals(
            SourceState.NEEDS_ATTENTION to ReasonCode.PERMISSION_REVOKED,
            reduce(healthy().copy(permissionGranted = false, startRefused = true)),
        )
        assertEquals(
            SourceState.NEEDS_ATTENTION to ReasonCode.FOREGROUND_TYPE_NOT_HELD,
            reduce(healthy().copy(foregroundTypeHeld = false, startRefused = true)),
        )
    }

    @Test
    fun desiredOnWithoutRuntimeFactsNeedsAttention() {
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.REBOOTED, reduce(healthy().copy(engineRunning = false)))
    }

    @Test
    fun desiredOnBeforeStartIsSettingUp() {
        assertEquals(
            SourceState.SETTING_UP to ReasonCode.NONE,
            reduce(healthy().copy(engineRunning = false, engineStartIssued = false)),
        )
    }

    @Test
    fun sourceAttentionUsesSpecificReducerBranches() {
        assertEquals(
            SourceState.NEEDS_ATTENTION to ReasonCode.STORAGE_FULL,
            reduce(healthy().copy(storageOk = false, conditionNeedsAttention = true)),
        )
        assertEquals(
            SourceState.NEEDS_ATTENTION to ReasonCode.PROVIDER_SILENT,
            reduce(healthy().copy(conditionNeedsAttention = true)),
        )
    }

    @Test
    fun reduceMapsS1HonestStateFactsIndividually() {
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.PERMISSION_REVOKED, reduce(healthy().copy(permissionGranted = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.SERVICE_KILLED, reduce(healthy().copy(fgsHeartbeatFresh = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.PROVIDER_SILENT, reduce(healthy().copy(providerEmitting = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.STORAGE_FULL, reduce(healthy().copy(storageOk = false)))
    }

    @Test
    fun aStaleHeartbeatWithNoStartEvidenceIsSettingUpRatherThanAKill() {
        // Fresh install, owner still working through the runtime permission dialogs: intake had
        // never been started, so there was no beat and no start request to read. The reducer took
        // that absence for SERVICE_KILLED, and source detail told the owner "intake was stopped by
        // the system" with a "start intake again" button, under a `setting up` verdict.
        val neverStarted = healthy().copy(
            fgsHeartbeatFresh = false,
            fgsStartEvidence = false,
            engineRunning = false,
            engineStartIssued = false,
        )

        assertEquals(SourceState.SETTING_UP to ReasonCode.NONE, reduce(neverStarted))
        // Unpaired is the honest reason in that window, and it is no longer masked.
        assertEquals(
            SourceState.NEEDS_ATTENTION to ReasonCode.UNPAIRED,
            reduce(neverStarted.copy(pairing = PairingFact.UNPAIRED)),
        )
        // Control: the same stale heartbeat WITH start evidence is still a kill.
        assertEquals(
            SourceState.NEEDS_ATTENTION to ReasonCode.SERVICE_KILLED,
            reduce(neverStarted.copy(fgsStartEvidence = true)),
        )
    }

    @Test
    fun aProviderThatWasNeverStartedIsNotSilent() {
        val neverStarted = healthy().copy(
            providerEmitting = false,
            engineRunning = false,
            engineStartIssued = false,
        )

        assertEquals(SourceState.SETTING_UP to ReasonCode.NONE, reduce(neverStarted))
        // Control: a provider that did start and then went quiet is still silent.
        assertEquals(
            SourceState.NEEDS_ATTENTION to ReasonCode.PROVIDER_SILENT,
            reduce(neverStarted.copy(engineStartIssued = true, engineRunning = true)),
        )
    }

    @Test
    fun reduceMapsOffAndHealthyOn() {
        assertEquals(SourceState.OFF to ReasonCode.NONE, reduce(healthy().copy(desiredOn = false)))
        assertEquals(SourceState.ON to ReasonCode.NONE, reduce(healthy()))
    }

    @Test
    fun providerEmissionAndSilencedAreIndependentFactsInReduce() {
        assertEquals(
            SourceState.ON to ReasonCode.NONE,
            reduce(healthy().copy(providerEmitting = true, silenced = SilencedFact.NOT_SILENCED)),
        )
        assertEquals(
            SourceState.PAUSED to ReasonCode.NONE,
            reduce(healthy().copy(providerEmitting = true, silenced = SilencedFact.SILENCED)),
        )
        assertEquals(
            SourceState.NEEDS_ATTENTION to ReasonCode.PROVIDER_SILENT,
            reduce(healthy().copy(providerEmitting = false, silenced = SilencedFact.NOT_SILENCED)),
        )
        assertEquals(
            SourceState.NEEDS_ATTENTION to ReasonCode.PROVIDER_SILENT,
            reduce(healthy().copy(providerEmitting = false, silenced = SilencedFact.SILENCED)),
        )
        assertEquals(
            SourceState.ON to ReasonCode.NONE,
            reduce(healthy().copy(providerEmitting = true, silenced = SilencedFact.UNKNOWN)),
        )
    }

    @Test
    fun pausedFactDoesNotCollapseSilencedUnknown() {
        assertEquals(
            SourceState.PAUSED to ReasonCode.NONE,
            reduce(healthy().copy(silenced = SilencedFact.UNKNOWN, paused = true)),
        )
        assertEquals(
            SourceState.ON to ReasonCode.NONE,
            reduce(healthy().copy(silenced = SilencedFact.UNKNOWN, paused = false)),
        )
    }

    @Test
    fun reduceMapsPairingFacts() {
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.UNPAIRED, reduce(healthy().copy(pairing = PairingFact.UNPAIRED)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.AUTH_REVOKED, reduce(healthy().copy(pairing = PairingFact.REVOKED)))
    }

    @Test
    fun pairingFactOfClassifiesEachCombination() {
        assertEquals(
            PairingFact.UNPAIRED,
            pairingFactOf(credentialPresent = false, endpointPresent = false, relayOriginPresent = false, identityState = null),
        )
        assertEquals(
            PairingFact.REVOKED,
            pairingFactOf(
                credentialPresent = true,
                endpointPresent = false,
                relayOriginPresent = true,
                identityState = IdentityState.REVOKED,
            ),
        )
        assertEquals(
            PairingFact.PAIRED,
            pairingFactOf(
                credentialPresent = true,
                endpointPresent = true,
                relayOriginPresent = false,
                identityState = IdentityState.PAIRED,
            ),
        )
        assertEquals(
            PairingFact.PAIRED,
            pairingFactOf(
                credentialPresent = true,
                endpointPresent = false,
                relayOriginPresent = true,
                identityState = IdentityState.PAIRED,
            ),
        )
        assertEquals(
            PairingFact.UNPAIRED,
            pairingFactOf(
                credentialPresent = true,
                endpointPresent = false,
                relayOriginPresent = false,
                identityState = IdentityState.PAIRED,
            ),
        )
        assertEquals(
            PairingFact.UNPAIRED,
            pairingFactOf(
                credentialPresent = false,
                endpointPresent = true,
                relayOriginPresent = false,
                identityState = IdentityState.PAIRED,
            ),
        )
        assertEquals(
            PairingFact.UNPAIRED,
            pairingFactOf(
                credentialPresent = false,
                endpointPresent = false,
                relayOriginPresent = false,
                identityState = IdentityState.UNPAIRED,
            ),
        )
    }

    private fun healthy() = SourceFacts(
        desiredOn = true,
        engineRunning = true,
        permissionGranted = true,
        fgsHeartbeatFresh = true,
        providerEmitting = true,
        storageOk = true,
        pairing = PairingFact.PAIRED,
        silenced = SilencedFact.NOT_SILENCED,
    )
}
