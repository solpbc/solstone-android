// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

import app.solstone.core.identity.atomicWriteOwnerOnly
import java.io.File

class GateResultWriter(private val target: File) {
    fun write(result: GateResult) {
        atomicWriteOwnerOnly(target, GateResultCodec.encode(result))
    }
}
