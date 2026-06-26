// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.ByteDuplex
import java.io.InputStream
import java.io.OutputStream
import javax.net.ssl.SSLSocket

internal class SocketByteDuplex(private val socket: SSLSocket) : ByteDuplex {
    override val input: InputStream = socket.inputStream
    override val output: OutputStream = socket.outputStream

    override fun close() {
        socket.close()
    }
}
