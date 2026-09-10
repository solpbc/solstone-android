// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

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
import app.solstone.observer.harness.HarnessJournalCacheState
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.PairLinkDispatchResult
import app.solstone.observer.harness.decimalBytes
import app.solstone.observer.harness.journalCacheText
import app.solstone.observer.harness.plStatusText
import app.solstone.observer.harness.syncNowMessage

class ObserverHarnessUi(
    private val context: Context,
    private val controller: HarnessController,
    private val permissionRequester: () -> Unit,
    private val asyncLoad: AsyncLoad,
    private val previewHeightPx: Int,
    private val qrBackend: QrBackend,
    private val qrThreadLabel: String,
    private val journalCacheState: () -> HarnessJournalCacheState,
    private val saveJournalCacheLimit: (Long) -> HarnessJournalCacheState,
    private val onEvidenceLoaded: () -> Unit = {},
    private val onSyncLoaded: () -> Unit = {},
    private val onJournalCacheLoadComplete: () -> Unit = {},
) {
    private val container = FrameLayout(context).apply { applySystemBarInsetPadding() }
    private var inSubmenu = false

    /**
     * Where leaving a screen goes, when this harness was opened for **one owner task**.
     *
     * 🔴 **Five of the seven screens below are operator instrumentation** — a permission probe, a
     * transport probe, raw start/stop, queue counters, and an evidence browser that prints spool
     * paths, wire segment ids and SHA-256s. ⛔ **None of them is an owner surface.** But the phone
     * shell opens this harness for two tasks that *are* (`connect a journal`, `manage local
     * storage`) and an App Link opens it for a third, and back out of any of them used to land the
     * owner on the menu that lists the other five.
     *
     * When this is set, there is no menu: the task is the whole surface and leaving it returns the
     * owner to the shell. ⛔ Nothing may route to [showMenu] while it is set.
     */
    private var dismiss: (() -> Unit)? = null
    private var permissionRows: TextView? = null
    private var plStatusRows: TextView? = null
    private var plStatusOutstanding: Boolean = false

    fun view(): View {
        // ⛔ Not unconditional: in single-task mode the caller routes to the one screen the owner
        // asked for, and building the menu first would put it on screen for a frame.
        if (dismiss == null) showMenu()
        return container
    }

    /** Open this harness for one task only; [action] is where leaving that task goes. */
    fun dismissTo(action: () -> Unit) {
        dismiss = action
    }

    fun showMenu() {
        setScreen(isMenu = true) {
            button("Permissions") { showPermissions() }
            button("Scan pair QR") { showScanPairQr() }
            button("PL status probe") { showPlStatusProbe() }
            button("Start/stop intake") { showStartStop() }
            button("Status + queue/sync") { showStatusQueueSync() }
            button("Evidence + export") { showEvidenceExport() }
            button("Local storage") { showLocalCache() }
        }
    }

    fun showPermissions() {
        setScreen {
            permissionRows = text("")
            val requestNote = text("")
            button("Request permissions") {
                permissionRequester()
                requestNote.text = "Requested"
            }
            backButton()
        }
        refreshPermissions()
    }

    fun refreshPermissions() {
        val rows = permissionRows ?: return
        rows.text = permissionRowsText(controller.refreshPermissions())
    }

    fun showScanPairQr() {
        setScreen {
            // Owner-reachable via the shell's `connect a journal`, so it says what to do rather
            // than that the harness is ready. Adopted from the string iOS already ships for this
            // screen (`QRScannerView`), not authored here.
            val status = text("point your phone at the code")
            val preview = when (qrBackend) {
                QrBackend.Camera2 -> Camera2QrPreviewView(context, controller, qrThreadLabel) { message ->
                    status.text = message
                }
                QrBackend.Legacy -> LegacyQrPreviewView(context, controller, qrThreadLabel) { message ->
                    status.text = message
                }
            }
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeightPx))
            backButton()
        }
    }

    fun showPairLink(uri: String?) {
        setScreen {
            val status = text("pairing…")
            backButton()
            asyncLoad.load({ controller.dispatchPairLink(uri) }) { state ->
                when (state) {
                    LoadState.Loading -> status.text = "pairing…"
                    is LoadState.Loaded -> when (val result = state.value) {
                        PairLinkDispatchResult.NoLink -> leave()
                        else -> status.text = requireNotNull(pairLinkDispatchText(result))
                    }
                    // ⚠ The third copy of `Pairing failed`, and the reason this line reads from
                    // the renderer now: two independent tables of the same words is how the first
                    // one outlived a product name.
                    is LoadState.Failed -> status.text = PAIR_DISPATCH_FAILED
                }
            }
        }
    }

    fun showPlStatusProbe() {
        setScreen {
            plStatusRows = text("")
            button("Probe") { loadPlStatus() }
            backButton()
            loadPlStatus()
        }
    }

    private fun loadPlStatus() {
        if (plStatusOutstanding) {
            plStatusRows?.text = "checking your journal…"
            return
        }
        plStatusOutstanding = true
        asyncLoad.load({ controller.probePlStatus() }) { state ->
            when (state) {
                LoadState.Loading ->
                    plStatusRows?.text = "checking your journal…"
                is LoadState.Loaded -> {
                    plStatusOutstanding = false
                    plStatusRows?.text = plStatusText(state.value)
                }
                is LoadState.Failed -> {
                    plStatusOutstanding = false
                    plStatusRows?.text = "couldn't check your journal"
                }
            }
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

    fun showLocalCache() {
        setScreen {
            val content = column()
            backButton()
            loadJournalCache(content)
        }
    }

    private fun loadJournalCache(content: LinearLayout) {
        asyncLoad.load(journalCacheState) { state ->
            content.removeAllViews()
            when (state) {
                LoadState.Loading -> content.text("checking what's on this device…")
                is LoadState.Loaded -> {
                    content.renderJournalCache(state.value)
                    onJournalCacheLoadComplete()
                }
                is LoadState.Failed -> {
                    content.text("couldn't check what's on this device.")
                    onJournalCacheLoadComplete()
                }
            }
        }
    }

    private fun LinearLayout.renderJournalCache(state: HarnessJournalCacheState) {
        text(journalCacheText(state))
        state.limitChoicesBytes.forEach { choice ->
            val current = if (choice == state.configuredLimitBytes) " (your limit now)" else ""
            button("use ${decimalBytes(choice)}$current") {
                asyncLoad.load({ saveJournalCacheLimit(choice) }) { loadState ->
                    when (loadState) {
                        LoadState.Loading -> {
                            removeAllViews()
                            text("saving your limit…")
                        }
                        is LoadState.Loaded -> {
                            removeAllViews()
                            renderJournalCache(loadState.value)
                            onJournalCacheLoadComplete()
                        }
                        is LoadState.Failed -> {
                            removeAllViews()
                            text("couldn't save that limit. your previous limit is still in place.")
                            onJournalCacheLoadComplete()
                        }
                    }
                }
            }
        }
    }

    private fun loadEvidence(content: LinearLayout) {
        asyncLoad.load({ controller.listEvidence() }) { state ->
            content.removeAllViews()
            when (state) {
                LoadState.Loading -> content.text("Loading...")
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
                LoadState.Loading -> content.text("Loading...")
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

    fun handleBack(): Boolean {
        dismiss?.let {
            it()
            return true
        }
        if (!inSubmenu) return false
        showMenu()
        return true
    }

    private fun leave() {
        dismiss?.invoke() ?: showMenu()
    }

    private fun setScreen(isMenu: Boolean = false, build: LinearLayout.() -> Unit) {
        inSubmenu = !isMenu
        permissionRows = null
        plStatusRows = null
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
            // 🔴 The platform button style sets `textAllCaps`, so every label on these screens
            // rendered as `USE 4 GB (YOUR LIMIT NOW)` — shouting, in an app whose entire register is
            // lowercase. ⚠ Invisible in source and invisible to a string test: the strings were
            // already lowercase and the widget uppercased them at draw time. Only looking at the
            // screen finds this one.
            it.isAllCaps = false
            it.setOnClickListener { onClick() }
            addView(it)
        }

    private fun LinearLayout.backButton() {
        // ⛔ No in-screen back in single-task mode. The platform bible gives Android's back to the
        // system gesture, and a drawn `Back` button is this harness's own idiom leaking onto a
        // surface the shell pushed.
        if (dismiss != null) return
        button("Back") { leave() }
    }
}
