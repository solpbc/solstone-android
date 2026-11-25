// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.solstone.platform.persistence.room.ImportCancelResult
import app.solstone.platform.work.abortRemoteUploadForPairing
import app.solstone.platform.work.syncStores
import java.util.concurrent.Executors

/**
 * Handles the `cancel` action button on the Android 14+ user-initiated import upload notification.
 * Non-exported; runs cancellation and best-effort remote `DELETE /app/import/api/uploads/{id}` off
 * the main thread.
 */
class ImportUploadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_UPLOAD) return
        val app = context.applicationContext
        val requestedId = intent.getStringExtra(EXTRA_ITEM_ID)
        val pendingResult = goAsync()
        cancelExecutor.execute {
            try {
                cancelImportItem(app, requestedId)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_UPLOAD = "app.solstone.observer.phone.action.CANCEL_IMPORT_UPLOAD"
        const val EXTRA_ITEM_ID = "item_id"

        private val cancelExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "import-upload-cancel").apply { isDaemon = true }
        }

        internal fun cancelImportItem(context: Context, explicitItemId: String?): ImportCancelResult {
            val app = context.applicationContext
            val store = ImportIntake.get(app).store
            val targetId = explicitItemId?.takeIf { it.isNotBlank() }
                ?: store.items(null, null).firstOrNull { it.inFlight || it.uploadResumable }?.id
                ?: return ImportCancelResult.Gone
            val result = store.requestCancel(targetId)
            when (result) {
                is ImportCancelResult.SignalledInFlight -> {
                    // Wake any retry backoff in the active UIDT job so it aborts immediately.
                    ImportUploadJobService.wakeForCancel()
                }
                is ImportCancelResult.RemovedLocally -> {
                    val uploadId = result.uploadId
                    val instanceId = result.uploadInstanceId
                    if (uploadId != null && instanceId != null) {
                        runCatching {
                            abortRemoteUploadForPairing(
                                stores = syncStores(app),
                                uploadId = uploadId,
                                expectedInstanceId = instanceId,
                            )
                        }
                    }
                    if (store.items(null, null).none { it.uploadResumable || it.inFlight }) {
                        ImportUploadScheduler.cancelJob(app)
                    }
                }
                ImportCancelResult.Gone,
                ImportCancelResult.Delivered,
                -> Unit
            }
            return result
        }
    }
}
