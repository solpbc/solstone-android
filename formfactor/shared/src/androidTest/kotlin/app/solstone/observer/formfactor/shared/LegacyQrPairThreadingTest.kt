// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.observer.harness.BundleExport
import app.solstone.observer.harness.DesiredObservingStore
import app.solstone.observer.harness.EvidenceReader
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.HarnessEvidenceSegment
import app.solstone.observer.harness.HarnessExportResult
import app.solstone.observer.harness.HarnessPairProbeResult
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.harness.HarnessSyncState
import app.solstone.observer.harness.HeartbeatFreshness
import app.solstone.observer.harness.ObserverLifecycle
import app.solstone.observer.harness.PairConnectionMode
import app.solstone.observer.harness.PairProbe
import app.solstone.observer.harness.PlStatusProbe
import app.solstone.observer.harness.RelayPairProbe
import app.solstone.observer.harness.SourceRuntimeSnapshot
import app.solstone.observer.harness.SyncEnqueue
import app.solstone.observer.harness.VisibleCaptureOwnerRegistry
import app.solstone.platform.camera.still.SingleHolderCameraLock
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.PermissionStatusReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyQrPairThreadingTest {
    /**
     * AC5 red proof: the legacy QR path previously decoded and paired on the main thread.
     */
    @Test
    fun legacyPairProbeRunsOffMainThread() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val latch = CountDownLatch(1)
        var pairLooper: Looper? = null
        val controller = controller(
            pairProbe = PairProbe { _, _ ->
                pairLooper = Looper.myLooper()
                latch.countDown()
                HarnessPairProbeResult(true, 200, 200, "", "home", "10.0.0.2", 7657, PairConnectionMode.PAIRING)
            },
        )
        val view = LegacyQrPreviewView(context, controller, "legacy-thread-test") {}

        view.submitDecodedTextForTest(validDirectPairLink())

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNotEquals(Looper.getMainLooper(), pairLooper)
    }

    private fun controller(pairProbe: PairProbe): HarnessController =
        HarnessController(
            permissionStatusReader = PermissionStatusReader {
                PermissionStatus(
                    microphoneGranted = true,
                    cameraGranted = true,
                    fineLocationGranted = true,
                    coarseLocationGranted = true,
                    backgroundLocationGranted = true,
                    notificationsGranted = true,
                )
            },
            desiredObservingStore = object : DesiredObservingStore {
                private var desired = false
                override fun isDesiredOn(): Boolean = desired
                override fun setDesiredOn(on: Boolean) {
                    desired = on
                }
            },
            cameraLock = SingleHolderCameraLock(),
            observerLifecycle = object : ObserverLifecycle {
                override fun start() = Unit
                override fun stop() = Unit
            },
            heartbeatFreshness = HeartbeatFreshness { true },
            pairProbe = pairProbe,
            relayPairProbe = RelayPairProbe { _, _ -> error("unexpected relay probe") },
            plStatusProbe = PlStatusProbe { HarnessPlStatus.NotPaired },
            syncEnqueue = object : SyncEnqueue {
                override fun enqueuePeriodic() = Unit
                override fun enqueueNow() = Unit
            },
            evidenceReader = object : EvidenceReader {
                override fun listEvidence(): List<HarnessEvidenceSegment> = emptyList()
                override fun pendingCount(): Int = 0
                override fun syncState(): HarnessSyncState = HarnessSyncState(0, null, null)
            },
            bundleExport = BundleExport { HarnessExportResult("", "", 0) },
            endpointStore = MemoryEndpointStore(),
            credentialStore = MemoryCredentialStore(),
            identityStore = MemoryIdentityStore(),
            sourceSnapshot = {
                SourceRuntimeSnapshot(
                    engineRunning = false,
                    providerEmitting = false,
                    storageOk = true,
                    exemptionVerified = true,
                )
            },
            deviceLabel = "legacy-test",
            visibleCaptureAuthority = VisibleCaptureOwnerRegistry(),
        )

    private fun validDirectPairLink(): String {
        val bytes = ByteArray(40)
        bytes[0] = 0x04
        bytes[1] = 0x01
        bytes[2] = 10
        bytes[3] = 0
        bytes[4] = 0
        bytes[5] = 2
        bytes[6] = 0x1d
        bytes[7] = 0xe9.toByte()
        for (i in 8 until bytes.size) {
            bytes[i] = i.toByte()
        }
        return "https://go.solstone.app/p#${crockfordEncode(bytes)}"
    }

    private fun crockfordEncode(bytes: ByteArray): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        bytes.forEach { raw ->
            buffer = (buffer shl 8) or (raw.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(alphabet[(buffer shr bits) and 31])
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        if (bits > 0) {
            out.append(alphabet[(buffer shl (5 - bits)) and 31])
        }
        return out.toString()
    }
}

private class MemoryEndpointStore : EndpointStore {
    private var endpoint: DirectEndpoint? = null
    override fun save(endpoint: DirectEndpoint) {
        this.endpoint = endpoint
    }
    override fun load(): DirectEndpoint? = endpoint
    override fun clear() {
        endpoint = null
    }
}

private class MemoryCredentialStore : ClientCredentialStore {
    private var credential: ClientCredential? = null
    override fun save(credential: ClientCredential) {
        this.credential = credential
    }
    override fun load(): ClientCredential? = credential
    override fun clear() {
        credential = null
    }
}

private class MemoryIdentityStore : IdentityStore {
    private var home: PairedHome? = null
    override fun save(home: PairedHome) {
        this.home = home
    }
    override fun load(): PairedHome? = home
    override fun clear() {
        home = null
    }
}
