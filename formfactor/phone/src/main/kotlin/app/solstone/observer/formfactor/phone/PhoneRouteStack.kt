// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

class PhoneRouteStack private constructor(private val entries: List<PhoneRoute>) {
    val depth: Int get() = entries.size

    fun showInDetail(route: PhoneRoute): PhoneRouteStack = PhoneRouteStack(listOf(route))

    fun pushInDetail(route: PhoneRoute): PhoneRouteStack = PhoneRouteStack(entries + route)

    fun toList(): List<PhoneRoute> = ArrayList(entries)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhoneRouteStack) return false
        return entries == other.entries
    }

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "PhoneRouteStack($entries)"

    companion object {
        val Empty = PhoneRouteStack(emptyList())
    }
}

fun encodePhoneRouteStack(stack: PhoneRouteStack): List<String> =
    stack.toList().map(::encodePhoneRoute)

fun decodePhoneRouteStack(keys: List<String>): PhoneRouteStack =
    keys.mapNotNull(::decodePhoneRoute).fold(PhoneRouteStack.Empty) { stack, route ->
        stack.pushInDetail(route)
    }

val PhoneRouteStackSaver: Saver<PhoneRouteStack, Any> = listSaver(
    save = { encodePhoneRouteStack(it) },
    restore = { decodePhoneRouteStack(it) },
)

@Composable
fun rememberPhoneRouteStack(
    initial: PhoneRouteStack = PhoneRouteStack.Empty,
): MutableState<PhoneRouteStack> =
    rememberSaveable(stateSaver = PhoneRouteStackSaver) {
        mutableStateOf(initial)
    }
