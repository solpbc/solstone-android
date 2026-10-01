// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import app.solstone.core.crypto.generateP256KeyPair
import app.solstone.core.crypto.pem
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.DurableTxnStep
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.identity.file.AndroidKeyStoreProtector
import app.solstone.platform.identity.file.FilePairingGraph
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.work.plStoreDir
import app.solstone.testing.JournalLoopbackStandIn
import java.io.File

class GlassesRealTestRunner : AndroidJUnitRunner() {
    companion object {
        @Volatile var standIn: JournalLoopbackStandIn? = null
        @Volatile var startupArg: String? = null
    }

    override fun onCreate(arguments: Bundle?) {
        val startup = arguments?.getString("startup")
        startupArg = startup

        if (startup == "upgraded" || startup == "death") {
            val s = JournalLoopbackStandIn()
            standIn = s
            val targetCtx = targetContext
            val plDir = plStoreDir(targetCtx).apply { mkdirs() }
            val spoolDir = File(targetCtx.filesDir, "spool").apply { mkdirs() }
            val db = openSolstonePersistenceDatabase(targetCtx)

            val protector = AndroidKeyStoreProtector()
            val clientKeyPair = generateP256KeyPair()
            val clientCert = JournalLoopbackStandIn.signedCertificate(
                subjectPublicKey = clientKeyPair.public,
                issuerKeyPair = s.caKeyPair,
                issuerSubject = "Journal CA",
                subjectName = "solstone-client",
                isCa = false,
            )
            val clientDer = clientCert.encoded
            val clientCertFp = "sha256:" + sha256Hex(clientDer)
            val caDer = s.caCert.encoded
            val caChainFp = "sha256:" + sha256Hex(caDer)

            val home = PairedHome(
                instanceId = s.instanceId,
                homeLabel = "Home",
                relayOrigin = null,
                caChainFingerprint = caChainFp,
                clientCertFingerprint = clientCertFp,
                observerHandle = "obs",
                deviceToken = null,
                expiresAt = null,
                state = IdentityState.PAIRED,
            )
            val cred = ClientCredential(
                privateKeyPem = pem("PRIVATE KEY", clientKeyPair.private.encoded),
                clientCertPem = pem("CERTIFICATE", clientCert.encoded),
                caChainPem = listOf(s.caPem),
            )
            val endpoint = DirectEndpoint("127.0.0.1", s.port)

            try {
                if (startup == "upgraded") {
                    val graph = FilePairingGraph(
                        identityFile = File(plDir, "identity.tsv"),
                        credentialFile = File(plDir, "credential.pem"),
                        endpointFile = File(plDir, "endpoint.txt"),
                        commitMarkerFile = File(plDir, "pairing.commit"),
                        protector = protector,
                        pushKeyFile = File(plDir, "push-key.bin"),
                        pushKeyProtector = protector,
                    )
                    graph.installOrReplace(home, cred, endpoint, isDirectAssociated = true)
                    File(plDir, "journal_confirmation.json").delete()
                    val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
                    try {
                        pool.submit {
                            seedGlassesSegments(db, spoolDir, MAIN_STREAM, s.instanceId)
                        }.get()
                    } finally {
                        pool.shutdown()
                    }
                } else if (startup == "death") {
                    val graph = FilePairingGraph(
                        identityFile = File(plDir, "identity.tsv"),
                        credentialFile = File(plDir, "credential.pem"),
                        endpointFile = File(plDir, "endpoint.txt"),
                        commitMarkerFile = File(plDir, "pairing.commit"),
                        protector = protector,
                        pushKeyFile = File(plDir, "push-key.bin"),
                        pushKeyProtector = protector,
                        stepHook = { step, _ ->
                            if (step == DurableTxnStep.DURABLE_COMMIT_DECISION) {
                                throw RuntimeException("Simulated process death after durable commit decision")
                            }
                        },
                    )
                    graph.installOrReplace(home, cred, endpoint, isDirectAssociated = true)
                    File(plDir, "journal_confirmation.json").delete()
                    val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
                    try {
                        pool.submit {
                            seedGlassesSegments(db, spoolDir, MAIN_STREAM, s.instanceId)
                        }.get()
                    } finally {
                        pool.shutdown()
                    }
                }
            } finally {
                db.close()
            }
        }

        super.onCreate(arguments)
    }

    override fun onDestroy() {
        standIn?.close()
        standIn = null
        super.onDestroy()
    }
}
