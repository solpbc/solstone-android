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
import app.solstone.core.pl.PairingMigrationOwner
import app.solstone.core.pl.PairingMigrationPendingReason
import app.solstone.core.pl.PairingMigrationRecord
import app.solstone.core.pl.PairingMigrationResult
import app.solstone.core.pl.PairingMigrationStage
import app.solstone.core.pl.TARGET_UNAVAILABLE_REASON
import app.solstone.observer.formfactor.shared.LegacyQrPreviewView
import app.solstone.observer.formfactor.shared.applySystemBarInsetPadding
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.satisfiableCaptureForegroundTypes

private enum class GlassesMigrationSurface {
    OFFER,
    PREPARING,
    PICKER,
    PICKER_EMPTY,
    CONFIRM,
    DECIDING,
    CHECKING,
    OFFLINE,
    UNSUPPORTED,
    STORAGE,
    LIST_UNAVAILABLE,
    TARGET_UNAVAILABLE,
    REFUSED,
    KEY_REFUSED,
}

private data class GlassesMigrationCopy(val title: String, val body: String? = null, val action: String? = null)

private fun glassesMigrationCopy(surface: GlassesMigrationSurface, selectedLabel: String? = null): GlassesMigrationCopy = when (surface) {
    GlassesMigrationSurface.OFFER -> GlassesMigrationCopy(
        "is this replacing one of your devices?",
        "you can keep both, or choose a device for this one to replace.",
    )
    GlassesMigrationSurface.PREPARING -> GlassesMigrationCopy(
        "getting this device ready",
        "anything waiting to send stays on this device until it's ready.",
    )
    GlassesMigrationSurface.PICKER -> GlassesMigrationCopy("choose a device")
    GlassesMigrationSurface.PICKER_EMPTY -> GlassesMigrationCopy("choose a device", "no other paired devices")
    GlassesMigrationSurface.CONFIRM -> GlassesMigrationCopy(
        selectedLabel?.takeIf(String::isNotBlank)?.let { "replace \"$it\"?" } ?: "replace the selected device?",
        "this device continues its name and history. the selected device will lose access to your journal.",
    )
    GlassesMigrationSurface.DECIDING -> GlassesMigrationCopy("saving your choice")
    GlassesMigrationSurface.CHECKING -> GlassesMigrationCopy(
        "checking your choice",
        "your journal hasn't confirmed the result yet.",
        "check again",
    )
    GlassesMigrationSurface.OFFLINE -> GlassesMigrationCopy(
        "can't reach your journal",
        "anything waiting to send stays on this device. try again when your journal is reachable.",
        "try again",
    )
    GlassesMigrationSurface.UNSUPPORTED -> GlassesMigrationCopy(
        "journal update needed",
        "your journal doesn't support this move yet. anything waiting to send stays on this device. update your journal, then try again.",
        "try again",
    )
    GlassesMigrationSurface.STORAGE -> GlassesMigrationCopy(
        "saved connection unavailable",
        "this device couldn't read its saved connection. anything waiting to send hasn't been removed.",
        "technical details",
    )
    GlassesMigrationSurface.LIST_UNAVAILABLE -> GlassesMigrationCopy(
        "devices unavailable",
        "couldn't load the devices in your journal. try again when your journal is reachable.",
        "try again",
    )
    GlassesMigrationSurface.TARGET_UNAVAILABLE -> GlassesMigrationCopy(
        "device no longer available",
        "that device is no longer listed in your journal. choose another device, or keep both.",
        "choose a device",
    )
    GlassesMigrationSurface.REFUSED -> GlassesMigrationCopy(
        "choice needs attention",
        "your journal couldn't apply this choice. your current connection still works.",
        "technical details",
    )
    GlassesMigrationSurface.KEY_REFUSED -> GlassesMigrationCopy(
        "pair again",
        "this device couldn't open its saved connection to your journal. anything waiting to send is still here. pair again to reconnect.",
        "pair again",
    )
}

