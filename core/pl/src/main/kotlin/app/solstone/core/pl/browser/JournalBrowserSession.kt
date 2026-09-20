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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class JournalBrowserOrigin(val url: String) {
    override fun toString(): String = "JournalBrowserOrigin(REDACTED)"
}


class JournalBrowserIdentityException : IOException()

interface JournalBrowserUpstream : Closeable {
    val isPoisoned: Boolean
    fun request(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): BrowserHttpResponse

    fun requestStreaming(
        method: String,
        path: String,
        headers: List<Pair<String, String>>,
        bodySource: BrowserRequestBodySource?,
        responseSink: BrowserResponseSink,
    ) {
        val bodyBytes = bodySource?.let { src ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = src.readChunk(buf, 0, buf.size)
                if (read < 0) break
                if (read > 0) out.write(buf, 0, read)
            }
            out.toByteArray()
        }
        val headerMap = mutableMapOf<String, String>()
        for ((k, v) in headers) {
            headerMap[k] = v
        }
        val response = request(method, path, headerMap, bodyBytes)
        val resHeaders = response.headers.toMutableList()
        if (resHeaders.none { it.first.equals("content-length", ignoreCase = true) } &&
            resHeaders.none { it.first.equals("transfer-encoding", ignoreCase = true) }) {
            resHeaders.add("Content-Length" to response.body.size.toString())
        }
        val reason = reasonText(response.status)
        responseSink.onStatusAndHeaders(response.status, reason, resHeaders)
        if (response.body.isNotEmpty()) {
            responseSink.onBodyChunk(response.body, 0, response.body.size)
        }
        responseSink.onComplete()
    }
}

fun reasonText(status: Int, rawReason: String? = null): String {
    if (!rawReason.isNullOrBlank()) return rawReason
    return when (status) {
        100 -> "Continue"
        101 -> "Switching Protocols"
        103 -> "Early Hints"
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        205 -> "Reset Content"
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
        408 -> "Request Timeout"
        417 -> "Expectation Failed"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Response"
    }
}

fun interface JournalBrowserUpstreamFactory {
    fun open(): JournalBrowserUpstream
}

