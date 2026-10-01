// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.testing

import app.solstone.core.crypto.caSpkiFp16
import app.solstone.core.crypto.generateP256KeyPair
import app.solstone.core.crypto.jidFromCaPem
import app.solstone.core.crypto.pem
import app.solstone.core.crypto.pemToDer
import app.solstone.core.crypto.sha256
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.observer.INGEST_PATH
import app.solstone.core.observer.SEGMENTS_PATH
import app.solstone.core.pl.FLAG_CLOSE
import app.solstone.core.pl.FLAG_DATA
import app.solstone.core.pl.FLAG_OPEN
import app.solstone.core.pl.FLAG_PING
import app.solstone.core.pl.FLAG_PONG
import app.solstone.core.pl.Frame
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.decodeFrame
import app.solstone.core.pl.encodeFrame
import app.solstone.core.pl.indexOf
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import org.bouncycastle.asn1.pkcs.CertificationRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

data class StandInRecordedRequest(
    val method: String,
    val path: String,
    val body: ByteArray,
)

class JournalLoopbackStandIn(
    preferredPort: Int = 0,
) : AutoCloseable {
    val caKeyPair: KeyPair = generateP256KeyPair()
    val caCert: X509Certificate = selfSignedCertificate(caKeyPair, "Journal CA", isCa = true)
    val caPem: String = pem("CERTIFICATE", caCert.encoded)
    val caPinSpki: ByteArray = caSpkiFp16(caPem)
    val instanceId: String = jidFromCaPem(caPem)

    val serverKeyPair: KeyPair = generateP256KeyPair()
    val serverCert: X509Certificate = signedCertificate(
        subjectPublicKey = serverKeyPair.public,
        issuerKeyPair = caKeyPair,
        issuerSubject = "Journal CA",
        subjectName = "localhost",
        isCa = false,
    )

    var dropStatusProbe: Boolean = false
    var relayEnrollStatus: Int = 200

    val recordedRequests = CopyOnWriteArrayList<StandInRecordedRequest>()

    private val serverSocket: SSLServerSocket
    private val sslContext: SSLContext
    val port: Int
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var running = true

    init {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("server", serverKeyPair.private, PASSWORD, arrayOf(serverCert, caCert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, PASSWORD)
        }
        sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf<TrustManager>(TrustAllManager), SecureRandom())
        }
        serverSocket = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
        serverSocket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferredPort))
        serverSocket.enabledProtocols = arrayOf("TLSv1.3")
        serverSocket.needClientAuth = false
        serverSocket.wantClientAuth = false
        port = serverSocket.localPort

        executor.execute {
            while (running) {
                try {
                    val socket = serverSocket.accept()
                    activeSockets.add(socket)
                    executor.execute {
                        try {
                            handleConnection(socket)
                        } finally {
                            activeSockets.remove(socket)
                            runCatching { socket.close() }
                        }
                    }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }
    }

    fun pairLink(
        ip: ByteArray = byteArrayOf(127, 0, 0, 1),
        port: Int = this.port,
    ): String {
        val nonce = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val bytes = ByteArray(41)
        bytes[0] = 0x05
        bytes[1] = 0x01
        bytes[2] = 1
        bytes[3] = ((port shr 8) and 0xff).toByte()
        bytes[4] = (port and 0xff).toByte()
        ip.copyInto(bytes, 5)
        nonce.copyInto(bytes, 9)
        val directCaPin = sha256(caCert.encoded).copyOf(16)
        directCaPin.copyInto(bytes, 25)
        return "https://go.solstone.app/p#${encodeCrockford(bytes)}"
    }

    private fun handleConnection(socket: Socket) {
        try {
            val rawInput = socket.getInputStream()
            val output = socket.getOutputStream()
            val pushback = PushbackInputStream(rawInput, 8)
            val firstByte = pushback.read()
            if (firstByte < 0) return
            pushback.unread(firstByte)

            // If first byte is ASCII letter ('G', 'P', 'D', 'H', etc.), it's an HTTP request over TLS
            val isHttp = firstByte.toChar() in 'A'..'Z'
            if (isHttp) {
                handleHttpOrWebSocket(pushback, output, socket)
            } else {
                handleMuxStream(pushback, output, socket)
            }
        } catch (_: Exception) {
        }
    }

    private fun handleHttpOrWebSocket(input: InputStream, output: OutputStream, socket: Socket) {
        val headBytes = readHttpHeaderBytes(input) ?: return
        val headStr = String(headBytes, Charsets.ISO_8859_1)
        val lines = headStr.split("\r\n")
        val reqLine = lines.firstOrNull()?.split(" ") ?: return
        if (reqLine.size < 2) return
        val method = reqLine[0]
        val path = reqLine[1]

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }

        val isWsUpgrade = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
        if (isWsUpgrade) {
            val wsKey = headers["sec-websocket-key"] ?: ""
            val acceptSha = MessageDigest.getInstance("SHA-1").digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
            val accept = Base64.getEncoder().encodeToString(acceptSha)
            val wsResp = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n"
            synchronized(output) {
                output.write(wsResp.toByteArray(Charsets.US_ASCII))
                output.flush()
            }
            handleInnerTlsOverWebSocket(input, output, socket)
            return
        }

        // Plain HTTP POST / GET
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            readExactlyOrNull(input, contentLength) ?: ByteArray(0)
        } else {
            ByteArray(0)
        }

        recordedRequests.add(StandInRecordedRequest(method, path, body))

        if (path.startsWith("/enroll/device")) {
            val respJson = if (relayEnrollStatus == 200) {
                """{"status":"ok"}"""
            } else {
                """{"error":"unavailable"}"""
            }
            val respBytes = respJson.toByteArray(Charsets.UTF_8)
            val httpHead = "HTTP/1.1 $relayEnrollStatus Status\r\n" +
                "Content-Length: ${respBytes.size}\r\n" +
                "Content-Type: application/json\r\n\r\n"
            synchronized(output) {
                output.write(httpHead.toByteArray(Charsets.US_ASCII) + respBytes)
                output.flush()
            }
            return
        }

        val response = route(method, path, body)
        val httpHead = "HTTP/1.1 ${response.status} OK\r\n" +
            "Content-Length: ${response.body.size}\r\n" +
            "Content-Type: application/json\r\n\r\n"
        synchronized(output) {
            output.write(httpHead.toByteArray(Charsets.US_ASCII) + response.body)
            output.flush()
        }
    }

    private fun handleInnerTlsOverWebSocket(input: InputStream, output: OutputStream, socket: Socket) {
        val bridgeServer = ServerSocket()
        bridgeServer.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val clientSide = Socket("127.0.0.1", bridgeServer.localPort)
        val serverSide = bridgeServer.accept()
        bridgeServer.close()

        executor.execute {
            try {
                val buf = ByteArray(8192)
                val inStream = clientSide.getInputStream()
                while (running && !socket.isClosed) {
                    val r = inStream.read(buf)
                    if (r < 0) break
                    writeWsBinaryFrame(output, buf, 0, r)
                }
            } catch (_: Exception) {}
        }
        executor.execute {
            try {
                val outStream = clientSide.getOutputStream()
                while (running && !socket.isClosed) {
                    val frameBytes = readWsBinaryFrame(input) ?: break
                    outStream.write(frameBytes)
                    outStream.flush()
                }
            } catch (_: Exception) {}
        }

        try {
            val innerSsl = sslContext.socketFactory.createSocket(serverSide, "localhost", serverSide.port, true) as SSLSocket
            innerSsl.useClientMode = false
            innerSsl.enabledProtocols = arrayOf("TLSv1.3")
            handleMuxStream(innerSsl.getInputStream(), innerSsl.getOutputStream(), innerSsl)
        } catch (_: Exception) {}
    }

    private fun writeWsBinaryFrame(out: OutputStream, data: ByteArray, offset: Int, length: Int) {
        synchronized(out) {
            out.write(0x82)
            if (length < 126) {
                out.write(length)
            } else if (length <= 65535) {
                out.write(126)
                out.write((length shr 8) and 0xff)
                out.write(length and 0xff)
            } else {
                out.write(127)
                for (shift in 56 downTo 0 step 8) {
                    out.write(((length.toLong() shr shift) and 0xff).toInt())
                }
            }
            out.write(data, offset, length)
            out.flush()
        }
    }

    private fun readWsBinaryFrame(input: InputStream): ByteArray? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val opcode = b0 and 0x0f
        if (opcode == 8) return null
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) {
            val hi = input.read()
            val lo = input.read()
            if (hi < 0 || lo < 0) return null
            len = ((hi shl 8) or lo).toLong()
        } else if (len == 127L) {
            var acc = 0L
            for (i in 0 until 8) {
                val b = input.read()
                if (b < 0) return null
                acc = (acc shl 8) or (b and 0xff).toLong()
            }
            len = acc
        }
        val mask = if (masked) {
            readExactlyOrNull(input, 4) ?: return null
        } else null
        val payload = readExactlyOrNull(input, len.toInt()) ?: return null
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return payload
    }

    private fun readHttpHeaderBytes(input: InputStream): ByteArray? {
        val bout = ByteArrayOutputStream()
        val marker = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val buf = ByteArray(1)
        while (true) {
            val r = input.read(buf)
            if (r < 0) return null
            bout.write(buf[0].toInt())
            val curr = bout.toByteArray()
            if (curr.size >= 4 && indexOf(curr, marker) >= 0) {
                return curr
            }
        }
    }

    private fun handleMuxStream(input: InputStream, output: OutputStream, socket: Socket) {
        val activeStreams = ConcurrentHashMap<Int, ByteArrayOutputStream>()

        while (running && !socket.isClosed) {
            val frame = try {
                readFrame(input) ?: break
            } catch (_: Exception) {
                break
            }

            if (frame.streamId == 0) {
                if (frame.flags == FLAG_PING) {
                    synchronized(output) {
                        output.write(encodeFrame(0, FLAG_PONG, frame.payload))
                        output.flush()
                    }
                }
                continue
            }

            val streamId = frame.streamId
            if ((frame.flags and FLAG_OPEN) != 0 || (frame.flags and FLAG_DATA) != 0) {
                val streamBuf = activeStreams.computeIfAbsent(streamId) { ByteArrayOutputStream() }
                streamBuf.write(frame.payload)
            }

            if ((frame.flags and FLAG_CLOSE) != 0) {
                val streamBuf = activeStreams.remove(streamId) ?: ByteArrayOutputStream()
                val reqBytes = streamBuf.toByteArray()
                if (reqBytes.isNotEmpty()) {
                    handleHttpRequest(streamId, reqBytes, output, socket)
                }
            }
        }
    }

    private fun handleHttpRequest(
        streamId: Int,
        reqBytes: ByteArray,
        output: OutputStream,
        socket: Socket,
    ) {
        val marker = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val split = indexOf(reqBytes, marker)
        if (split < 0) return

        val head = String(reqBytes, 0, split, Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        val reqLine = lines.firstOrNull()?.split(" ") ?: return
        if (reqLine.size < 2) return

        val method = reqLine[0]
        val path = reqLine[1]
        val body = if (split + marker.size < reqBytes.size) {
            reqBytes.copyOfRange(split + marker.size, reqBytes.size)
        } else {
            ByteArray(0)
        }

        recordedRequests.add(StandInRecordedRequest(method, path, body))

        if (method == "GET" && path == "/app/network/api/status" && dropStatusProbe) {
            runCatching { socket.close() }
            return
        }

        val response = route(method, path, body)
        val httpHead = "HTTP/1.1 ${response.status} OK\r\n" +
            "Content-Length: ${response.body.size}\r\n" +
            "Content-Type: application/json\r\n" +
            "\r\n"
        val responseBytes = httpHead.toByteArray(Charsets.US_ASCII) + response.body
        synchronized(output) {
            output.write(encodeFrame(streamId, FLAG_DATA or FLAG_CLOSE, responseBytes))
            output.flush()
        }
    }

    private fun route(method: String, path: String, body: ByteArray): HttpResponse = when {
        method == "POST" && path.startsWith("/app/network/pair") -> {
            val csrPem = extractCsrFromPairBody(body)
            val clientKey = if (csrPem != null) {
                runCatching { extractPublicKeyFromCsr(csrPem) }.getOrNull() ?: generateP256KeyPair().public
            } else {
                generateP256KeyPair().public
            }
            val clientCert = signedCertificate(
                subjectPublicKey = clientKey,
                issuerKeyPair = caKeyPair,
                issuerSubject = "Journal CA",
                subjectName = "solstone-client",
                isCa = false,
            )
            val clientCertPem = pem("CERTIFICATE", clientCert.encoded)
            val fingerprint = "sha256:" + sha256Hex(clientCert.encoded)
            val json = """
            {
              "ca_chain": [${toJson(caPem)}],
              "client_cert": ${toJson(clientCertPem)},
              "instance_id": "$instanceId",
              "home_label": "Home",
              "home_attestation": "attestation.jwt",
              "fingerprint": "$fingerprint",
              "local_endpoints": [{"ip":"127.0.0.1","port":$port}]
            }
            """.trimIndent()
            HttpResponse(200, emptyMap(), json.toByteArray(Charsets.UTF_8))
        }
        method == "GET" && path == "/app/network/api/status" ->
            HttpResponse(200, emptyMap(), ByteArray(0))
        path.startsWith("/enroll/device") -> {
            if (relayEnrollStatus == 200) {
                HttpResponse(200, emptyMap(), """{"status":"ok"}""".toByteArray(Charsets.UTF_8))
            } else {
                HttpResponse(relayEnrollStatus, emptyMap(), """{"error":"unavailable"}""".toByteArray(Charsets.UTF_8))
            }
        }
        method == "GET" && path == "/app/network/api/clients/self" -> {
            val json = """{"protocol_version":1,"revision":1,"journal":{"name":"Home Journal","version":"1.2.3"},"reported":{"name":"Old Name","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null}"""
            HttpResponse(200, emptyMap(), json.toByteArray(Charsets.UTF_8))
        }
        method == "PUT" && path == "/app/network/api/clients/self" -> {
            val json = """{"protocol_version":1,"revision":2,"reported":{"name":"android","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null,"journal":{"name":"Home Journal","version":"1.2.3"}}"""
            HttpResponse(200, emptyMap(), json.toByteArray(Charsets.UTF_8))
        }
        method == "GET" && path == "/app/network/api/relay/access" -> {
            val json = """{"relay_origin":"https://link.solstone.app","device_token":"token-1","expires_at":null}"""
            HttpResponse(200, emptyMap(), json.toByteArray(Charsets.UTF_8))
        }
        method == "GET" && (path == "/app/network/api/identity" || path == "/app/network/api/version") -> {
            val json = """{"instance_id":"$instanceId","journal_name":"Home Journal","protocol_version":1,"revision":1,"reported":null,"owner_label":null,"display_label":"Phone","updated_at":null,"journal":{"name":"Home Journal","version":"1.2.3"}}"""
            HttpResponse(200, emptyMap(), json.toByteArray(Charsets.UTF_8))
        }
        method == "DELETE" && path.startsWith("/app/network/api/clients/") -> {
            val json = """{"status":"ok"}"""
            HttpResponse(200, emptyMap(), json.toByteArray(Charsets.UTF_8))
        }
        method == "GET" && path.startsWith(SEGMENTS_PATH) -> {
            val json = """{"items":[{"key":"remote","observed":true,"files":[]}],"total":1,"protocol_version":3}"""
            HttpResponse(200, emptyMap(), json.toByteArray(Charsets.UTF_8))
        }
        method == "POST" && path == INGEST_PATH -> {
            val bodyText = body.toString(Charsets.UTF_8)
            val name = if (bodyText.contains("b.bin")) "b.bin" else "a.bin"
            val sha = if (bodyText.contains("b.bin")) "sha-b" else "sha-a"
            val json = """{"status":"ok","segment":"srv-a","file_descriptors":[{"submitted":"$name","written":"$name","size":3,"sha256":"$sha","disposition":"written"}]}"""
            HttpResponse(200, emptyMap(), json.toByteArray(Charsets.UTF_8))
        }
        else -> HttpResponse(200, emptyMap(), ByteArray(0))
    }

    private fun extractCsrFromPairBody(body: ByteArray): String? = runCatching {
        val root = parseJson(body.toString(Charsets.UTF_8)) as? Map<*, *>
        root?.get("csr") as? String
    }.getOrNull()

    override fun close() {
        running = false
        runCatching { serverSocket.close() }
        for (socket in activeSockets) {
            runCatching { socket.close() }
        }
        activeSockets.clear()
        executor.shutdownNow()
    }

    companion object {
        private val PASSWORD = "standin-password".toCharArray()

        private val LOCAL_ECDSA_OID = byteArrayOf(
            0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02,
        )

        private fun der(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            writeDerLength(out, content.size)
            out.write(content, 0, content.size)
            return out.toByteArray()
        }

        private fun writeDerLength(out: ByteArrayOutputStream, length: Int) {
            if (length < 0x80) {
                out.write(length)
                return
            }
            var bytes = 0
            var value = length
            while (value > 0) {
                bytes += 1
                value = value shr 8
            }
            out.write(0x80 or bytes)
            for (shift in (bytes - 1) * 8 downTo 0 step 8) {
                out.write((length shr shift) and 0xff)
            }
        }

        private fun concat(vararg parts: ByteArray): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var offset = 0
            for (part in parts) {
                part.copyInto(out, offset)
                offset += part.size
            }
            return out
        }

        private fun readFrame(input: InputStream): Frame? {
            val header = readExactlyOrNull(input, 8) ?: return null
            val length = ((header[5].toInt() and 0xff) shl 16) or
                ((header[6].toInt() and 0xff) shl 8) or
                (header[7].toInt() and 0xff)
            val payload = if (length > 0) (readExactlyOrNull(input, length) ?: return null) else ByteArray(0)
            return decodeFrame(header + payload).frame
        }

        private fun readExactlyOrNull(input: InputStream, length: Int): ByteArray? {
            val out = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(out, offset, length - offset)
                if (read < 0) return null
                offset += read
            }
            return out
        }

        fun selfSignedCertificate(
            keyPair: KeyPair,
            cn: String = "localhost",
            isCa: Boolean = false,
        ): X509Certificate = signedCertificate(
            subjectPublicKey = keyPair.public,
            issuerKeyPair = keyPair,
            issuerSubject = cn,
            subjectName = cn,
            isCa = isCa,
        )

        fun signedCertificate(
            subjectPublicKey: PublicKey,
            issuerKeyPair: KeyPair,
            issuerSubject: String,
            subjectName: String,
            isCa: Boolean = false,
        ): X509Certificate {
            val issuerPrincipal = X500Principal("CN=$issuerSubject")
            val subjectPrincipal = X500Principal("CN=$subjectName")
            val algorithm = der(0x30, LOCAL_ECDSA_OID)
            val serial = der(0x02, BigInteger.valueOf(System.currentTimeMillis()).abs().toByteArray())
            val validity = der(0x30, concat(der(0x18, "20260101000000Z".toByteArray(Charsets.US_ASCII)), der(0x18, "20360101000000Z".toByteArray(Charsets.US_ASCII))))

            val extensions = if (isCa) {
                val basicConstraintsOid = byteArrayOf(0x06, 0x03, 0x55, 0x1d, 0x13)
                val basicConstraintsVal = der(0x30, der(0x01, byteArrayOf(0xff.toByte())))
                val ext = der(0x30, concat(basicConstraintsOid, der(0x01, byteArrayOf(0xff.toByte())), der(0x04, basicConstraintsVal)))
                der(0xa3, der(0x30, ext))
            } else {
                val sanOid = byteArrayOf(0x06, 0x03, 0x55, 0x1d, 0x11)
                val ipSan = der(0x87, byteArrayOf(127, 0, 0, 1))
                val dnsSan = der(0x82, "localhost".toByteArray(Charsets.US_ASCII))
                val generalNames = der(0x30, concat(ipSan, dnsSan))
                val ext = der(0x30, concat(sanOid, der(0x04, generalNames)))
                der(0xa3, der(0x30, ext))
            }

            val tbs = der(
                0x30,
                concat(
                    der(0xa0, der(0x02, byteArrayOf(0x02))),
                    serial,
                    algorithm,
                    issuerPrincipal.encoded,
                    validity,
                    subjectPrincipal.encoded,
                    subjectPublicKey.encoded,
                    extensions,
                ),
            )
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(issuerKeyPair.private)
            signer.update(tbs)
            val cert = der(
                0x30,
                concat(
                    tbs,
                    algorithm,
                    der(0x03, concat(byteArrayOf(0x00), signer.sign())),
                ),
            )
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(cert)) as X509Certificate
        }

        fun extractPublicKeyFromCsr(csrPem: String): PublicKey {
            val der = pemToDer(csrPem, "CERTIFICATE REQUEST")
            val certReq = CertificationRequest.getInstance(der)
            val spki = certReq.certificationRequestInfo.subjectPublicKeyInfo
            val spkiDer = spki.getEncoded("DER")
            return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spkiDer))
        }

        fun encodeCrockford(bytes: ByteArray): String {
            val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
            val output = StringBuilder()
            var buffer = 0
            var bits = 0
            bytes.forEach { raw ->
                buffer = (buffer shl 8) or (raw.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    output.append(alphabet[(buffer shr bits) and 31])
                    buffer = buffer and ((1 shl bits) - 1)
                }
            }
            if (bits > 0) {
                output.append(alphabet[(buffer shl (5 - bits)) and 31])
            }
            return output.toString()
        }
    }

    private object TrustAllManager : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
    }
}