class GlassesHarnessUi(
    private val context: Context,
    private val controller: HarnessController,
    private val permissionRequester: () -> Unit,
    private val asyncLoad: AsyncLoad,
    private val pairingMigrationOwner: PairingMigrationOwner? = null,
    private val onPairingCommitted: () -> Unit = {},
    private val onEvidenceLoaded: () -> Unit = {},
    private val onSyncLoaded: () -> Unit = {},
) {
    private val container = FrameLayout(context).apply { applySystemBarInsetPadding() }
    private var migrationProgressDialog: android.app.AlertDialog? = null

    fun view(): View {
        showMenu()
        return container
    }

    fun showMenu() {
        val migrationState = pairingMigrationOwner?.let { runCatching { it.currentOffer() }.getOrNull() }
        val deviceChoicePending = when (migrationState) {
            is PairingMigrationResult.Offer -> migrationState.record.stage !in setOf(
                PairingMigrationStage.TERMINAL_KEEP_BOTH,
                PairingMigrationStage.TERMINAL_REPLACED,
                PairingMigrationStage.TERMINAL_REFUSED,
            )
            PairingMigrationResult.NoOffer -> false
            else -> pairingMigrationOwner != null
        }
        setScreen {
            button("Permissions") { showPermissions() }
            button("Scan pair QR") { showScanPairQr() }
            if (deviceChoicePending) {
                button("device choice") { showDeviceChoice() }
            }
            button("PL status probe") { showPlStatusProbe() }
            button("Start/stop intake") { showStartStop() }
            button("Status + queue/sync") { showStatusQueueSync() }
            button("Evidence + export") { showEvidenceExport() }
        }
    }

    fun showDeviceChoice() {
        val owner = pairingMigrationOwner ?: return
        asyncLoad.load({ owner.currentOffer() }) { state ->
            when (state) {
                LoadState.Loading -> Unit
                is LoadState.Failed -> showMigrationResult(owner, PairingMigrationResult.Unavailable)
                is LoadState.Loaded -> when (val result = state.value) {
                    is PairingMigrationResult.Offer -> {
                        val record = result.record
                        when (record.stage) {
                            PairingMigrationStage.OFFER_NOT_SHOWN -> {
                                asyncLoad.load({
                                    owner.claimInitialPresentation(record.generation)
                                    when (val claimed = owner.currentOffer()) {
                                        is PairingMigrationResult.Offer -> if (claimed.record.stage != PairingMigrationStage.OFFER_NOT_SHOWN) {
                                            claimed
                                        } else {
                                            PairingMigrationResult.Unavailable
                                        }
                                        else -> claimed
                                    }
                                }) { claimed ->
                                    val value = (claimed as? LoadState.Loaded)?.value
                                    showMigrationResult(owner, value ?: PairingMigrationResult.Unavailable)
                                }
                            }
                            PairingMigrationStage.READY_TO_SUBMIT,
                            PairingMigrationStage.SUBMITTED_UNKNOWN -> {
                                showMigrationProgress(GlassesMigrationSurface.CHECKING)
                                asyncLoad.load({ owner.resume(record.generation) }) { resumed ->
                                    val value = (resumed as? LoadState.Loaded)?.value
                                    showMigrationResult(owner, value ?: PairingMigrationResult.Unavailable)
                                }
                            }
                            PairingMigrationStage.AWAITING_SELECTION -> requestMigrationListing(owner, record.generation)
                            else -> showDeviceChoiceOptions(owner, record)
                        }
                    }
                    else -> showMigrationResult(owner, result)
                }
            }
        }
    }

    private fun showDeviceChoiceOptions(owner: PairingMigrationOwner, record: PairingMigrationRecord) {
        if (record.stage == PairingMigrationStage.TERMINAL_KEEP_BOTH ||
            record.stage == PairingMigrationStage.TERMINAL_REPLACED
        ) {
            showMenu()
            return
        }
        if (record.stage == PairingMigrationStage.TERMINAL_REFUSED) {
            showRefusal(owner, record)
            return
        }
        if (record.refusalReason == TARGET_UNAVAILABLE_REASON) {
            showTargetUnavailable(owner, record.generation)
            return
        }
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(glassesMigrationCopy(GlassesMigrationSurface.OFFER).title)
            .setMessage(glassesMigrationCopy(GlassesMigrationSurface.OFFER).body)
            .setPositiveButton("keep both") { _, _ ->
                showMigrationProgress(GlassesMigrationSurface.DECIDING)
                asyncLoad.load({ owner.keepBoth(record.generation) }) { result ->
                    val value = (result as? LoadState.Loaded)?.value
                    showMigrationResult(owner, value ?: PairingMigrationResult.Unavailable)
                }
            }
            .setNegativeButton("choose a device") { _, _ ->
                requestMigrationListing(owner, record.generation)
            }
            .setNeutralButton("not now") { _, _ ->
                asyncLoad.load({ owner.defer(record.generation) }) { }
            }
            .setOnCancelListener { asyncLoad.load({ owner.defer(record.generation) }) { } }
            .create()
        dialog.show()
    }

    private fun showTargetUnavailable(owner: PairingMigrationOwner, generation: app.solstone.core.identity.PairingGeneration) {
        val copy = glassesMigrationCopy(GlassesMigrationSurface.TARGET_UNAVAILABLE)
        android.app.AlertDialog.Builder(context)
            .setTitle(copy.title)
            .setMessage(copy.body)
            .setPositiveButton("choose a device") { _, _ -> requestMigrationListing(owner, generation) }
            .setNegativeButton("keep both") { _, _ -> submitKeepBoth(owner, generation) }
            .setNeutralButton("not now") { _, _ -> asyncLoad.load({ owner.defer(generation) }) { } }
            .setOnCancelListener { asyncLoad.load({ owner.defer(generation) }) { } }
            .show()
    }

    private fun showDeviceChoiceListing(owner: PairingMigrationOwner, listing: app.solstone.core.pl.PairingMigrationListing) {
        if (listing.clients.isEmpty()) {
            val copy = glassesMigrationCopy(GlassesMigrationSurface.PICKER_EMPTY)
            android.app.AlertDialog.Builder(context)
                .setTitle(copy.title)
                .setMessage(copy.body)
                .setPositiveButton("keep both") { _, _ -> submitKeepBoth(owner, listing.generation) }
                .setNegativeButton("cancel") { _, _ -> asyncLoad.load({ owner.defer(listing.generation) }) { } }
                .setOnCancelListener { asyncLoad.load({ owner.defer(listing.generation) }) { } }
                .show()
            return
        }
        android.app.AlertDialog.Builder(context)
            .setTitle(glassesMigrationCopy(GlassesMigrationSurface.PICKER).title)
            .setItems(listing.clients.map { it.displayLabel }.toTypedArray()) { _, index ->
                val client = listing.clients[index]
                showMigrationProgress(GlassesMigrationSurface.PREPARING)
                asyncLoad.load({ owner.selectTarget(listing, client.cid) }) { selection ->
                    if ((selection as? LoadState.Loaded)?.value is PairingMigrationResult.Offer) {
                        showConfirmReplacement(owner, listing, client)
                    } else {
                        val value = (selection as? LoadState.Loaded)?.value
                        showMigrationResult(owner, value ?: PairingMigrationResult.Unavailable)
                    }
                }
            }
            .setNegativeButton("cancel") { _, _ -> asyncLoad.load({ owner.defer(listing.generation) }) { } }
            .setOnCancelListener { asyncLoad.load({ owner.defer(listing.generation) }) { } }
            .show()
    }

    private fun requestMigrationListing(owner: PairingMigrationOwner, generation: app.solstone.core.identity.PairingGeneration) {
        showMigrationProgress(GlassesMigrationSurface.PREPARING)
        asyncLoad.load({ owner.listClients(generation) }) { result ->
            val value = (result as? LoadState.Loaded)?.value
            showMigrationResult(owner, value ?: PairingMigrationResult.Unavailable)
        }
    }

    private fun submitKeepBoth(owner: PairingMigrationOwner, generation: app.solstone.core.identity.PairingGeneration) {
        showMigrationProgress(GlassesMigrationSurface.DECIDING)
        asyncLoad.load({ owner.keepBoth(generation) }) { result ->
            val value = (result as? LoadState.Loaded)?.value
            showMigrationResult(owner, value ?: PairingMigrationResult.Unavailable)
        }
    }

    private fun showConfirmReplacement(
        owner: PairingMigrationOwner,
        listing: app.solstone.core.pl.PairingMigrationListing,
        client: app.solstone.core.pl.MigrationClient,
    ) {
        dismissMigrationProgress()
        val copy = glassesMigrationCopy(GlassesMigrationSurface.CONFIRM, client.displayLabel)
        android.app.AlertDialog.Builder(context)
            .setTitle(copy.title)
            .setMessage(copy.body)
            .setPositiveButton("replace device") { _, _ ->
                showMigrationProgress(GlassesMigrationSurface.DECIDING)
                asyncLoad.load({ owner.replaceSelected(listing, client.cid, nativeConfirmed = true) }) { result ->
                    val value = (result as? LoadState.Loaded)?.value
                    showMigrationResult(owner, value ?: PairingMigrationResult.Unavailable)
                }
            }
            .setNegativeButton("cancel") { _, _ -> asyncLoad.load({ owner.defer(listing.generation) }) { } }
            .setOnCancelListener { asyncLoad.load({ owner.defer(listing.generation) }) { } }
            .show()
    }

    private fun showMigrationProgress(surface: GlassesMigrationSurface) {
        val copy = glassesMigrationCopy(surface)
        val builder = android.app.AlertDialog.Builder(context).setTitle(copy.title)
        copy.body?.let(builder::setMessage)
        migrationProgressDialog?.dismiss()
        migrationProgressDialog = builder.setCancelable(false).create().also { it.show() }
    }

    private fun showMigrationStatus(
        surface: GlassesMigrationSurface,
        onAction: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
    ) {
        val copy = glassesMigrationCopy(surface)
        val builder = android.app.AlertDialog.Builder(context).setTitle(copy.title)
        copy.body?.let(builder::setMessage)
        if (copy.action != null && onAction != null) {
            builder.setPositiveButton(copy.action) { _, _ -> onAction() }
        }
        builder.setOnCancelListener { onCancel?.invoke() }.show()
    }

    private fun showRefusal(owner: PairingMigrationOwner, record: PairingMigrationRecord) {
        if (record.refusalReason == "migration_protocol_unsupported") {
            showMigrationStatus(GlassesMigrationSurface.UNSUPPORTED, onAction = {
                asyncLoad.load({ owner.retryUnsupported(record.generation) }) { result ->
                    val value = (result as? LoadState.Loaded)?.value
                    showMigrationResult(owner, value ?: PairingMigrationResult.Unavailable)
                }
            })
        } else {
            showMigrationStatus(GlassesMigrationSurface.REFUSED, onAction = ::showPlStatusProbe)
        }
    }

    private fun showMigrationResult(owner: PairingMigrationOwner, result: PairingMigrationResult) {
        dismissMigrationProgress()
        when (result) {
            PairingMigrationResult.NoOffer,
            PairingMigrationResult.StaleGeneration,
            PairingMigrationResult.InvalidSelection,
            PairingMigrationResult.ConfirmationRequired -> showMenu()
            PairingMigrationResult.Unavailable -> showMigrationStatus(
                GlassesMigrationSurface.STORAGE,
                onAction = ::showPlStatusProbe,
            )
            is PairingMigrationResult.Offer -> showDeviceChoiceOptions(owner, result.record)
            is PairingMigrationResult.Listing -> showDeviceChoiceListing(owner, result.value)
            is PairingMigrationResult.Terminal -> {
                if (result.record.stage == PairingMigrationStage.TERMINAL_REFUSED) showRefusal(owner, result.record)
                else showMenu()
            }
            is PairingMigrationResult.Pending -> when (result.reason) {
                PairingMigrationPendingReason.DECISION_UNKNOWN -> showMigrationStatus(
                    GlassesMigrationSurface.CHECKING,
                    onAction = {
                        showMigrationProgress(GlassesMigrationSurface.CHECKING)
                        asyncLoad.load({ owner.resume(result.record.generation) }) { resumed ->
                            val value = (resumed as? LoadState.Loaded)?.value
                            showMigrationResult(owner, value ?: PairingMigrationResult.Unavailable)
                        }
                    },
                    onCancel = { asyncLoad.load({ owner.defer(result.record.generation) }) { } },
                )
                PairingMigrationPendingReason.LIST_OFFLINE -> showMigrationStatus(
                    GlassesMigrationSurface.OFFLINE,
                    onAction = { requestMigrationListing(owner, result.record.generation) },
                    onCancel = { asyncLoad.load({ owner.defer(result.record.generation) }) { } },
                )
                PairingMigrationPendingReason.LIST_UNAVAILABLE -> showMigrationStatus(
                    GlassesMigrationSurface.LIST_UNAVAILABLE,
                    onAction = { requestMigrationListing(owner, result.record.generation) },
                    onCancel = { asyncLoad.load({ owner.defer(result.record.generation) }) { } },
                )
                PairingMigrationPendingReason.SAVED_CONNECTION_REFUSED -> showMigrationStatus(
                    GlassesMigrationSurface.KEY_REFUSED,
                    onAction = ::showScanPairQr,
                )
            }
        }
    }

    private fun dismissMigrationProgress() {
        migrationProgressDialog?.dismiss()
        migrationProgressDialog = null
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
            val preview = LegacyQrPreviewView(
                context = context,
                controller = controller,
                threadLabel = "glasses",
                onPairingCommitted = onPairingCommitted,
            ) { message -> status.text = message }
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
        val ready = satisfiableCaptureForegroundTypes(
            microphoneGranted = p.microphoneGranted,
            cameraGranted = p.cameraGranted,
            locationGranted = p.locationGranted,
            declared = setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.CAMERA),
        ).isNotEmpty()
        return listOf(
            "Microphone: ${p.microphoneGranted}",
            "Camera: ${p.cameraGranted}",
            "Notifications: ${p.notificationsGranted}",
            "Ready: $ready",
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
        button("Back") { showMenu() }
    }
}
