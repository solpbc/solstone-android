// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.identity.PairingGeneration
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class JournalBrowserOrigin(val url: String)

class JournalBrowserIdentityException : IOException()

interface JournalBrowserUpstream : Closeable {
    val isPoisoned: Boolean
    fun request(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): BrowserHttpResponse
}

fun interface JournalBrowserUpstreamFactory {
    fun open(): JournalBrowserUpstream
}

class JournalBrowserSession(
    private val pairing: () -> PairingGeneration?,
    private val accessStillCurrent: () -> Boolean,
    private val upstreamFactory: JournalBrowserUpstreamFactory,
    private val diag: (DiagEvent) -> Unit,
) {
    private val lock = ReentrantLock()
    private val secureRandom = SecureRandom()
    private val pool = UpstreamPool(upstreamFactory)

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeToken: String? = null
    @Volatile private var activeOrigin: JournalBrowserOrigin? = null
    @Volatile private var pairingSnapshot: PairingGeneration? = null

    private var acceptThread: Thread? = null
    private var workerExecutor = Executors.newCachedThreadPool()

    // Concurrency controls
    private val activeClientSemaphore = Semaphore(MAX_ACTIVE_CLIENTS, true)
    private val responseBudgetSemaphore = Semaphore(RESPONSE_BUDGET_SLOTS, true) // 2 slots of 16 MiB = 32 MiB
    private val requestBudgetLock = ReentrantLock()
    private var allocatedRequestBodyBytes = 0

    private val inFlightConnections = AtomicInteger(0)

    fun start(bindPort: Int = 0): JournalBrowserOrigin = lock.withLock {
        val currentGen = pairing() ?: throw IllegalStateException("Cannot start browser session when unpaired")
        if (!accessStillCurrent()) {
            throw IllegalStateException("Access not current")
        }

        if (running && activeOrigin != null && pairingSnapshot == currentGen) {
            return activeOrigin!!
        }

        if (running) {
            stopInternal(emitStopped = false)
        }

        val tokenBytes = ByteArray(16)
        secureRandom.nextBytes(tokenBytes)
        val token = tokenBytes.joinToString("") { "%02x".format(it) }

        val socket = ServerSocket(bindPort, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        activeToken = token
        pairingSnapshot = currentGen
        val origin = JournalBrowserOrigin("http://$token.localhost:${socket.localPort}/")
        activeOrigin = origin
        running = true

        if (workerExecutor.isShutdown) {
            workerExecutor = Executors.newCachedThreadPool()
        }

        val thread = Thread({
            acceptLoop(socket, token, currentGen)
        }, "journal-browser-accept")
        acceptThread = thread
        thread.isDaemon = true
        thread.start()

        diag(DiagEvent.JournalBrowser(eventClass = "lifecycle", outcome = "bound"))
        return origin
    }

    fun stop() = lock.withLock {
        stopInternal(emitStopped = true)
        workerExecutor.shutdown()
    }

    private fun stopInternal(emitStopped: Boolean) {
        if (!running && serverSocket == null) return
        running = false
        activeToken = null
        activeOrigin = null
        pairingSnapshot = null

        val sock = serverSocket
        serverSocket = null
        try {
            sock?.close()
        } catch (_: Exception) {}

        val t = acceptThread
        acceptThread = null
        if (t != null && Thread.currentThread() != t) {
            try {
                t.join(1000)
            } catch (_: Exception) {}
        }

        pool.closeAll()

        if (emitStopped) {
            diag(DiagEvent.JournalBrowser(eventClass = "lifecycle", outcome = "stopped"))
        }
    }

    private fun triggerTerminal(eventClass: String, outcome: String) {
        diag(DiagEvent.JournalBrowser(eventClass = eventClass, outcome = outcome))
        lock.withLock {
            if (!running && serverSocket == null) return
            running = false
            activeToken = null
            activeOrigin = null
            pairingSnapshot = null

            val sock = serverSocket
            serverSocket = null
            try {
                sock?.close()
            } catch (_: Exception) {}

            pool.closeAll()
        }
    }

    private fun acceptLoop(socket: ServerSocket, expectedToken: String, expectedGen: PairingGeneration) {
        while (running && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                if (!running) break
                continue
            }

            if (!running) {
                try { client.close() } catch (_: Exception) {}
                break
            }

            val currentCount = inFlightConnections.incrementAndGet()
            if (currentCount > MAX_ACTIVE_CLIENTS + MAX_FIFO_WAITERS) {
                inFlightConnections.decrementAndGet()
                diag(DiagEvent.JournalBrowser(eventClass = "limit", outcome = "capacity"))
                sendDirectError(client, 503, "Service Unavailable")
                continue
            }

            try {
                workerExecutor.execute {
                    try {
                        processClient(client, expectedToken, expectedGen)
                    } finally {
                        inFlightConnections.decrementAndGet()
                    }
                }
            } catch (_: Exception) {
                inFlightConnections.decrementAndGet()
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun processClient(client: Socket, expectedToken: String, expectedGen: PairingGeneration) {
        client.soTimeout = HEADER_READ_TIMEOUT_MS
        val inputStream = BufferedInputStream(client.getInputStream())
        val outputStream = BufferedOutputStream(client.getOutputStream())

        val localOrigin = activeOrigin?.url
        if (!running || localOrigin == null) {
            sendErrorAndClose(client, outputStream, 502, "Bad Gateway")
            return
        }

        val requestHeaderBytes = ByteArrayOutputStream()
        var headerEnd = -1
        val buf = ByteArray(1024)

        try {
            while (headerEnd < 0) {
                if (requestHeaderBytes.size() > MAX_REQUEST_HEADERS_BYTES) {
                    diag(DiagEvent.JournalBrowser(eventClass = "limit", outcome = "rejected"))
                    sendErrorAndClose(client, outputStream, 431, "Request Header Fields Too Large")
                    return
                }
                val read = inputStream.read(buf)
                if (read < 0) {
                    try { client.close() } catch (_: Exception) {}
                    return
                }
                requestHeaderBytes.write(buf, 0, read)
                headerEnd = findHeaderEnd(requestHeaderBytes.toByteArray())
            }
        } catch (_: SocketTimeoutException) {
            sendErrorAndClose(client, outputStream, 408, "Request Timeout")
            return
        } catch (_: IOException) {
            try { client.close() } catch (_: Exception) {}
            return
        }

        val allHeaderBytes = requestHeaderBytes.toByteArray()
        val headerText = allHeaderBytes.copyOfRange(0, headerEnd).toString(Charsets.UTF_8)
        val lines = headerText.split(Regex("\r?\n"))
        if (lines.isEmpty() || lines[0].isBlank()) {
            sendErrorAndClose(client, outputStream, 400, "Bad Request")
            return
        }

        val requestLine = lines[0].trim()
        if (requestLine.length > MAX_REQUEST_LINE_BYTES) {
            diag(DiagEvent.JournalBrowser(eventClass = "limit", outcome = "rejected"))
            sendErrorAndClose(client, outputStream, 414, "URI Too Long")
            return
        }

        val requestLineParts = requestLine.split(" ", limit = 3)
        if (requestLineParts.size < 2) {
            sendErrorAndClose(client, outputStream, 400, "Bad Request")
            return
        }

        val method = requestLineParts[0].uppercase(Locale.US)
        val rawTarget = requestLineParts[1]

        val headers = mutableListOf<Pair<String, String>>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon < 0) {
                sendErrorAndClose(client, outputStream, 400, "Bad Request")
                return
            }
            headers.add(line.substring(0, colon).trim() to line.substring(colon + 1).trim())
        }

        if (headers.size > MAX_HEADER_COUNT) {
            diag(DiagEvent.JournalBrowser(eventClass = "limit", outcome = "rejected"))
            sendErrorAndClose(client, outputStream, 431, "Request Header Fields Too Large")
            return
        }

        if (method !in ALLOWED_METHODS) {
            val allowHeader = "Allow: ${ALLOWED_METHODS.joinToString(", ")}\r\n"
            sendResponseWithCustomHeaders(outputStream, 405, "Method Not Allowed", allowHeader, ByteArray(0))
            try { client.close() } catch (_: Exception) {}
            return
        }

        val boundPort = serverSocket?.localPort ?: -1

        // Validate request-target URI if absolute
        val targetPath: String
        if (rawTarget.startsWith("http://", ignoreCase = true) || rawTarget.startsWith("https://", ignoreCase = true)) {
            val targetUri = try {
                URI(rawTarget)
            } catch (_: Exception) {
                sendErrorAndClose(client, outputStream, 400, "Bad Request")
                return
            }
            val targetHost = targetUri.host?.lowercase(Locale.US)
            val targetPort = targetUri.port
            val expectedHost = "$expectedToken.localhost"
            if (targetHost != expectedHost || (targetPort != -1 && targetPort != boundPort)) {
                sendErrorAndClose(client, outputStream, 400, "Bad Request")
                return
            }
            targetPath = targetUri.rawPath.ifEmpty { "/" } + (targetUri.rawQuery?.let { "?$it" } ?: "")
        } else {
            targetPath = rawTarget
        }

        // Admission check on Host header
        val hostHeader = headers.firstOrNull { it.first.equals("host", ignoreCase = true) }?.second
        val validAdmission = isValidHostAdmission(hostHeader, expectedToken, boundPort)

        if (!validAdmission) {
            diag(DiagEvent.JournalBrowser(eventClass = "admission", outcome = "rejected"))
            sendErrorAndClose(client, outputStream, 400, "Bad Request")
            return
        }
        diag(DiagEvent.JournalBrowser(eventClass = "admission", outcome = "ok"))

        // Wait in FIFO queue for active client slot (bounded by REQUEST_DEADLINE_MS)
        if (!activeClientSemaphore.tryAcquire(REQUEST_DEADLINE_MS.toLong(), TimeUnit.MILLISECONDS)) {
            diag(DiagEvent.JournalBrowser(eventClass = "limit", outcome = "capacity"))
            sendErrorAndClose(client, outputStream, 503, "Service Unavailable")
            return
        }

        var acquiredRequestBudget = 0
        var acquiredResponseBudget = false
        try {
            // Verify pairing generation and access snapshot before upstream work
            val curGen = pairing()
            if (curGen == null || curGen != expectedGen || !accessStillCurrent()) {
                triggerTerminal(eventClass = "pairing", outcome = "invalidated")
                sendErrorAndClose(client, outputStream, 502, "Bad Gateway")
                return
            }

            // Request body processing
            val contentLengthHeader = headers.firstOrNull { it.first.equals("content-length", ignoreCase = true) }?.second
            val contentLength = contentLengthHeader?.toIntOrNull() ?: 0
            if (contentLength > MAX_REQUEST_BODY_BYTES) {
                diag(DiagEvent.JournalBrowser(eventClass = "limit", outcome = "rejected"))
                sendErrorAndClose(client, outputStream, 413, "Payload Too Large")
                return
            }

            val alreadyReadBodyBytes = allHeaderBytes.size - headerEnd
            var bodyBytes: ByteArray? = null

            if (contentLength > 0) {
                if (!acquireRequestBodyBudget(contentLength)) {
                    diag(DiagEvent.JournalBrowser(eventClass = "limit", outcome = "capacity"))
                    sendErrorAndClose(client, outputStream, 503, "Service Unavailable")
                    return
                }
                acquiredRequestBudget = contentLength

                try {
                    client.soTimeout = IDLE_READ_TIMEOUT_MS
                    val bodyStream = ByteArrayOutputStream()
                    if (alreadyReadBodyBytes > 0) {
                        bodyStream.write(allHeaderBytes, headerEnd, minOf(alreadyReadBodyBytes, contentLength))
                    }
                    while (bodyStream.size() < contentLength) {
                        val toRead = minOf(1024, contentLength - bodyStream.size())
                        val read = inputStream.read(buf, 0, toRead)
                        if (read < 0) {
                            sendErrorAndClose(client, outputStream, 400, "Bad Request")
                            return
                        }
                        bodyStream.write(buf, 0, read)
                    }
                    bodyBytes = bodyStream.toByteArray()
                } catch (_: SocketTimeoutException) {
                    sendErrorAndClose(client, outputStream, 408, "Request Timeout")
                    return
                } catch (_: IOException) {
                    try { client.close() } catch (_: Exception) {}
                    return
                }
            }

            // Acquire response memory reservation (16 MiB slot from 32 MiB budget)
            acquiredResponseBudget = responseBudgetSemaphore.tryAcquire(REQUEST_DEADLINE_MS.toLong(), TimeUnit.MILLISECONDS)
            if (!acquiredResponseBudget) {
                diag(DiagEvent.JournalBrowser(eventClass = "limit", outcome = "capacity"))
                sendErrorAndClose(client, outputStream, 503, "Service Unavailable")
                return
            }

            val filteredHeaders = filterRequestHeaders(headers)
            val isIdempotent = method in IDEMPOTENT_METHODS
            var response: BrowserHttpResponse? = null
            var isAmbiguousFailure = false
            var isTimeoutFailure = false
            var isIdentityFailure = false

            var upstream: JournalBrowserUpstream? = null
            try {
                upstream = pool.acquire()
                response = upstream.request(method, targetPath, filteredHeaders, bodyBytes)
                pool.release(upstream)
                upstream = null
            } catch (e: JournalBrowserIdentityException) {
                isIdentityFailure = true
                upstream?.let { pool.discard(it) }
                upstream = null
            } catch (e: SocketTimeoutException) {
                isTimeoutFailure = true
                upstream?.let { pool.discard(it) }
                upstream = null
            } catch (e: Exception) {
                upstream?.let { pool.discard(it) }
                upstream = null
                if (isIdempotent && pairing() == expectedGen && accessStillCurrent()) {
                    // Retry idempotent once with a fresh client
                    try {
                        val freshUpstream = pool.acquireFresh()
                        response = freshUpstream.request(method, targetPath, filteredHeaders, bodyBytes)
                        pool.release(freshUpstream)
                        diag(DiagEvent.JournalBrowser(eventClass = "retry", outcome = "ok"))
                    } catch (ie: JournalBrowserIdentityException) {
                        isIdentityFailure = true
                    } catch (te: SocketTimeoutException) {
                        isTimeoutFailure = true
                    } catch (_: Exception) {
                        // Retry failed
                    }
                } else if (!isIdempotent) {
                    isAmbiguousFailure = true
                }
            }

            if (isIdentityFailure) {
                triggerTerminal(eventClass = "pairing", outcome = "invalidated")
                sendErrorAndClose(client, outputStream, 502, "Bad Gateway")
                return
            }

            if (isTimeoutFailure) {
                diag(DiagEvent.JournalBrowser(eventClass = "proxy", outcome = "timeout"))
                sendErrorAndClose(client, outputStream, 504, "Gateway Timeout")
                return
            }

            if (isAmbiguousFailure) {
                diag(DiagEvent.JournalBrowser(eventClass = "proxy", outcome = "ambiguous"))
                sendSyntheticBody(outputStream, 502, "Bad Gateway", "AMBIGUOUS_DELIVERY".encodeToByteArray())
                try { client.close() } catch (_: Exception) {}
                return
            }

            if (response == null) {
                diag(DiagEvent.JournalBrowser(eventClass = "proxy", outcome = "rejected"))
                sendErrorAndClose(client, outputStream, 502, "Bad Gateway")
                return
            }

            // Check pairing generation again before writing bytes to client
            val finalGen = pairing()
            if (finalGen == null || finalGen != expectedGen || !accessStillCurrent()) {
                triggerTerminal(eventClass = "pairing", outcome = "invalidated")
                sendErrorAndClose(client, outputStream, 502, "Bad Gateway")
                return
            }

            val isHead = method == "HEAD"
            val formattedHeaders = filterAndFormatResponseHeaders(
                upstreamHeaders = response.headers,
                responseBodySize = response.body.size,
                localOriginUrl = localOrigin,
                statusCode = response.status,
            )

            if (formattedHeaders == null) {
                // Redirect rebase rejected
                diag(DiagEvent.JournalBrowser(eventClass = "redirect", outcome = "rejected"))
                sendErrorAndClose(client, outputStream, 502, "Bad Gateway")
                return
            }

            try {
                writeResponse(
                    out = outputStream,
                    status = response.status,
                    headers = formattedHeaders,
                    body = if (isHead) ByteArray(0) else response.body,
                )
                diag(DiagEvent.JournalBrowser(eventClass = "proxy", outcome = "ok"))
            } catch (_: Exception) {
            } finally {
                try { client.close() } catch (_: Exception) {}
            }
        } finally {
            if (acquiredResponseBudget) {
                responseBudgetSemaphore.release()
            }
            if (acquiredRequestBudget > 0) {
                releaseRequestBodyBudget(acquiredRequestBudget)
            }
            activeClientSemaphore.release()
        }
    }

    private fun isValidHostAdmission(hostHeader: String?, expectedToken: String, boundPort: Int): Boolean {
        if (hostHeader.isNullOrBlank()) return false
        val trimmed = hostHeader.trim().lowercase(Locale.US)
        if (trimmed.contains("@")) return false // reject userinfo
        val expectedWithPort = "$expectedToken.localhost:$boundPort"
        val expectedNoPort = "$expectedToken.localhost"
        return trimmed == expectedWithPort || trimmed == expectedNoPort
    }

    private fun acquireRequestBodyBudget(bytes: Int): Boolean {
        requestBudgetLock.withLock {
            if (allocatedRequestBodyBytes + bytes <= MAX_GLOBAL_REQUEST_BUDGET_BYTES) {
                allocatedRequestBodyBytes += bytes
                return true
            }
            return false
        }
    }

    private fun releaseRequestBodyBudget(bytes: Int) {
        requestBudgetLock.withLock {
            allocatedRequestBodyBytes = (allocatedRequestBodyBytes - bytes).coerceAtLeast(0)
        }
    }

    private fun writeResponse(
        out: BufferedOutputStream,
        status: Int,
        headers: List<Pair<String, String>>,
        body: ByteArray,
    ) {
        val statusText = when (status) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            206 -> "Partial Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            307 -> "Temporary Redirect"
            308 -> "Permanent Redirect"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            410 -> "Gone"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Status"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(" ").append(statusText).append("\r\n")
        for ((name, value) in headers) {
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

    private fun sendErrorAndClose(client: Socket, out: BufferedOutputStream, status: Int, statusText: String) {
        try {
            sendResponseWithCustomHeaders(out, status, statusText, "", ByteArray(0))
        } catch (_: Exception) {}
        try {
            client.close()
        } catch (_: Exception) {}
    }

    private fun sendDirectError(client: Socket, status: Int, statusText: String) {
        try {
            val out = BufferedOutputStream(client.getOutputStream())
            sendResponseWithCustomHeaders(out, status, statusText, "", ByteArray(0))
        } catch (_: Exception) {}
        try {
            client.close()
        } catch (_: Exception) {}
    }

    private fun sendSyntheticBody(out: BufferedOutputStream, status: Int, statusText: String, body: ByteArray) {
        val extra = "Content-Type: text/plain\r\nReferrer-Policy: no-referrer\r\n"
        sendResponseWithCustomHeaders(out, status, statusText, extra, body)
    }

    private fun sendResponseWithCustomHeaders(
        out: BufferedOutputStream,
        status: Int,
        statusText: String,
        extraHeaders: String,
        body: ByteArray,
    ) {
        val response = "HTTP/1.1 $status $statusText\r\n" +
            "Connection: close\r\n" +
            "Content-Length: ${body.size}\r\n" +
            extraHeaders +
            "\r\n"
        out.write(response.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

    private fun findHeaderEnd(raw: ByteArray): Int {
        for (i in 0 until raw.size - 3) {
            if (raw[i] == '\r'.code.toByte() &&
                raw[i + 1] == '\n'.code.toByte() &&
                raw[i + 2] == '\r'.code.toByte() &&
                raw[i + 3] == '\n'.code.toByte()
            ) {
                return i + 4
            }
        }
        for (i in 0 until raw.size - 1) {
            if (raw[i] == '\n'.code.toByte() && raw[i + 1] == '\n'.code.toByte()) {
                return i + 2
            }
        }
        return -1
    }

    private class UpstreamPool(
        private val factory: JournalBrowserUpstreamFactory,
    ) {
        private val lock = ReentrantLock()
        private val pool = ArrayDeque<JournalBrowserUpstream>()

        fun acquire(): JournalBrowserUpstream = lock.withLock {
            while (pool.isNotEmpty()) {
                val candidate = pool.removeFirst()
                if (!candidate.isPoisoned) {
                    return candidate
                }
                try { candidate.close() } catch (_: Exception) {}
            }
            return factory.open()
        }

        fun acquireFresh(): JournalBrowserUpstream = factory.open()

        fun release(upstream: JournalBrowserUpstream) = lock.withLock {
            if (upstream.isPoisoned) {
                try { upstream.close() } catch (_: Exception) {}
            } else if (pool.size < MAX_UPSTREAM_CLIENTS) {
                pool.addLast(upstream)
            } else {
                try { upstream.close() } catch (_: Exception) {}
            }
        }

        fun discard(upstream: JournalBrowserUpstream) = lock.withLock {
            try { upstream.close() } catch (_: Exception) {}
        }

        fun closeAll() = lock.withLock {
            while (pool.isNotEmpty()) {
                val candidate = pool.removeFirst()
                try { candidate.close() } catch (_: Exception) {}
            }
        }
    }

    companion object {
        const val MAX_ACTIVE_CLIENTS = 4
        const val MAX_FIFO_WAITERS = 8
        const val RESPONSE_BUDGET_SLOTS = 2 // 2 x 16 MiB = 32 MiB
        const val MAX_UPSTREAM_CLIENTS = 4
        const val MAX_REQUEST_BODY_BYTES = 8 * 1024 * 1024 // 8 MiB
        const val MAX_GLOBAL_REQUEST_BUDGET_BYTES = 16 * 1024 * 1024 // 16 MiB
        const val MAX_REQUEST_LINE_BYTES = 8 * 1024 // 8 KiB
        const val MAX_REQUEST_HEADERS_BYTES = 64 * 1024 // 64 KiB
        const val MAX_HEADER_COUNT = 100

        const val HEADER_READ_TIMEOUT_MS = 10_000
        const val IDLE_READ_TIMEOUT_MS = 15_000
        const val REQUEST_DEADLINE_MS = 120_000

        val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
