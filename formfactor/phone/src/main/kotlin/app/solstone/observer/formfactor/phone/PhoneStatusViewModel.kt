// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.SourcesReader
import java.time.ZoneId

class PhoneStatusViewModel(
    private val read: () -> HarnessBacklogStatus,
    private val sources: SourcesReader,
    private val asyncLoad: AsyncLoad,
    private val awaitingMarkConfirmation: () -> Boolean,
    private val recoveryCompleted: () -> Boolean,
    private val audioAwaitingCustody: () -> Boolean,
    private val gapStatus: () -> PhoneGapStatus = { PhoneGapStatus() },
    private val markSeen: (Set<String>, Set<String>) -> Boolean = { _, _ -> false },
    private val onGapSeen: () -> Unit = {},
    private val capturedStatusState: LoadState<PhoneStatusSnapshot>? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    var statusState: LoadState<PhoneStatusSnapshot> by androidx.compose.runtime.mutableStateOf(LoadState.Loading)
        private set

    var nowEpochMs by mutableLongStateOf(statusWallNow())
        private set

    private var requestedGeneration = 0L
    private var readInFlight = false
    private var trailingRefreshRequested = false
    private var hasSeenHostResume = false
    private var contentVisible = false
    private var visitActive = false
    private var visitLossIds: Set<String> = emptySet()
    private var visitUnknownKeys: Set<String> = emptySet()

    init {
        refresh()
    }

    fun refresh() {
        val generation = ++requestedGeneration
        val captured = capturedStatusState
        if (captured != null) {
            statusState = captured
            return
        }
        if (readInFlight) {
            trailingRefreshRequested = true
            return
        }
        startRead(generation)
    }

    fun onHostResumed() {
        if (hasSeenHostResume) {
            refresh()
        } else {
            hasSeenHostResume = true
        }
        beginVisit()
    }

    /** Moves the time the pill reads. Does not read status again. */
    fun onNow(now: Long) {
        nowEpochMs = now
    }

    /**
     * Remembers whether status content is on screen. Closing the sheet does not end the visit.
     * A visit starts from here, from a load that lands while the content is open, or from resume.
     */
    fun onStatusForeground(visible: Boolean) {
        contentVisible = visible
        if (visible) beginVisit()
    }

    /**
     * Marks exactly the loss ids and unknown keys on screen when the gate is open.
     * A later loss is not in that set. A closed gate still becomes a visit and marks nothing.
     */
    private fun beginVisit() {
        if (!contentVisible || visitActive) return
        val loaded = statusState as? LoadState.Loaded ?: return
        val model = loaded.value.status
        if (!model.gateOpen) {
            visitActive = true
            visitLossIds = emptySet()
            visitUnknownKeys = emptySet()
            return
        }
        visitLossIds = displayedGapLossIds(model, nowEpochMs, zone)
        visitUnknownKeys = model.unseenUnknownKeys.toSet()
        visitActive = true
        if (visitLossIds.isEmpty() && visitUnknownKeys.isEmpty()) return
        if (!markSeen(visitLossIds, visitUnknownKeys)) return
        val remainingKeys = model.unseenUnknownKeys.filter { it !in visitUnknownKeys }
        statusState = LoadState.Loaded(
            loaded.value.copy(
                status = model.copy(
                    gapLosses = model.gapLosses.map { loss ->
                        if (loss.id in visitLossIds) loss.copy(seen = true) else loss
                    },
                    unseenUnknownKeys = remainingKeys,
                    unknownUnseen = remainingKeys.isNotEmpty(),
                ),
            ),
        )
        onGapSeen()
    }

    /** A configuration change keeps the visit. Leaving the screen ends it. */
    fun onHostStop(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        visitActive = false
        visitLossIds = emptySet()
        visitUnknownKeys = emptySet()
    }

    /** The pane keeps the visit's losses unseen until the visit ends. The pill reads [statusState]. */
    fun paneState(): LoadState<PhoneStatusSnapshot> {
        val current = statusState
        if (!visitActive) return current
        val loaded = current as? LoadState.Loaded ?: return current
        val status = loaded.value.status
        return LoadState.Loaded(
            loaded.value.copy(
                status = status.copy(
                    gapLosses = status.gapLosses.map { loss ->
                        if (loss.id in visitLossIds) loss.copy(seen = false) else loss
                    },
                ),
            ),
        )
    }

    /**
     * Shows a status the app already read in the background, without reading it again. The screen
     * keeps what it shows until this lands, so a routine refresh never blinks through loading.
     */
    fun publish(backlog: HarnessBacklogStatus) {
        if (capturedStatusState != null || readInFlight) return
        val generation = ++requestedGeneration
        asyncLoad.load({ snapshotOf(backlog) }) { incoming ->
            if (incoming is LoadState.Loading) return@load
            if (generation == requestedGeneration) {
                statusState = incoming
                if (incoming is LoadState.Loaded) beginVisit()
            }
        }
    }

    private fun startRead(generation: Long) {
        readInFlight = true
        asyncLoad.load({ snapshotOf(read()) }) { incoming ->
            if (incoming is LoadState.Loading) {
                if (generation == requestedGeneration) statusState = incoming
                return@load
            }
            readInFlight = false
            if (generation == requestedGeneration) {
                statusState = incoming
                if (incoming is LoadState.Loaded) beginVisit()
            }
            if (trailingRefreshRequested) {
                trailingRefreshRequested = false
                startRead(requestedGeneration)
            }
        }
    }

    private fun snapshotOf(backlog: HarnessBacklogStatus): PhoneStatusSnapshot = phoneStatusSnapshotOf(
        backlog = backlog,
        registered = sources.snapshot().sources,
        awaitingMarkConfirmation = awaitingMarkConfirmation(),
        recoveryCompleted = recoveryCompleted(),
        audioAwaitingCustody = audioAwaitingCustody(),
        gaps = gapStatus(),
    )
}

/**
 * Status content is the open sheet, or the wide paired detail beside the deck.
 * The pairing overlay covers both, so neither is a visit while it is open.
 */
fun phoneStatusContentVisible(
    sheetOpen: Boolean,
    pairingOpen: Boolean,
    wide: Boolean,
    pairedDetail: Boolean,
): Boolean = !pairingOpen && (sheetOpen || (wide && pairedDetail))
