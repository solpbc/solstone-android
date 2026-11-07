// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.atomicWriteOwnerOnly
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * The journal's in-app origin for one pairing: `http://<token>.localhost:<port>/`.
 *
 * It lasts as long as the pairing, so the journal's page cache and web storage carry over from one
 * open to the next. It is kept beside the pairing record and never leaves the phone.
 *
 * 🔴 It belongs to exactly one pairing. A record whose pairing is not the current one is never
 * used: a new one is minted in its place, and unpairing deletes it.
 */
data class JournalBrowserOriginRecord(
    val pairing: PairingGeneration,
    val token: String,
    val port: Int,
) {
    override fun toString(): String = "JournalBrowserOriginRecord(REDACTED)"
}

interface JournalBrowserOriginStore {
    /** The stored origin, or null when there is none or it can't be read. */
    fun read(): JournalBrowserOriginRecord?
    fun write(record: JournalBrowserOriginRecord)
    fun clear()
}

class FileJournalBrowserOriginStore(private val file: File) : JournalBrowserOriginStore {
    private val lock = Any()

    override fun read(): JournalBrowserOriginRecord? = synchronized(lock) {
        if (!file.exists()) return null
        runCatching {
            val root = parseJson(file.readText(Charsets.UTF_8)) as? Map<*, *> ?: return null
            val instanceId = root["instance_id"] as? String ?: return null
            val fingerprint = root["client_cert_fingerprint"] as? String ?: return null
            val token = root["token"] as? String ?: return null
            val port = (root["port"] as? Number)?.toInt() ?: return null
            JournalBrowserOriginRecord(PairingGeneration(instanceId, fingerprint), token, port)
        }.getOrNull()
    }

    override fun write(record: JournalBrowserOriginRecord) = synchronized(lock) {
        val body = mapOf(
            "instance_id" to record.pairing.instanceId,
            "client_cert_fingerprint" to record.pairing.clientCertFingerprint,
            "token" to record.token,
            "port" to record.port,
        )
        atomicWriteOwnerOnly(file, toJson(body).toByteArray(Charsets.UTF_8))
    }

    override fun clear() {
        synchronized(lock) { file.delete() }
    }
}

/**
 * Holds the kept origin's port for as long as this process lives, so the journal's page can never
 * ask that port for anything after another app has taken it. One per process.
 *
 * 🔴 Why the whole process and not just the session. A journal page keeps asking its origin for
 * things after the session behind it ends (its live channel reconnects by itself), and it keeps
 * going for a moment even after its web view is destroyed: a `/favicon.ico` request was measured
 * arriving after `WebView.destroy()` had returned. With an origin that lasts the pairing, a request
 * that reached another app's listener would hand it a token that is still good, and the chance to
 * leave cached pages the next open would run. So the port is never let go while the origin is in
 * use. Between sessions it closes every connection unanswered; when the process dies, the web
 * views' networking dies with it.
 *
 * The port is let go only when the origin itself is retired (unpairing, or a different pairing).
 * Anything that reaches the port after that carries a token nothing will load again.
 */
class JournalBrowserOriginKeeper(private val store: JournalBrowserOriginStore) {

    class Kept internal constructor(
        val record: JournalBrowserOriginRecord,
        internal val listener: JournalBrowserListener,
        /** `held` this process already held it, `kept` the stored port bound again, `minted` new, `fallback` the stored port was taken. */
        val outcome: String,
    )

    private var held: Kept? = null

    /**
     * This pairing's origin, its listener holding the port. Throws when no port will bind.
     *
     * 🔴 A page is loaded only from an origin whose port our own listener holds. When the stored
     * port won't bind, a fresh port and a fresh token are minted, a new origin with an empty cache,
     * and nothing is ever loaded from the old one.
     */
    @Synchronized
    fun acquire(pairing: PairingGeneration): Kept {
        val stored = runCatching { store.read() }.getOrNull()
            ?.takeIf { it.pairing == pairing && it.token.matches(TOKEN_SHAPE) && it.port in 1..65535 }
        held?.let { h ->
            if (h.record == stored && !h.listener.isClosed) return Kept(h.record, h.listener, "held")
            // Another pairing's origin, or one that was cleared: retire it.
            h.listener.close()
            held = null
        }
        var outcome = "minted"
        var ss: ServerSocket? = null
        var token = mintJournalBrowserToken()
        if (stored != null) {
            ss = bindJournalBrowserLoopback(stored.port)
            if (ss != null) {
                token = stored.token
                outcome = "kept"
            } else {
                outcome = "fallback"
            }
        }
        val bound = ss ?: bindJournalBrowserLoopback(0) ?: throw IOException("bind failed")
        val record = JournalBrowserOriginRecord(pairing, token, bound.localPort)
        // A record that can't be written costs the next process its cache, nothing else.
        if (record != stored) runCatching { store.write(record) }
        return Kept(record, JournalBrowserListener(bound), outcome).also { held = it }
    }

    /** Unpairing: the origin is deleted and its port let go. */
    @Synchronized
    fun clear() {
        runCatching { store.clear() }
        held?.listener?.close()
        held = null
    }

    private companion object {
        val TOKEN_SHAPE = Regex("^[0-9a-f]{32}$")
    }
}

/**
 * A loopback listener that hands each connection to whoever serves it now, and closes it unanswered
 * when no one does.
 *
 * 🔴 Never answer before a request arrives. The browser opens connections ahead of need and pools
 * them by host and port; a refusal written into one while the sheet was closed sat unread until the
 * next open sent its document down that connection and read the stale refusal as the answer (the
 * journal then showed "couldn't reach your journal"). A connection closed unanswered the browser
 * simply replaces.
 */
internal class JournalBrowserListener(private val socket: ServerSocket) {
    val port: Int = socket.localPort
    val isClosed: Boolean get() = socket.isClosed

    @Volatile var handler: ((Socket) -> Unit)? = null

    init {
        Thread({ acceptLoop() }, "JournalBrowser-listener").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop() {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                if (socket.isClosed) break else continue
            }
            val serve = handler
            if (serve == null) {
                try { client.close() } catch (_: Exception) {}
            } else {
                try {
                    serve(client)
                } catch (_: Exception) {
                    try { client.close() } catch (_: Exception) {}
                }
            }
        }
    }

    fun close() {
        try { socket.close() } catch (_: Exception) {}
    }

}

private val tokenRandom = SecureRandom()

internal fun mintJournalBrowserToken(): String {
    val bytes = ByteArray(16)
    synchronized(tokenRandom) { tokenRandom.nextBytes(bytes) }
    return bytes.joinToString("") { "%02x".format(it) }
}

internal fun bindJournalBrowserLoopback(port: Int): ServerSocket? {
    val ss = ServerSocket()
    return try {
        // A kept port has to bind again while earlier connections to it sit in TIME_WAIT.
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
        ss
    } catch (_: IOException) {
        try { ss.close() } catch (_: Exception) {}
        null
    }
}
