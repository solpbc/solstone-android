// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.certificateFromPem
import app.solstone.core.crypto.privateKeyFromPem
import app.solstone.core.identity.ClientCredential
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory

class EphemeralTlsFixture private constructor(
    val caCertPem: String,
    val caKeyPem: String,
    val serverCertPem: String,
    val serverKeyPem: String,
    val clientCertPem: String,
    val clientKeyPem: String,
    val wrongCaCertPem: String,
    val wrongClientCertPem: String,
    val wrongClientKeyPem: String,
) {
    val credential = ClientCredential(
        privateKeyPem = clientKeyPem,
        clientCertPem = clientCertPem,
        caChainPem = listOf(caCertPem),
    )

    val wrongCredential = ClientCredential(
        privateKeyPem = wrongClientKeyPem,
        clientCertPem = wrongClientCertPem,
        caChainPem = listOf(wrongCaCertPem),
    )

    fun createServerSslContext(needClientAuth: Boolean = true): SSLContext {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        val cert = certificateFromPem(serverCertPem)
        val caCert = certificateFromPem(caCertPem)
        val key = privateKeyFromPem(serverKeyPem)
        keyStore.setKeyEntry("server", key, "password".toCharArray(), arrayOf(cert, caCert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "password".toCharArray())

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("ca", caCert)

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(kmf.keyManagers, if (needClientAuth) tmf.trustManagers else null, SecureRandom())
        return sslContext
    }

    fun createClientTrustContext(): SSLContext {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("ca", certificateFromPem(caCertPem))

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(null, tmf.trustManagers, SecureRandom())
        return sslContext
    }

    fun <T> withClientTrustDefault(block: () -> T): T {
        val prevContext = SSLContext.getDefault()
        val trustStoreFile = File.createTempFile("solstone_trust", ".jks")
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("ca", certificateFromPem(caCertPem))
        trustStoreFile.outputStream().use { trustStore.store(it, "password".toCharArray()) }

        val prevTrustStore = System.getProperty("javax.net.ssl.trustStore")
        val prevTrustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword")
        val prevTrustStoreType = System.getProperty("javax.net.ssl.trustStoreType")

        System.setProperty("javax.net.ssl.trustStore", trustStoreFile.absolutePath)
        System.setProperty("javax.net.ssl.trustStorePassword", "password")
        System.setProperty("javax.net.ssl.trustStoreType", KeyStore.getDefaultType())

        val testContext = createClientTrustContext()
        SSLContext.setDefault(testContext)

        return try {
            block()
        } finally {
            SSLContext.setDefault(prevContext)
            if (prevTrustStore != null) {
                System.setProperty("javax.net.ssl.trustStore", prevTrustStore)
            } else {
                System.clearProperty("javax.net.ssl.trustStore")
            }
            if (prevTrustStorePassword != null) {
                System.setProperty("javax.net.ssl.trustStorePassword", prevTrustStorePassword)
            } else {
                System.clearProperty("javax.net.ssl.trustStorePassword")
            }
            if (prevTrustStoreType != null) {
                System.setProperty("javax.net.ssl.trustStoreType", prevTrustStoreType)
            } else {
                System.clearProperty("javax.net.ssl.trustStoreType")
            }
            trustStoreFile.delete()
        }
    }

    fun createServerSslServerSocket(port: Int = 0, needClientAuth: Boolean = true): SSLServerSocket {
        val ctx = createServerSslContext(needClientAuth)
        val ss = ctx.serverSocketFactory.createServerSocket(port) as SSLServerSocket
        ss.needClientAuth = needClientAuth
        ss.enabledProtocols = arrayOf("TLSv1.3")
        return ss
    }

    companion object {
        fun generate(): EphemeralTlsFixture {
            val dir = File.createTempFile("solstone_tls", "").apply {
                delete()
                mkdirs()
            }
            try {
                val caKey = File(dir, "ca.key")
                val caCrt = File(dir, "ca.crt")
                val serverKey = File(dir, "server.key")
                val serverCsr = File(dir, "server.csr")
                val serverCrt = File(dir, "server.crt")
                val clientKey = File(dir, "client.key")
                val clientCsr = File(dir, "client.csr")
                val clientCrt = File(dir, "client.crt")
                val sanCnf = File(dir, "san.cnf").apply {
                    writeText("[san]\nsubjectAltName=IP:127.0.0.1,DNS:localhost\n")
                }

                runOpenssl("req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-keyout", caKey.absolutePath, "-out", caCrt.absolutePath, "-days", "365", "-nodes", "-subj", "/CN=Test CA")
                runOpenssl("req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-keyout", serverKey.absolutePath, "-out", serverCsr.absolutePath, "-nodes", "-subj", "/CN=localhost")
                runOpenssl("x509", "-req", "-in", serverCsr.absolutePath, "-CA", caCrt.absolutePath, "-CAkey", caKey.absolutePath, "-CAcreateserial", "-out", serverCrt.absolutePath, "-days", "365", "-extfile", sanCnf.absolutePath, "-extensions", "san")
                runOpenssl("req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-keyout", clientKey.absolutePath, "-out", clientCsr.absolutePath, "-nodes", "-subj", "/CN=client")
                runOpenssl("x509", "-req", "-in", clientCsr.absolutePath, "-CA", caCrt.absolutePath, "-CAkey", caKey.absolutePath, "-CAcreateserial", "-out", clientCrt.absolutePath, "-days", "365")

                val wrongCaKey = File(dir, "wrong_ca.key")
                val wrongCaCrt = File(dir, "wrong_ca.crt")
                val wrongClientKey = File(dir, "wrong_client.key")
                val wrongClientCsr = File(dir, "wrong_client.csr")
                val wrongClientCrt = File(dir, "wrong_client.crt")

                runOpenssl("req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-keyout", wrongCaKey.absolutePath, "-out", wrongCaCrt.absolutePath, "-days", "365", "-nodes", "-subj", "/CN=Wrong CA")
                runOpenssl("req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-keyout", wrongClientKey.absolutePath, "-out", wrongClientCsr.absolutePath, "-nodes", "-subj", "/CN=wrong-client")
                runOpenssl("x509", "-req", "-in", wrongClientCsr.absolutePath, "-CA", wrongCaCrt.absolutePath, "-CAkey", wrongCaKey.absolutePath, "-CAcreateserial", "-out", wrongClientCrt.absolutePath, "-days", "365")

                return EphemeralTlsFixture(
                    caCertPem = caCrt.readText(),
                    caKeyPem = caKey.readText(),
                    serverCertPem = serverCrt.readText(),
                    serverKeyPem = serverKey.readText(),
                    clientCertPem = clientCrt.readText(),
                    clientKeyPem = clientKey.readText(),
                    wrongCaCertPem = wrongCaCrt.readText(),
                    wrongClientCertPem = wrongClientCrt.readText(),
                    wrongClientKeyPem = wrongClientKey.readText(),
                )
            } finally {
                dir.deleteRecursively()
            }
        }

        private fun runOpenssl(vararg args: String) {
            val proc = ProcessBuilder("openssl", *args).start()
            val exit = proc.waitFor()
            if (exit != 0) {
                val err = proc.errorStream.bufferedReader().readText()
                throw IllegalStateException("openssl failed: $err")
            }
        }
    }
}
