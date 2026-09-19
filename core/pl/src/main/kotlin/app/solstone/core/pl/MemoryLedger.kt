// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class LedgerCategory {
    CALLBACK,
    WS_QUEUED_CURRENT,
    TLS,
    MUX_HEADER_PAYLOAD,
    HTTP_PARSER_HEADER_TRAILER,
    PER_FLOW_QUEUE,
    DOWNSTREAM_OUTPUT,
}

interface LiveRoot {
    val category: LedgerCategory
    val capacity: Long
    fun retainedBytes(): Long = capacity
}

object MemoryLedger {
    const val MAX_CAPACITY_BYTES: Long = 52L * 1024 * 1024 // 52 MiB

    private val roots = ConcurrentHashMap.newKeySet<LiveRoot>()

    fun registerRoot(root: LiveRoot) {
        roots.add(root)
    }

    fun unregisterRoot(root: LiveRoot) {
        roots.remove(root)
    }

    fun totalRegisteredCapacity(): Long {
        return roots.sumOf { it.capacity }
    }

    fun categoryCapacity(category: LedgerCategory): Long {
        return roots.filter { it.category == category }.sumOf { it.capacity }
    }

    fun measureRetainedBytes(): Long {
        return roots.sumOf { it.retainedBytes() }
    }

    fun isWithinEnvelope(): Boolean {
        return totalRegisteredCapacity() <= MAX_CAPACITY_BYTES
    }

    fun reset() {
        roots.clear()
    }
}
