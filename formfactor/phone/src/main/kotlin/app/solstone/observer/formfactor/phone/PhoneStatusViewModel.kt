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
    private val clock: () -> Long = { statusWallNow() },
) : ViewModel() {
    var statusState: LoadState<PhoneStatusSnapshot> by androidx.compose.runtime.mutableStateOf(LoadState.Loading)
        private set

    var nowEpochMs by mutableLongStateOf(clock())
        private set

    private var requestedGeneration = 0L
    private var readInFlight = false
    private var trailingRefreshRequested = false
    private var hasSeenHostResume = false
    private var contentVisible = false
    private var hostInForeground = false
    private var visitActive = false
    private var retainVisit = false
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

    /** The activity is started. Refreshes the clock. A visit still waits for a showing or a resume. */
    fun onHostStarted() {
        nowEpochMs = clock()
        hostInForeground = true
    }

    fun onHostResumed() {
        nowEpochMs = clock()
        hostInForeground = true
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
     * A visit is one continuous showing of the status content while the app is in the foreground.
     * It starts when the content becomes visible while started, or when the host resumes with it
     * visible. Hiding the content ends it, except across a configuration change, which keeps the
     * same showing. A loss that appears during the visit is shown and not marked; the next visit
     * marks it.
     */
    fun onStatusForeground(visible: Boolean) {
        if (!visible) {
            contentVisible = false
            if (!retainVisit) endVisit()
            return
        }
        contentVisible = true
        retainVisit = false
        beginVisit()
    }

    /**
     * Marks exactly the unseen in-window losses on screen when this showing starts.
     * The clock is read before that set is chosen. The mark itself runs off the caller thread.
     * Nothing is marked while the host is stopped. An empty showing marks nothing.
     * A closed gate still becomes a visit and marks nothing.
     */
    private fun beginVisit() {
        nowEpochMs = clock()
        if (!hostInForeground || !contentVisible || visitActive) return
        val loaded = statusState as? LoadState.Loaded ?: return
        val model = loaded.value.status
        if (!model.gateOpen) {
            visitActive = true
            visitLossIds = emptySet()
            visitUnknownKeys = emptySet()
            return
        }
        val lossIds = displayedGapLossIds(model, nowEpochMs, zone)
        val unknownKeys = model.unseenUnknownKeys.toSet()
        visitLossIds = lossIds
        visitUnknownKeys = unknownKeys
        visitActive = true
        if (lossIds.isEmpty() && unknownKeys.isEmpty()) return
        asyncLoad.load({ markSeen(lossIds, unknownKeys) }) { incoming ->
            if (incoming !is LoadState.Loaded || incoming.value != true) return@load
            if (!visitActive || visitLossIds != lossIds || visitUnknownKeys != unknownKeys) return@load
            val current = statusState as? LoadState.Loaded ?: return@load
            val currentModel = current.value.status
            val remainingKeys = currentModel.unseenUnknownKeys.filter { it !in unknownKeys }
            statusState = LoadState.Loaded(
                current.value.copy(
                    status = currentModel.copy(
                        gapLosses = currentModel.gapLosses.map { loss ->
                            if (loss.id in lossIds) loss.copy(seen = true) else loss
                        },
                        unseenUnknownKeys = remainingKeys,
                        unknownUnseen = remainingKeys.isNotEmpty(),
                    ),
                ),
            )
            onGapSeen()
        }
    }

    /**
     * A real stop ends the visit. A configuration change keeps it, and the losses it displayed
     * stay displayed. Nothing is marked while the host is stopped for a real stop.
     */
    fun onHostStop(changingConfigurations: Boolean) {
        if (changingConfigurations) {
            retainVisit = true
            return
        }
        hostInForeground = false
        retainVisit = false
        endVisit()
    }

    private fun endVisit() {
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
 * Status content is the open sheet, or the paired detail actually drawn in the foreground.
 * A route on top of the detail stack, or a split that is not drawn, is not a visit.
 * The pairing overlay covers both.
 */
fun phoneStatusContentVisible(
    sheetOpen: Boolean,
    pairingOpen: Boolean,
    splitDrawn: Boolean,
    statusDetailForeground: Boolean,
): Boolean = !pairingOpen && (sheetOpen || (splitDrawn && statusDetailForeground))

/** The activity's start, resume, and stop for the status clock and the visit. */
class PhoneStatusHost(private val status: PhoneStatusViewModel) {
    fun onStart() {
        status.onHostStarted()
    }

    fun onResume() {
        status.onHostResumed()
    }

    fun onStop(changingConfigurations: Boolean) {
        status.onHostStop(changingConfigurations)
    }
}
