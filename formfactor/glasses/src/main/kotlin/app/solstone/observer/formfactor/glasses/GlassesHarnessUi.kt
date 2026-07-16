// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.glasses

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.HarnessEvidenceSegment
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.plStatusText
import app.solstone.observer.harness.syncNowMessage
import app.solstone.observer.formfactor.shared.LegacyQrPreviewView
import app.solstone.observer.formfactor.shared.applySystemBarInsetPadding

class GlassesHarnessUi(
    private val context: Context,
    private val controller: HarnessController,
    private val permissionRequester: () -> Unit,
    private val asyncLoad: AsyncLoad,
    private val onEvidenceLoaded: () -> Unit = {},
    private val onSyncLoaded: () -> Unit = {},
) {
    private val container = FrameLayout(context).apply { applySystemBarInsetPadding() }

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
            val preview = LegacyQrPreviewView(context, controller, "glasses") { message ->
                status.text = message
            }
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 220))
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
                asyncLoad.load(
                    {
                        controller.stop()
                        controller.diagnostics().display
                    },
                ) { state ->
                    status.text = when (state) {
                        LoadState.Loading -> "Stopping..."
                        is LoadState.Loaded -> state.value
                        is LoadState.Failed -> "Stop failed"
                    }
                }
            }
            backButton()
        }
    }

    fun showStatusQueueSync() {
        setScreen {
            val content = column()
            val syncMessage = text("")
            button("Refresh") { loadStatus(content) }
            button("Sync now") {
                syncMessage.text = syncNowMessage(controller.syncNow())
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
        val localPath = "${segment.day}/${segment.stream}/${segment.dirSegment}"
        text(
            listOf(
                localPath,
                if (segment.dirSegment != segment.segment) "wire=${segment.segment}" else null,
                "state=${segment.state}",
                "bytes=${segment.byteSize}",
            ).filterNotNull().joinToString("\n"),
        )
        segment.files.forEach { file ->
            text("${file.sourceId} ${file.name} ${file.mediaType} ${file.byteSize} ${file.sha256}")
        }
        text("Bundle: spool/$localPath")
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
            "Notifications: ${p.notificationsGranted}",
            "Ready: ${p.allRequiredGranted}",
        ).joinToString("\n")
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
