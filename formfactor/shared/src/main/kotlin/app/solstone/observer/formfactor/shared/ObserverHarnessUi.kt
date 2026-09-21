// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
    private val markAccessoryFactory: ((context: Context, onConfirmed: () -> Unit) -> View)? = null,
    /**
     * How an OWNER-facing control and an owner-facing line of text are dressed.
     *
     * ⚠ Supplied by the form factor, because brand lives in `formfactor/phone` and this module sits
     * below it. ⛔ Applied only in owner-task mode: the operator menu is instrumentation and keeps
     * the platform's own look, which is the same seam `dismiss` already draws for the screen margin
     * and the drawn back button.
     *
     * 🔴 These MUTATE the widget rather than replacing it. `connect a journal` is the app's main
     * call to action, and it was rendering as a grey square-cornered `android.widget.Button` with
     * system-font body text beside the shell's branded surfaces. ⛔ Do not swap the widget class:
     * the runtime tests match on `view is Button`.
     */
    private val ownerButtonStyle: ((Button) -> Unit)? = null,
    private val ownerTextStyle: ((TextView) -> Unit)? = null,
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

    /** Whether the camera-off screen is what the owner is looking at right now. */
    var showsCameraOff: Boolean = false
        private set

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

    /**
     * The gated way in to the scanner, installed by the host activity.
     *
     * 🔴 The menu button called [showScanPairQr] directly, which builds a camera preview — so on
     * this one entry the camera opened before anything asked for it, the same shape as the
     * owner-facing defect the shell's own entry was fixed for. ⛔ Leave this null only where there
     * is no activity to ask.
     */
    var scanEntry: (() -> Unit)? = null

    fun showMenu() {
        setScreen(isMenu = true) {
            button("Permissions") { showPermissions() }
            button("Scan pair QR") { (scanEntry ?: ::showScanPairQr)() }
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
            val onStatus = { message: String ->
                status.text = message
                if (message == "paired") showPaired()
            }
            val preview = when (qrBackend) {
                QrBackend.Camera2 -> Camera2QrPreviewView(context, controller, qrThreadLabel, onStatus)
                QrBackend.Legacy -> LegacyQrPreviewView(context, controller, qrThreadLabel, onStatus)
            }
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeightPx))
            backButton()
        }
    }

    /**
     * The scanner's stand-in when the owner has declined the camera.
     *
     * ⚠ A grant made in system Settings has no in-app callback; the activity watches for the owner
     * coming back while [showsCameraOff] is set and moves on to the scanner. [beforeSettings] runs
     * first so the caller can record that this grant is for scanning, not for a camera source.
     */
    fun showCameraOff(beforeSettings: () -> Unit = {}) {
        setScreen {
            text(CAMERA_OFF_FOR_SCAN)
            button(OPEN_ANDROID_SETTINGS) {
                beforeSettings()
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            }
            backButton()
        }
        showsCameraOff = true
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
                        else -> {
                            status.text = requireNotNull(pairLinkDispatchText(result))
                            if (result is PairLinkDispatchResult.Attempted && result.outcome.isSuccessfulPair()) {
                                showPaired()
                            }
                        }
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

    /**
     * Fill a pairing screen's success slot: the journal mark, then the one way on.
     *
     * 🔴 **`done` exists because single-task mode draws no `Back`.** A successful pair used to end
     * on the word `paired` and the mark with no control anywhere on screen, so the only way on was
     * a system gesture the screen never mentioned. It leaves exactly as back does: to the shell
     * when this harness was opened for one task, to the menu otherwise.
     */
    /**
     * The paired state REPLACES the screen. ⛔ It must not merely clear a column inside it.
     *
     * 🔴 **This used to `removeAllViews()` on a nested column while the camera preview sat beside
     * that column as its sibling, so the preview was never detached — and
     * `Camera2QrPreviewView` releases the camera from `onSurfaceTextureDestroyed`, which only
     * fires on detach.** The owner therefore finished pairing and sat on a confirmation screen
     * with a live camera behind it and the system's camera-in-use indicator still lit.
     *
     * ⚠ **The observable is the camera being RELEASED, not the preview being invisible.** Covering
     * or hiding the preview would look identical to an owner reading this code and identical in a
     * screenshot, and would leave the privacy indicator burning. Replacing the screen is what
     * detaches the `TextureView`, and the detach is what closes the device.
     *
     * ✅ Safe from `onStatus`: `Camera2QrPreviewView` delivers status through `post {}`, so this
     * runs on the main thread.
     */
    private fun showPaired() {
        setScreen {
            val offerExit: () -> Unit = { button("done") { leave() }; Unit }
            markAccessoryFactory?.invoke(context, offerExit)?.let { addView(it) } ?: offerExit()
        }
    }

    private fun setScreen(isMenu: Boolean = false, build: LinearLayout.() -> Unit) {
        inSubmenu = !isMenu
        permissionRows = null
        plStatusRows = null
        showsCameraOff = false
        container.removeAllViews()
        container.addView(scroll(build))
    }

    private fun scroll(build: LinearLayout.() -> Unit): ScrollView {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // ⚠ Only when the owner is the reader. These screens were laid out as instrumentation,
            // with text hard against the display edge; the shell that pushes an owner task here
            // keeps a margin, and its screens should not lose it on the way in.
            if (dismiss != null) {
                val margin = (OWNER_TASK_MARGIN_DP * resources.displayMetrics.density).toInt()
                setPadding(margin, margin, margin, margin)
            }
            build()
        }
        return ScrollView(context).apply {
            addView(layout)
        }
    }

    private fun LinearLayout.text(value: String): TextView =
        TextView(context).also {
            it.text = value
            if (dismiss != null) ownerTextStyle?.invoke(it)
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
            if (dismiss != null) ownerButtonStyle?.invoke(it)
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

    private companion object {
        /** The phone shell's content margin; this module cannot see that constant. */
        const val OWNER_TASK_MARGIN_DP = 16
    }
}
