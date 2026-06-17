// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.solstone.core.model.QueueState
import app.solstone.core.sources.LOCATION_STREAM
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.HarnessEvidenceSegment
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.harness.LoadState

class PhoneHarnessUi(
    private val context: Context,
    private val controller: HarnessController,
    private val permissionRequester: () -> Unit,
    private val asyncLoad: AsyncLoad,
    private val onEvidenceLoaded: () -> Unit = {},
    private val onSyncLoaded: () -> Unit = {},
) {
    private val container = FrameLayout(context)

    fun view(): View {
        showMenu()
        return container
    }

    fun showMenu() {
        setScreen {
            button("Permissions") { showPermissions() }
            button("Scan pair QR") { showScanPairQr() }
            button("PL status probe") { showPlStatusProbe() }
            button("Start/stop observing") { showStartStop() }
            button("Status + queue/sync") { showStatusQueueSync() }
            button("Evidence + export") { showEvidenceExport() }
        }
    }

    fun showPermissions() {
        setScreen {
            text(permissionText())
            button("Request permissions") {
                permissionRequester()
                text("Requested")
            }
            backButton()
        }
    }

    fun showScanPairQr() {
        setScreen {
            val status = text("Ready")
            val preview = QrPreviewView(context, controller) { message ->
                status.text = message
            }
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 480))
            backButton()
        }
    }

    fun showPlStatusProbe() {
        setScreen {
            val status = text(plStatusText(controller.probePlStatus()))
            button("Probe") {
                status.text = plStatusText(controller.probePlStatus())
            }
            backButton()
        }
    }

    fun showStartStop() {
        setScreen {
            val status = text(controller.diagnostics().display)
            button("Start") {
                val started = controller.start()
                status.text = if (started) controller.diagnostics().display else "Start refused"
            }
            button("Stop") {
                controller.stop()
                status.text = controller.diagnostics().display
            }
            backButton()
        }
    }

    fun showStatusQueueSync() {
        setScreen {
            val content = column()
            button("Refresh") { loadStatus(content) }
            button("Sync now") {
                controller.syncNow()
                loadStatus(content)
            }
            backButton()
            loadStatus(content)
        }
    }

    fun showEvidenceExport() {
        setScreen {
            val content = column()
            backButton()
            loadEvidence(content)
        }
    }

    private fun loadEvidence(content: LinearLayout) {
        asyncLoad.load({ controller.listEvidence() }) { state ->
            content.removeAllViews()
            when (state) {
                LoadState.Loading -> content.text("Loading…")
                is LoadState.Loaded -> {
                    if (state.value.isEmpty()) {
                        content.text("No sealed segments")
                    } else {
                        state.value.forEach { content.segmentView(it) }
                    }
                    onEvidenceLoaded()
                }
                is LoadState.Failed -> {
                    content.text("Couldn't load evidence")
                    onEvidenceLoaded()
                }
            }
        }
    }

    private fun LinearLayout.segmentView(segment: HarnessEvidenceSegment) {
        text(
            listOf(
                "${segment.day}/${segment.stream}/${segment.segment}",
                "state=${segment.state}",
                "bytes=${segment.byteSize}",
                if (segment.stream == LOCATION_STREAM && segment.state == QueueState.SEALED) {
                    "captured-but-not-uploaded"
                } else {
                    null
                },
            ).filterNotNull().joinToString("\n"),
        )
        segment.files.forEach { file ->
            text("${file.sourceId} ${file.name} ${file.mediaType} ${file.byteSize} ${file.sha256}")
        }
        text("Bundle: spool/${segment.day}/${segment.stream}/${segment.segment}")
        button("Export") {
            val result = controller.exportSegment(segment)
            text("Exported ${result.copiedFileCount}: ${result.destinationPath}")
        }
    }

    private fun loadStatus(content: LinearLayout) {
        asyncLoad.load({ controller.syncState() }) { state ->
            content.removeAllViews()
            when (state) {
                LoadState.Loading -> content.text("Loading…")
                is LoadState.Loaded -> {
                    val sync = state.value
                    content.text(
                        listOf(
                            controller.diagnostics().display,
                            "Pending: ${sync.pendingCount}",
                            "Last success: ${sync.lastSuccessAt ?: "none"}",
                            "Last failure: ${sync.lastFailureAt ?: "none"}",
                        ).joinToString("\n"),
                    )
                    onSyncLoaded()
                }
                is LoadState.Failed -> {
                    content.text("Couldn't load status")
                    onSyncLoaded()
                }
            }
        }
    }

    private fun permissionText(): String {
        val p = controller.refreshPermissions()
        return listOf(
            "Microphone: ${p.microphoneGranted}",
            "Camera: ${p.cameraGranted}",
            "Fine location: ${p.fineLocationGranted}",
            "Coarse location: ${p.coarseLocationGranted}",
            "Background location: ${p.backgroundLocationGranted}",
            "Notifications: ${p.notificationsGranted}",
            "Ready: ${p.allRequiredGranted}",
        ).joinToString("\n")
    }

    private fun plStatusText(status: HarnessPlStatus): String =
        when (status) {
            HarnessPlStatus.NotPaired -> "Not paired"
            is HarnessPlStatus.PairedButUnreachable -> "Paired but unreachable: ${status.reason ?: "unknown"}"
            is HarnessPlStatus.Reachable -> "Reachable: ${status.status}"
        }

    private fun setScreen(build: LinearLayout.() -> Unit) {
        container.removeAllViews()
        container.addView(scroll(build))
    }

    private fun scroll(build: LinearLayout.() -> Unit): ScrollView {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            build()
        }
        return ScrollView(context).apply {
            addView(layout)
        }
    }

    private fun LinearLayout.text(value: String): TextView =
        TextView(context).also {
            it.text = value
            addView(it)
        }

    private fun LinearLayout.column(): LinearLayout =
        LinearLayout(context).also {
            it.orientation = LinearLayout.VERTICAL
            addView(it)
        }

    private fun LinearLayout.button(label: String, onClick: () -> Unit): Button =
        Button(context).also {
            it.text = label
            it.setOnClickListener { onClick() }
            addView(it)
        }

    private fun LinearLayout.backButton() {
        button("Back") { showMenu() }
    }
}
