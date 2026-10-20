// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import app.solstone.core.observer.IntakeDecision
import app.solstone.core.observer.IntakeRefusal
import app.solstone.core.observer.intakeDecision
import app.solstone.observer.formfactor.phone.IntakeRefusalLine
import app.solstone.observer.formfactor.phone.IntakeRefusalReason
import app.solstone.observer.formfactor.phone.PhoneShareSheet
import app.solstone.observer.formfactor.phone.PhoneTheme
import app.solstone.observer.formfactor.phone.ShareSheetItem
import app.solstone.observer.formfactor.phone.ShareSheetState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where a share to solstone lands. It runs in the sharing app's task, so back returns there,
 * and each share is its own instance. Nothing is copied until the owner taps to add it: any app
 * can address this activity directly, without the system chooser, so the owner's tap is what
 * makes an import theirs.
 */
class ShareToJournalActivity : ComponentActivity() {
    private val model: ShareModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!model.started) {
            val share = readShare(intent)
            if (share == null) {
                finish()
                return
            }
            model.begin(share, share.uris.map { uri ->
                val meta = share.meta.getValue(uri.toString())
                ShareSheetItem(
                    name = meta.name ?: "item",
                    type = ownerTypeWord(meta.mime),
                    size = meta.size?.let { Formatter.formatShortFileSize(this, it) },
                    refusal = knownRefusal(uri.scheme, meta.mime)?.toLine(),
                )
            })
        }
        setContent {
            val sheet by model.state.collectAsState()
            val intake by ImportIntake.get(this).state.collectAsState()
            // While this share copies, show the intake's own progress for it.
            val state = (sheet as? ShareSheetState.Copying)?.let { copying ->
                (intake as? IntakeState.Copying)?.takeIf { it.total == copying.total }?.let { ShareSheetState.Copying(it.done, it.total) }
            } ?: sheet
            PhoneTheme {
                Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
                    PhoneShareSheet(
                        state = state,
                        onAdd = { add() },
                        onCancel = { finish() },
                        onDone = { finish() },
                    )
                }
            }
        }
    }

    private fun add() {
        val share = model.share ?: return
        // Every stream opens now, while the share's read grant certainly holds; the copies then
        // run app-wide and keep going if this sheet is closed. One the sheet already showed as
        // refused by its type is not opened at all.
        val items = share.uris.map { uri ->
            val meta = share.meta.getValue(uri.toString())
            IntakeItem.Stream(
                displayName = meta.name,
                mime = meta.mime,
                scheme = uri.scheme,
                reportedSize = meta.size,
                input = if (knownRefusal(uri.scheme, meta.mime) != null) null else runCatching { contentResolver.openInputStream(uri) }.getOrNull(),
            )
        } + listOfNotNull(share.text?.let { IntakeItem.Text(it) })
        model.copying(items.size)
        ImportIntake.get(this).submit(items) { outcome -> model.finished(outcome) }
    }

    /** What the share holds, or null when it is nothing this sheet handles. */
    private fun readShare(intent: Intent?): PendingShare? {
        intent ?: return null
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent))
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent)
            else -> return null
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        val keepText = uris.isEmpty() && text != null
        if (uris.isEmpty() && !keepText) return null
        val meta = uris.associate { it.toString() to describe(it) }
        return PendingShare(uris, meta, if (keepText) text else null, textDropped = !keepText && text != null)
    }

    private fun describe(uri: Uri): ShareMeta {
        var name: String? = null
        var size: Long? = null
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
                    size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                }
            }
        }
        val mime = if (uri.scheme == "content") runCatching { contentResolver.getType(uri) }.getOrNull() else null
        return ShareMeta(name, mime, size)
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM))
            ?.filterNotNull().orEmpty()

    /** A refusal the item's type alone already decides, before anything is opened. */
    private fun knownRefusal(scheme: String?, mime: String?): IntakeRefusal? =
        (intakeDecision(scheme, mime, ImportIntake.canConvertHeic) as? IntakeDecision.Refuse)?.reason
}

/** The word an owner knows for a shared item's type; never the media type itself. */
internal fun ownerTypeWord(mime: String?): String {
    val type = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return when {
        type == "application/pdf" -> "PDF"
        type.startsWith("image/") -> "image"
        type.startsWith("audio/") -> "audio"
        type == "text/plain" -> "text"
        else -> "file"
    }
}

internal data class ShareMeta(val name: String?, val mime: String?, val size: Long?)

internal data class PendingShare(
    val uris: List<Uri>,
    val meta: Map<String, ShareMeta>,
    val text: String?,
    val textDropped: Boolean,
)

/** Holds one share across rotation and recreation. */
internal class ShareModel(private val saved: SavedStateHandle) : ViewModel() {
    var share: PendingShare? = null
        private set
    var started = false
        private set
    private val _state = MutableStateFlow<ShareSheetState>(ShareSheetState.Review(emptyList(), null))
    val state: StateFlow<ShareSheetState> = _state

    fun begin(pending: PendingShare, items: List<ShareSheetItem>) {
        share = pending
        started = true
        _state.value = ShareSheetState.Review(
            items = items,
            textPreview = pending.text,
            textDropped = pending.textDropped,
        )
    }

    fun copying(total: Int) {
        _state.value = ShareSheetState.Copying(0, total)
    }

    fun finished(outcome: IntakeOutcome) {
        _state.value = ShareSheetState.Done(outcome.accepted, outcome.refused.map { IntakeRefusalLine(it.label, it.reason.toLine()) })
    }
}

internal fun IntakeRefusal.toLine(): IntakeRefusalReason = IntakeRefusalReason.valueOf(name)
