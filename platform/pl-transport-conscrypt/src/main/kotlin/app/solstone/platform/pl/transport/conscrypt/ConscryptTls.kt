// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.certificateFromPem
import app.solstone.core.crypto.requireTls13
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.CredentialCodec
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.conscrypt.Conscrypt

private var conscryptInstalled = false

@Synchronized
fun installConscrypt() {
    if (conscryptInstalled) {
        return
    }
    if (Security.getProvider("Conscrypt") == null) {
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }
    conscryptInstalled = true
}

private fun newTlsContext(): SSLContext {
    installConscrypt()
    return SSLContext.getInstance("TLS", "Conscrypt")
}

fun certlessFactory(): SSLSocketFactory {
    return certlessContext().socketFactory
}

fun authenticatedFactory(credential: ClientCredential): SSLSocketFactory {
    return authenticatedContext(credential).socketFactory
}

fun certlessEngine(host: String, port: Int): SSLEngine {
    val engine = certlessContext().createSSLEngine(host, port)
    configureClientEngine(engine)
    return engine
}

fun authenticatedEngine(credential: ClientCredential, host: String, port: Int): SSLEngine {
    val engine = authenticatedContext(credential).createSSLEngine(host, port)
    configureClientEngine(engine)
    return engine
}

private fun certlessContext(): SSLContext {
    val context = newTlsContext()
    context.init(null, arrayOf<TrustManager>(TrustAllManager), SecureRandom())
    return context
}

private fun authenticatedContext(credential: ClientCredential): SSLContext {
    val context = newTlsContext()
    context.init(CredentialCodec.keyManagers(credential), trustManagersFor(credential), SecureRandom())
    return context
}

private fun configureClientEngine(engine: SSLEngine) {
    engine.useClientMode = true
    engine.enabledProtocols = requireTls13(engine.supportedProtocols)
}

private fun trustManagersFor(credential: ClientCredential): Array<TrustManager> {
    val keys = KeyStore.getInstance(KeyStore.getDefaultType())
    keys.load(null)
    credential.caChainPem.forEachIndexed { index, pem ->
        keys.setCertificateEntry("ca-$index", certificateFromPem(pem))
    }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(keys)
    return factory.trustManagers
}

private object TrustAllManager : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
}
