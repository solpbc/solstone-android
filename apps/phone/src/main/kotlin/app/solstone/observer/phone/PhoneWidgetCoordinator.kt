// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhoneWidgetCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun updateAll() {
        scope.launch {
            updateAllInBackground()
        }
    }

    fun refreshAndUpdateAll(refresh: () -> Unit) {
        scope.launch {
            refresh()
            updateAllInBackground()
        }
    }

    private suspend fun updateAllInBackground() {
        PhoneObserverWidget().updateAll(appContext)
        onUpdateCompleteForTest?.invoke()
    }

    companion object {
        @Volatile internal var onUpdateCompleteForTest: (() -> Unit)? = null
    }
}
