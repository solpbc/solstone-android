// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

interface ByteDuplex : Closeable {
    val input: InputStream
    val output: OutputStream
}
