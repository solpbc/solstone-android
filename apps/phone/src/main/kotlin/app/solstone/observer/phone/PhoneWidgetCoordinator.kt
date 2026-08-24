// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhoneWidgetCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()

    fun updateAll() {
        scope.launch {
            refreshMutex.withLock {
                updateAllInBackground()
            }
        }
    }

    fun refreshAndUpdateAll(refresh: () -> Unit) {
        scope.launch {
            refreshMutex.withLock {
                refresh()
                updateAllInBackground()
            }
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
