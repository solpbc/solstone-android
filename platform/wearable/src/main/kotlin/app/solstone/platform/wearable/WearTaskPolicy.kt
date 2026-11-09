// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.wearable

import com.google.android.gms.common.internal.RootTelemetryConfigManager
import com.google.android.gms.common.internal.RootTelemetryConfiguration
import com.google.android.gms.tasks.Task

/** Creates Data Layer tasks without SDK invocation or timing telemetry. */
object WearTaskPolicy {
    private val disabled = RootTelemetryConfiguration(Int.MAX_VALUE, false, false, 0, 0)

    fun <T> createTask(call: () -> Task<T>): Task<T> {
        val configuration = RootTelemetryConfigManager.getInstance()
        return synchronized(configuration) {
            // SDK null/reset updates use this same monitor. Reapply at every call,
            // including listener registration, rather than only at app startup.
            configuration.zza(disabled)
            val current = configuration.config
            check(
                current != null &&
                    !current.methodInvocationTelemetryEnabled &&
                    !current.methodTimingTelemetryEnabled,
            ) { "Wearable SDK telemetry policy unavailable" }

            // The pinned base SDK decides whether to attach its telemetry listener
            // synchronously during task creation. A dependency change requires a
            // new audit of that boundary and the shared configuration monitor.
            // Await tasks and perform channel I/O only after this method returns.
            call()
        }
    }
}