class JournalBrowserSession(
    private val publisher: app.solstone.core.identity.PairingPublisher? = null,
    private val pairing: () -> PairingGeneration? = { (publisher?.currentSnapshot() as? app.solstone.core.identity.PairingGraphSnapshot.Committed)?.pairing },
    private val accessStillCurrent: () -> Boolean = { publisher == null || publisher.currentSnapshot() is app.solstone.core.identity.PairingGraphSnapshot.Committed },
    private val upstreamFactory: JournalBrowserUpstreamFactory,
    private val diag: (DiagEvent) -> Unit,
) {
    constructor(
        publisher: app.solstone.core.identity.PairingPublisher,
        upstreamFactory: JournalBrowserUpstreamFactory,
        diag: (DiagEvent) -> Unit,
    ) : this(
        publisher = publisher,
        pairing = { (publisher.currentSnapshot() as? app.solstone.core.identity.PairingGraphSnapshot.Committed)?.pairing },
        accessStillCurrent = { publisher.currentSnapshot() is app.solstone.core.identity.PairingGraphSnapshot.Committed },
        upstreamFactory = upstreamFactory,
        diag = diag,
    )

    private val lock = ReentrantLock()
    private val secureRandom = SecureRandom()
    private var pool: UpstreamPool? = null

    private val epochCounter = AtomicLong(0)
    private val lifecycleListeners = CopyOnWriteArrayList<JournalBrowserLifecycleListener>()
    @Volatile private var currentLifecycle: JournalBrowserLifecycle = JournalBrowserLifecycle.Terminal(
        epoch = BrowserEpoch(0),
        reason = BrowserTerminalReason(BrowserTerminalClass.EXPLICIT_STOP),
    )
    val lifecycle: JournalBrowserLifecycle get() = currentLifecycle

    fun addLifecycleListener(listener: JournalBrowserLifecycleListener) {
        lifecycleListeners.add(listener)
        runCatching { listener.onStateChanged(currentLifecycle) }
    }

    fun removeLifecycleListener(listener: JournalBrowserLifecycleListener) {
        lifecycleListeners.remove(listener)
    }

    private fun setLifecycle(newState: JournalBrowserLifecycle) {
        currentLifecycle = newState
        for (l in lifecycleListeners) {
            runCatching { l.onStateChanged(newState) }
        }
    }

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeToken: String? = null
    @Volatile private var activeOrigin: JournalBrowserOrigin? = null
    @Volatile private var pairingSnapshot: PairingGeneration? = null
    @Volatile private var publisherSub: app.solstone.core.identity.SubscriptionHandle? = null

    private var acceptThread: Thread? = null
    private var workerExecutor = Executors.newCachedThreadPool()

    // Concurrency controls
    private val activeClientSemaphore = Semaphore(MAX_ACTIVE_CLIENTS, true)
    private val responseBudgetSemaphore = Semaphore(RESPONSE_BUDGET_SLOTS, true) // 2 slots of 16 MiB = 32 MiB
    private val requestBudgetLock = ReentrantLock()
    private var allocatedRequestBodyBytes = 0

    private val inFlightConnections = AtomicInteger(0)

    fun start(bindPort: Int = 0): JournalBrowserOrigin = lock.withLock {
        if (running && activeOrigin != null) {
            return activeOrigin!!
        }

        val snap = publisher?.currentSnapshot()
        if (snap != null) {
            when (snap) {
                is app.solstone.core.identity.PairingGraphSnapshot.Absent -> {
                    val epoch = BrowserEpoch(epochCounter.incrementAndGet())
                    setLifecycle(JournalBrowserLifecycle.Terminal(epoch, BrowserTerminalReason(BrowserTerminalClass.UNPAIRED_FORGOTTEN)))
                    throw IllegalStateException("Cannot start browser session when unpaired")
                }
                is app.solstone.core.identity.PairingGraphSnapshot.Uncertain -> {
                    val epoch = BrowserEpoch(epochCounter.incrementAndGet())
                    setLifecycle(JournalBrowserLifecycle.Terminal(epoch, BrowserTerminalReason(BrowserTerminalClass.PAIRING_UNCERTAIN)))
                    throw IllegalStateException("Cannot start browser session when pairing is uncertain")
                }
                is app.solstone.core.identity.PairingGraphSnapshot.Committed -> {
                    if (!snap.isDirectEligible && !snap.isRelayEligible) {
                        val epoch = BrowserEpoch(epochCounter.incrementAndGet())
                        setLifecycle(JournalBrowserLifecycle.Terminal(epoch, BrowserTerminalReason(BrowserTerminalClass.IDENTITY_AUTH_REFUSAL)))
                        throw IllegalStateException("Cannot start browser session without eligible route")
                    }
                }
            }
        }

        val currentGen = pairing() ?: run {
            val epoch = BrowserEpoch(epochCounter.incrementAndGet())
            setLifecycle(JournalBrowserLifecycle.Terminal(epoch, BrowserTerminalReason(BrowserTerminalClass.UNPAIRED_FORGOTTEN)))
            throw IllegalStateException("Cannot start browser session when unpaired")
        }
        if (!accessStillCurrent()) {
            val epoch = BrowserEpoch(epochCounter.incrementAndGet())
            setLifecycle(JournalBrowserLifecycle.Terminal(epoch, BrowserTerminalReason(BrowserTerminalClass.IDENTITY_AUTH_REFUSAL)))
            throw IllegalStateException("Cannot start browser session when access is not current")
        }

        val epoch = BrowserEpoch(epochCounter.incrementAndGet())
        setLifecycle(JournalBrowserLifecycle.Starting(epoch))

        val tokenBytes = ByteArray(16)
        secureRandom.nextBytes(tokenBytes)
        val token = tokenBytes.joinToString("") { "%02x".format(it) }

        val ss = try {
            ServerSocket(bindPort, 50, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            setLifecycle(JournalBrowserLifecycle.Terminal(epoch, BrowserTerminalReason(BrowserTerminalClass.BIND_FAILURE)))
            throw e
        }

        serverSocket = ss
        pool = UpstreamPool(upstreamFactory).also { newPool ->
            newPool.setCarrierFailureListener {
                triggerTerminalForEpoch(epoch, BrowserTerminalReason(BrowserTerminalClass.SESSION_CARRIER_LOSS))
            }
        }
        activeToken = token
        pairingSnapshot = currentGen
        val origin = JournalBrowserOrigin("http://$token.localhost:${ss.localPort}/")
        activeOrigin = origin
        running = true
        setLifecycle(JournalBrowserLifecycle.Live(epoch))

        diag(DiagEvent.JournalBrowser(eventClass = "lifecycle", outcome = "bound"))

        publisherSub = publisher?.subscribe { newSnap ->
            lock.withLock {
                if (!running || currentLifecycle.epoch != epoch) return@subscribe
                when (newSnap) {
                    is app.solstone.core.identity.PairingGraphSnapshot.Absent -> {
                        triggerTerminal(BrowserTerminalReason(BrowserTerminalClass.UNPAIRED_FORGOTTEN))
                    }
                    is app.solstone.core.identity.PairingGraphSnapshot.Uncertain -> {
                        triggerTerminal(BrowserTerminalReason(BrowserTerminalClass.PAIRING_UNCERTAIN))
                    }
                    is app.solstone.core.identity.PairingGraphSnapshot.Committed -> {
                        if (newSnap.pairing != currentGen) {
                            triggerTerminal(BrowserTerminalReason(BrowserTerminalClass.PAIRING_REPLACED))
                        } else if (!newSnap.isDirectEligible && !newSnap.isRelayEligible) {
                            triggerTerminal(BrowserTerminalReason(BrowserTerminalClass.IDENTITY_AUTH_REFUSAL))
                        }
                    }
                }
            }
        }

        acceptThread = Thread({
            acceptLoop(ss, token, currentGen, origin.url, epoch)
        }, "JournalBrowserSession-accept")
        acceptThread?.isDaemon = true
        acceptThread?.start()

        return origin
    }

    fun stop() = lock.withLock {
        if (!running) return
        val ep = currentLifecycle.epoch
        setLifecycle(JournalBrowserLifecycle.Terminal(ep, BrowserTerminalReason(BrowserTerminalClass.EXPLICIT_STOP)))
        stopInternal()
    }

    private fun stopInternal() {
        running = false
        publisherSub?.cancel()
        publisherSub = null
        diag(DiagEvent.JournalBrowser(eventClass = "lifecycle", outcome = "stopped"))
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        activeToken = null
        activeOrigin = null
        pairingSnapshot = null

        acceptThread?.interrupt()
        acceptThread = null

        workerExecutor.shutdownNow()
        workerExecutor = Executors.newCachedThreadPool()

        pool?.close()
        pool = null
    }

    fun triggerTerminal(reason: BrowserTerminalReason) {
        lock.withLock {
            if (!running) return
            val ep = currentLifecycle.epoch
            setLifecycle(JournalBrowserLifecycle.Terminal(ep, reason))
            stopInternal()
        }
    }

    private fun triggerTerminalForEpoch(epoch: BrowserEpoch, reason: BrowserTerminalReason) {
        lock.withLock {
            if (!running || currentLifecycle.epoch != epoch) return
            setLifecycle(JournalBrowserLifecycle.Terminal(epoch, reason))
            stopInternal()
        }
    }

    private fun triggerTerminalForEpoch(epoch: BrowserEpoch, eventClass: String, outcome: String) {
        diag(DiagEvent.JournalBrowser(eventClass = eventClass, outcome = outcome))
        val terminalClass = when (eventClass) {
            "pairing" -> BrowserTerminalClass.PAIRING_REPLACED
            "auth" -> BrowserTerminalClass.IDENTITY_AUTH_REFUSAL
            "trust" -> BrowserTerminalClass.TRUST_REFUSAL
            "carrier" -> BrowserTerminalClass.SESSION_CARRIER_LOSS
            else -> BrowserTerminalClass.TERMINAL_UPSTREAM_PROTOCOL
        }
        triggerTerminalForEpoch(epoch, BrowserTerminalReason(terminalClass))
    }




    private fun acceptLoop(
        ss: ServerSocket,
        expectedToken: String,
        expectedGen: PairingGeneration,
        localOrigin: String,
        expectedEpoch: BrowserEpoch,
    ) {
        while (running) {
            try {
                val client = ss.accept()
                val currentInFlight = inFlightConnections.incrementAndGet()

                // MAX_FIFO_WAITERS = 8, MAX_ACTIVE_CLIENTS = 4 -> max 12 in-flight
                if (currentInFlight > MAX_ACTIVE_CLIENTS + MAX_FIFO_WAITERS) {
                    inFlightConnections.decrementAndGet()
                    diag(DiagEvent.JournalBrowser(eventClass = "limit", outcome = "capacity"))
                    try {
                        val out = BufferedOutputStream(client.getOutputStream())
                        sendErrorAndClose(client, out, 503, "Service Unavailable")
                    } catch (_: Exception) {
                        try { client.close() } catch (_: Exception) {}
                    }
                    continue
                }

                workerExecutor.submit {
                    try {
                        processClient(client, expectedToken, expectedGen, localOrigin, expectedEpoch)
                    } catch (_: Exception) {
                    } finally {
                        inFlightConnections.decrementAndGet()
                    }
                }
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun processClient(
        client: Socket,
        expectedToken: String,
        expectedGen: PairingGeneration,
        localOrigin: String,
        expectedEpoch: BrowserEpoch,
    ) {
        val inputStream = BufferedInputStream(client.getInputStream())
        val outputStream = BufferedOutputStream(client.getOutputStream())

        val buf = ByteArray(1024)
        val headerStream = ByteArrayOutputStream()
        var headerEnd = -1

        try {
            client.soTimeout = IDLE_READ_TIMEOUT_MS
            while (headerEnd == -1) {
                val read = inputStream.read(buf)
                if (read < 0) {
                    try { client.close() } catch (_: Exception) {}
                    return
                }
                headerStream.write(buf, 0, read)
                headerEnd = findHeaderEnd(headerStream.toByteArray())
                if (headerEnd == -1 && headerStream.size() > MAX_REQUEST_HEADERS_BYTES) {
                    diag(DiagEvent.JournalBrowser(eventClass = "limit", outcome = "rejected"))
                    sendErrorAndClose(client, outputStream, 431, "Request Header Fields Too Large")
                    return
                }
            }
        } catch (_: SocketTimeoutException) {
            sendErrorAndClose(client, outputStream, 408, "Request Timeout")
            return
        } catch (_: IOException) {
            try { client.close() } catch (_: Exception) {}
            return
        }

        val allHeaderBytes = headerStream.toByteArray()
        val headerText = String(allHeaderBytes, 0, headerEnd, Charsets.US_ASCII)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty() || lines[0].isBlank()) {
            sendErrorAndClose(client, outputStream, 400, "Bad Request")
            return
        }

        val requestLine = lines[0]
        val reqLineBytes = requestLine.toByteArray(Charsets.US_ASCII)
        if (reqLineBytes.size > MAX_REQUEST_LINE_BYTES) {
            diag(DiagEvent.JournalBrowser(eventClass = "limit", outcome = "rejected"))
            sendErrorAndClose(client, outputStream, 414, "URI Too Long")
            return
        }

        val requestParts = requestLine.split(" ")
        if (requestParts.size != 3) {
            sendErrorAndClose(client, outputStream, 400, "Bad Request")
            return
        }

        val method = requestParts[0].uppercase(Locale.ROOT)
        val rawPath = requestParts[1]
        val httpVersion = requestParts[2]

        if (lines.size - 1 > MAX_REQUEST_HEADERS_COUNT) {
            diag(DiagEvent.JournalBrowser(eventClass = "limit", outcome = "rejected"))
            sendErrorAndClose(client, outputStream, 431, "Request Header Fields Too Large")
            return
        }

        val headers = mutableListOf<Pair<String, String>>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val colonIndex = line.indexOf(':')
            if (colonIndex == -1) {
                sendErrorAndClose(client, outputStream, 400, "Bad Request")
                return
            }
            val key = line.substring(0, colonIndex).trim()
            val value = line.substring(colonIndex + 1).trim()
            headers.add(key to value)
        }

        if (method in DISALLOWED_METHODS) {
            diag(DiagEvent.JournalBrowser(eventClass = "admission", outcome = "rejected"))
            val allowed = "GET, HEAD, POST, PUT, DELETE, PATCH, OPTIONS"
            val headersWithAllow = listOf("Allow: $allowed", "Referrer-Policy: no-referrer")
            sendResponseHeadersAndClose(client, outputStream, 405, "Method Not Allowed", headersWithAllow)
            return
        }

        if (method !in ALLOWED_METHODS) {
            diag(DiagEvent.JournalBrowser(eventClass = "admission", outcome = "rejected"))
            sendErrorAndClose(client, outputStream, 400, "Bad Request")
            return
        }

        val targetPath: String
        try {
            val uri = URI(rawPath)
            if (uri.isAbsolute) {
                if (!isValidHostAdmission(uri.host, expectedToken, serverSocket?.localPort ?: 0)) {
                    diag(DiagEvent.JournalBrowser(eventClass = "admission", outcome = "rejected"))
                    sendErrorAndClose(client, outputStream, 400, "Bad Request")
                    return
                }
                targetPath = uri.rawPath.ifEmpty { "/" } + if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
            } else {
                targetPath = rawPath
            }
        } catch (_: Exception) {
            sendErrorAndClose(client, outputStream, 400, "Bad Request")
            return
        }

        val hostHeader = headers.firstOrNull { it.first.equals("host", ignoreCase = true) }?.second
        val boundPort = serverSocket?.localPort ?: 0
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
                triggerTerminalForEpoch(expectedEpoch, eventClass = "pairing", outcome = "invalidated")
                sendErrorAndClose(client, outputStream, 502, "Bad Gateway")
                return
            }

            // Acquire response memory reservation (16 MiB slot from 32 MiB budget)
            acquiredResponseBudget = responseBudgetSemaphore.tryAcquire(REQUEST_DEADLINE_MS.toLong(), TimeUnit.MILLISECONDS)
            if (!acquiredResponseBudget) {
                diag(DiagEvent.JournalBrowser(eventClass = "limit", outcome = "capacity"))
                sendErrorAndClose(client, outputStream, 503, "Service Unavailable")
                return
            }

            // Expect: 100-continue handling after all 4 admission gates (FIFO, active-client, body budget, response slot)
            val expectHeaders = headers.filter { it.first.equals("expect", ignoreCase = true) }
            var browserSinkAcceptedBytes = false

            if (expectHeaders.isNotEmpty()) {
                if (expectHeaders.size > 1 || expectHeaders.any { it.second.contains(",") } ||
                    !expectHeaders[0].second.trim().equals("100-continue", ignoreCase = true)
                ) {
                    sendErrorAndClose(client, outputStream, 417, "Expectation Failed")
                    return
                }
                // Emit exactly one bridge 100 Continue
                outputStream.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                outputStream.flush()
                browserSinkAcceptedBytes = true
            }

            // Request body source streaming
            val contentLengthHeader = headers.firstOrNull { it.first.equals("content-length", ignoreCase = true) }?.second
            val transferEncodingHeader = headers.firstOrNull { it.first.equals("transfer-encoding", ignoreCase = true) }?.second
            val contentLength = contentLengthHeader?.toLongOrNull() ?: 0L
            val isChunked = transferEncodingHeader?.contains("chunked", ignoreCase = true) == true
            val hasBody = (contentLength > 0L) || isChunked

            val bodySource: BrowserRequestBodySource? = if (hasBody) {
                var alreadyReadRemaining = allHeaderBytes.size - headerEnd
                var alreadyReadOffset = headerEnd
                var bodyBytesReadSoFar = 0L
                BrowserRequestBodySource { target, offset, length ->
                    if (!isChunked && contentLength > 0L && bodyBytesReadSoFar >= contentLength) {
                        return@BrowserRequestBodySource -1
                    }
                    if (alreadyReadRemaining > 0) {
                        val toCopy = minOf(length, alreadyReadRemaining)
                        System.arraycopy(allHeaderBytes, alreadyReadOffset, target, offset, toCopy)
                        alreadyReadOffset += toCopy
                        alreadyReadRemaining -= toCopy
                        bodyBytesReadSoFar += toCopy
                        toCopy
                    } else {
                        val maxToRead = if (!isChunked && contentLength > 0L) {
                            minOf(length.toLong(), contentLength - bodyBytesReadSoFar).toInt()
                        } else {
                            length
                        }
                        if (maxToRead <= 0) {
                            -1
                        } else {
                            val read = inputStream.read(target, offset, maxToRead)
                            if (read > 0) {
                                bodyBytesReadSoFar += read
                            }
                            read
                        }
                    }
                }
            } else {
                null
            }

            val filteredHeaders = filterRequestHeadersOrdered(headers).filterNot { 
                it.first.equals("expect", ignoreCase = true)
            }

            val isIdempotent = method in IDEMPOTENT_METHODS
            var isAmbiguousFailure = false
            var isTimeoutFailure = false
            var isIdentityFailure = false
            var completedSuccessfully = false
            // 🔴 The parser hands this sink DE-CHUNKED body bytes. When the response we announce to
            // the browser is `Transfer-Encoding: chunked` — which is every response the journal
            // sends without a Content-Length, and so every long-lived one — those bytes have to be
            // re-framed as chunks on the way out. Writing them raw under a chunked announcement is
            // a malformed response: the browser reads the first body line as a chunk-size line,
            // fails to parse it as hex and drops the connection immediately. That is what killed
            // the journal's own live channel (`EventSource('/sse/events')`) inside the app, which
            // an owner saw as a permanent *connection lost* card over the journal's home.
            var emitChunkedFraming = false
            var pendingTrailers: List<Pair<String, String>> = emptyList()

            val responseSink = object : BrowserResponseSink {
                override fun onStatusAndHeaders(status: Int, reason: String, resHeaders: List<Pair<String, String>>) {
                    val finalGen = pairing()
                    if (finalGen == null || finalGen != expectedGen || !accessStillCurrent()) {
                        triggerTerminalForEpoch(expectedEpoch, eventClass = "pairing", outcome = "invalidated")
                        throw JournalBrowserIdentityException()
                    }
                    val formatted = filterAndFormatResponseHeaders(
                        upstreamHeaders = resHeaders,
                        responseBodySize = null,
                        localOriginUrl = localOrigin,
                        statusCode = status,
                    ) ?: throw IOException("Redirect rebase rejected")

                    emitChunkedFraming = method != "HEAD" && formatted.any {
                        it.first.equals("transfer-encoding", ignoreCase = true) &&
                            it.second.contains("chunked", ignoreCase = true)
                    }

                    val sb = StringBuilder()
                    val reasonStr = reasonText(status, reason)
                    sb.append("HTTP/1.1 $status $reasonStr\r\n")
                    for ((k, v) in formatted) {
                        sb.append("$k: $v\r\n")
                    }
                    sb.append("\r\n")
                    outputStream.write(sb.toString().toByteArray(Charsets.US_ASCII))
                    outputStream.flush()
                    browserSinkAcceptedBytes = true
                }

                override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {
                    if (method != "HEAD" && length > 0) {
                        if (emitChunkedFraming) {
                            outputStream.write(
                                "${length.toString(16)}\r\n".toByteArray(Charsets.US_ASCII),
                            )
                        }
                        outputStream.write(chunk, offset, length)
                        if (emitChunkedFraming) {
                            outputStream.write(CRLF)
                        }
                        outputStream.flush()
                        browserSinkAcceptedBytes = true
                    }
                }

                override fun onTrailers(trailers: List<Pair<String, String>>) {
                    pendingTrailers = trailers
                }

                override fun onComplete() {
                    if (emitChunkedFraming) {
                        val end = StringBuilder("0\r\n")
                        for ((k, v) in pendingTrailers) {
                            end.append("$k: $v\r\n")
                        }
                        end.append("\r\n")
                        outputStream.write(end.toString().toByteArray(Charsets.US_ASCII))
                        emitChunkedFraming = false
                    }
                    outputStream.flush()
                    completedSuccessfully = true
                }

                override fun onError(cause: Throwable) {
                    if (cause is JournalBrowserIdentityException) {
                        isIdentityFailure = true
                    } else if (cause is SocketTimeoutException) {
                        isTimeoutFailure = true
                    }
                }
            }

            var upstream: JournalBrowserUpstream? = null
            val requestPool = pool
            if (requestPool == null || currentLifecycle.epoch != expectedEpoch) {
                sendErrorAndClose(client, outputStream, 503, "Service Unavailable")
                return
            }
            try {
                upstream = requestPool.acquire()
                upstream.requestStreaming(method, targetPath, filteredHeaders, bodySource, responseSink)
                requestPool.release(upstream)
                upstream = null
            } catch (e: JournalBrowserIdentityException) {
                isIdentityFailure = true
                upstream?.let { requestPool.discard(it) }
                upstream = null
            } catch (e: SocketTimeoutException) {
                isTimeoutFailure = true
                upstream?.let { requestPool.discard(it) }
                upstream = null
            } catch (e: Exception) {
                upstream?.let { requestPool.discard(it) }
                upstream = null
                if (isIdempotent && !browserSinkAcceptedBytes && pairing() == expectedGen && accessStillCurrent()) {
                    // Retry idempotent once with a fresh client before any browser sink byte
                    try {
                        val freshUpstream = requestPool.acquireFresh()
                        freshUpstream.requestStreaming(method, targetPath, filteredHeaders, bodySource, responseSink)
                        requestPool.release(freshUpstream)
                        diag(DiagEvent.JournalBrowser(eventClass = "retry", outcome = "ok"))
                    } catch (ie: JournalBrowserIdentityException) {
                        isIdentityFailure = true
                    } catch (te: SocketTimeoutException) {
                        isTimeoutFailure = true
                    } catch (_: Exception) {
                    }
                } else if (!isIdempotent && !browserSinkAcceptedBytes) {
                    isAmbiguousFailure = true
                }
            }

            if (!completedSuccessfully) {
                if (isIdentityFailure) {
                    triggerTerminalForEpoch(expectedEpoch, eventClass = "pairing", outcome = "invalidated")
                    if (!browserSinkAcceptedBytes) {
                        sendErrorAndClose(client, outputStream, 502, "Bad Gateway")
                    }
                    return
                }
                if (isTimeoutFailure) {
                    diag(DiagEvent.JournalBrowser(eventClass = "proxy", outcome = "timeout"))
                    if (!browserSinkAcceptedBytes) {
                        sendErrorAndClose(client, outputStream, 504, "Gateway Timeout")
                    }
                    return
                }
                if (isAmbiguousFailure) {
                    diag(DiagEvent.JournalBrowser(eventClass = "proxy", outcome = "ambiguous"))
                    if (!browserSinkAcceptedBytes) {
                        sendSyntheticBody(outputStream, 502, "Bad Gateway", "AMBIGUOUS_DELIVERY".encodeToByteArray())
                    }
                    try { client.close() } catch (_: Exception) {}
                    return
                }
                if (!browserSinkAcceptedBytes) {
                    diag(DiagEvent.JournalBrowser(eventClass = "proxy", outcome = "rejected"))
                    sendErrorAndClose(client, outputStream, 502, "Bad Gateway")
                    return
                }
            } else {
                diag(DiagEvent.JournalBrowser(eventClass = "proxy", outcome = "ok"))
            }
        } finally {
            try { client.close() } catch (_: Exception) {}
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

    private fun acquireRequestBodyBudget(bytes: Int): Boolean = requestBudgetLock.withLock {
        if (allocatedRequestBodyBytes + bytes > MAX_GLOBAL_REQUEST_BUDGET_BYTES) {
            false
        } else {
            allocatedRequestBodyBytes += bytes
            true
        }
    }

    private fun releaseRequestBodyBudget(bytes: Int) = requestBudgetLock.withLock {
        allocatedRequestBodyBytes = maxOf(0, allocatedRequestBodyBytes - bytes)
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == '\r'.code.toByte() &&
                bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() &&
                bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i + 4
            }
        }
        return -1
    }

    private fun sendErrorAndClose(client: Socket, out: BufferedOutputStream, status: Int, message: String) {
        val headers = listOf("Content-Type: text/plain", "Referrer-Policy: no-referrer")
        sendResponseHeadersAndClose(client, out, status, message, headers, message.encodeToByteArray())
    }

    private fun sendSyntheticBody(out: BufferedOutputStream, status: Int, statusText: String, body: ByteArray) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status $statusText\r\n")
        sb.append("Content-Type: text/plain\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun sendResponseHeadersAndClose(
        client: Socket,
        out: BufferedOutputStream,
        status: Int,
        message: String,
        headers: List<String>,
        body: ByteArray? = null,
    ) {
        try {
            val sb = StringBuilder()
            sb.append("HTTP/1.1 $status $message\r\n")
            for (h in headers) {
                sb.append(h).append("\r\n")
            }
            if (body != null) {
                sb.append("Content-Length: ${body.size}\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            if (body != null && body.isNotEmpty()) {
                out.write(body)
            }
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun writeResponse(
        out: BufferedOutputStream,
        status: Int,
        headers: List<String>,
        body: ByteArray,
    ) {
        val sb = StringBuilder()
        val reason = if (status == 200) "OK" else if (status == 204) "No Content" else "Response"
        sb.append("HTTP/1.1 $status $reason\r\n")
        for (h in headers) {
            sb.append(h).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

    private class UpstreamPool(
        private val factory: JournalBrowserUpstreamFactory,
    ) : Closeable {
        private val lock = ReentrantLock()
        private val idle = ArrayDeque<JournalBrowserUpstream>()
        private val all = mutableListOf<JournalBrowserUpstream>()
        @Volatile private var closed = false
        private var carrierFailureListener: (() -> Unit)? = null

        fun setCarrierFailureListener(listener: () -> Unit) {
            this.carrierFailureListener = listener
        }

        fun notifyCarrierFailure() {
            carrierFailureListener?.invoke()
        }

        fun acquire(): JournalBrowserUpstream = lock.withLock {

            if (closed) throw IOException("Pool is closed")
            while (idle.isNotEmpty()) {
                val candidate = idle.removeFirst()
                if (!candidate.isPoisoned) {
                    return candidate
                }
                all.remove(candidate)
                try { candidate.close() } catch (_: Exception) {}
            }
            if (all.size >= MAX_UPSTREAM_CLIENTS) {
                throw IOException("Upstream pool exhausted")
            }
            val fresh = factory.open()
            all.add(fresh)
            return fresh
        }

        fun acquireFresh(): JournalBrowserUpstream = lock.withLock {
            if (closed) throw IOException("Pool is closed")
            if (all.size >= MAX_UPSTREAM_CLIENTS) {
                // If pool full, evict an idle one
                if (idle.isNotEmpty()) {
                    val evict = idle.removeFirst()
                    all.remove(evict)
                    try { evict.close() } catch (_: Exception) {}
                } else {
                    throw IOException("Upstream pool exhausted for fresh client")
                }
            }
            val fresh = factory.open()
            all.add(fresh)
            return fresh
        }

        fun release(upstream: JournalBrowserUpstream) = lock.withLock {
            if (closed || upstream.isPoisoned) {
                all.remove(upstream)
                try { upstream.close() } catch (_: Exception) {}
            } else {
                idle.addLast(upstream)
            }
        }

        fun discard(upstream: JournalBrowserUpstream) = lock.withLock {
            all.remove(upstream)
            idle.remove(upstream)
            try { upstream.close() } catch (_: Exception) {}
        }

        override fun close() = lock.withLock {
            closed = true
            for (u in all) {
                try { u.close() } catch (_: Exception) {}
            }
            all.clear()
            idle.clear()
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
        const val MAX_REQUEST_HEADERS_COUNT = 100
        const val REQUEST_DEADLINE_MS = 120_000
        const val IDLE_READ_TIMEOUT_MS = 10_000
        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)

        val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
        val DISALLOWED_METHODS = setOf("TRACE", "CONNECT")
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
