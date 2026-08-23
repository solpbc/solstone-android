// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

class PaneStates private constructor(private val openPanes: Set<PhonePane>) {
    fun isOpen(pane: PhonePane): Boolean = pane in openPanes

    fun open(pane: PhonePane): PaneStates = PaneStates(openPanes + pane)

    fun close(pane: PhonePane): PaneStates = PaneStates(openPanes - pane)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaneStates) return false
        return openPanes == other.openPanes
    }

    override fun hashCode(): Int = openPanes.hashCode()

    override fun toString(): String = "PaneStates($openPanes)"

    companion object {
        val Empty = PaneStates(emptySet())
    }
}

fun encodePaneStates(states: PaneStates): List<String> =
    PhonePane.entries.filter { states.isOpen(it) }.map(::encodePhonePane)

fun decodePaneStates(keys: List<String>): PaneStates =
    keys.mapNotNull(::decodePhonePane).fold(PaneStates.Empty) { states, pane ->
        states.open(pane)
    }

val PaneStatesSaver: Saver<PaneStates, Any> = listSaver(
    save = { encodePaneStates(it) },
    restore = { decodePaneStates(it) },
)

@Composable
fun rememberPaneStates(
    initial: PaneStates = PaneStates.Empty,
): MutableState<PaneStates> =
    rememberSaveable(stateSaver = PaneStatesSaver) {
        mutableStateOf(initial)
    }
