// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import android.content.res.Resources
import java.text.NumberFormat

/** The presenter stays host-testable; Android owns resource and quantity selection. */
internal interface WatchCopy {
    fun text(message: WatchMessage, vararg arguments: Any): String
    fun quantity(message: WatchMessage, quantity: Int, vararg arguments: Any): String
    fun number(value: Long): String = value.toString()
}

internal class AndroidWatchCopy(private val resources: Resources) : WatchCopy {
    override fun text(message: WatchMessage, vararg arguments: Any): String =
        resources.getString(message.resource, *arguments)

    override fun quantity(message: WatchMessage, quantity: Int, vararg arguments: Any): String =
        resources.getQuantityString(message.resource, quantity, *arguments)

    override fun number(value: Long): String = NumberFormat.getIntegerInstance(resources.configuration.locales[0])
        .apply { isGroupingUsed = false }.format(value)
}
