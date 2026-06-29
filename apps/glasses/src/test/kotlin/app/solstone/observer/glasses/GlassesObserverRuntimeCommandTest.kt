// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
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
import app.solstone.observer.harness.PairProbe
import app.solstone.observer.harness.PlStatusProbe
import app.solstone.observer.harness.RelayPairProbe
import app.solstone.observer.harness.SourceRuntimeSnapshot
import app.solstone.observer.harness.SyncEnqueue
import app.solstone.platform.camera.still.CameraLock
import app.solstone.platform.fgs.ForegroundStartAllowed
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.PermissionStatusReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class GlassesObserverRuntimeCommandTest {
    @Test
    fun pairStartStopSyncAndSpeakWorkWithoutActivity() {
        val container = FakeRuntimeContainer(controller = controller())
        val runtime = GlassesObserverRuntime(container)

        assertEquals(CommandSucceeded, runtime.pairLink(validPairLink()))
        assertEquals(CommandSucceeded, runtime.observeStart())
        assertEquals(CommandSucceeded, runtime.syncNow())
        assertEquals(CommandSucceeded, runtime.speakStatus())
        assertEquals(CommandSucceeded, runtime.speakNeedsAttention())
        assertEquals(CommandSucceeded, runtime.observeStop())
        assertEquals(1, container.sync.enqueueNowCalls)
        assertEquals(1, container.speakStatusCalls)
        assertEquals(1, container.speakAttentionCalls)
    }

    @Test
    fun observeStartMissingPermissionsReportsBlocked() {
        val runtime = GlassesObserverRuntime(
            FakeRuntimeContainer(
                controller = controller(permissionStatus = grantedPermissions().copy(cameraGranted = false)),
            ),
        )

        assertEquals(CommandBlocked(RuntimeCommandBlockReason.MissingPermissions), runtime.observeStart())
    }

    @Test
    fun observeStartUnpairedReportsDiagnosticsNeedsAttention() {
        val runtime = GlassesObserverRuntime(
            FakeRuntimeContainer(
                controller = controller(
                    endpointStore = FakeEndpointStore(null),
                    credentialStore = FakeCredentialStore(null),
                    identityStore = FakeIdentityStore(null),
                ),
            ),
        )

        val result = assertIs<CommandNeedsAttention>(runtime.observeStart())
        assertEquals(SourceState.NEEDS_ATTENTION, result.state)
        assertEquals(ReasonCode.UNPAIRED, result.reason)
    }

    @Test
    fun invalidPairLinkReportsBlocked() {
        val runtime = GlassesObserverRuntime(FakeRuntimeContainer(controller = controller()))

        assertEquals(
            CommandBlocked(RuntimeCommandBlockReason.InvalidPairLinkOrCameraBusy),
            runtime.pairLink("not a pair link"),
        )
    }

    @Test
    fun activityReattachUsesExistingContainerAndPipeline() {
        val container = FakeRuntimeContainer(controller = controller())
        val runtime = GlassesObserverRuntime(container)

        assertSame(container, runtime.containerIfInitialized)
        assertSame(container, runtime.containerIfInitialized)
    }

    private class FakeRuntimeContainer(
        override val controller: HarnessController,
    ) : GlassesRuntimeContainer {
        val sync = controllerSync(controller)
        var speakStatusCalls = 0
        var speakAttentionCalls = 0
        var closeCalls = 0

        override fun speakCurrentStatus() {
            speakStatusCalls += 1
        }

        override fun speakNeedsAttention() {
            speakAttentionCalls += 1
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private class MutablePermissionReader(var status: PermissionStatus) : PermissionStatusReader {
        override fun read(): PermissionStatus = status
    }

    private class MemoryDesiredStore(initial: Boolean = false) : DesiredObservingStore {
        private var current = initial
        override fun isDesiredOn(): Boolean = current
        override fun setDesiredOn(on: Boolean) {
            current = on
        }
    }

    private class RecordingLifecycle : ObserverLifecycle {
        var starts = 0
        var stops = 0
        override fun start() {
            starts += 1
        }
        override fun stop() {
            stops += 1
        }
    }

    private class RecordingCameraLock : CameraLock {
        private var held = false
        override fun tryAcquire(): Boolean =
            if (held) {
                false
            } else {
                held = true
                true
            }
        override fun release() {
            held = false
        }
    }

    private class FreshHeartbeat : HeartbeatFreshness {
        override fun isFresh(): Boolean = true
    }

    private class RecordingSync : SyncEnqueue {
        var enqueueNowCalls = 0
        override fun enqueuePeriodic() = Unit
        override fun enqueueNow() {
            enqueueNowCalls += 1
        }
    }

    private class FakeEvidenceReader : EvidenceReader {
        override fun listEvidence(): List<HarnessEvidenceSegment> = emptyList()
        override fun pendingCount(): Int = 0
        override fun syncState(): HarnessSyncState = HarnessSyncState(0, null, null)
    }

    private class FakeEndpointStore(var endpoint: DirectEndpoint? = DirectEndpoint("10.0.0.2", 7657)) : EndpointStore {
        override fun save(endpoint: DirectEndpoint) {
            this.endpoint = endpoint
        }
        override fun load(): DirectEndpoint? = endpoint
        override fun clear() {
            endpoint = null
        }
    }

    private class FakeCredentialStore(var credential: ClientCredential? = credential()) : ClientCredentialStore {
        override fun save(credential: ClientCredential) {
            this.credential = credential
        }
        override fun load(): ClientCredential? = credential
        override fun clear() {
            credential = null
        }
    }

    private class FakeIdentityStore(var home: PairedHome? = pairedHome()) : IdentityStore {
        override fun save(home: PairedHome) {
            this.home = home
        }
        override fun load(): PairedHome? = home
        override fun clear() {
            home = null
        }
    }

    private fun controller(
        permissionStatus: PermissionStatus = grantedPermissions(),
        endpointStore: FakeEndpointStore = FakeEndpointStore(),
        credentialStore: FakeCredentialStore = FakeCredentialStore(),
        identityStore: FakeIdentityStore = FakeIdentityStore(),
    ): HarnessController {
        val sync = RecordingSync()
        return HarnessController(
            permissionStatusReader = MutablePermissionReader(permissionStatus),
            desiredObservingStore = MemoryDesiredStore(),
            foregroundStartAllowed = ForegroundStartAllowed { true },
            cameraLock = RecordingCameraLock(),
            observerLifecycle = RecordingLifecycle(),
            heartbeatFreshness = FreshHeartbeat(),
            pairProbe = PairProbe { _, _ -> HarnessPairProbeResult(true, 200, 200, "ok", "home", "10.0.0.2", 7657) },
            relayPairProbe = RelayPairProbe { _, _ -> HarnessPairProbeResult(true, 200, 200, "ok", "home", "link.solstone.app", 443) },
            plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
            syncEnqueue = sync,
            evidenceReader = FakeEvidenceReader(),
            bundleExport = BundleExport { HarnessExportResult("source", "dest", 0) },
            endpointStore = endpointStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            sourceSnapshot = {
                SourceRuntimeSnapshot(
                    engineRunning = true,
                    providerEmitting = true,
                    storageOk = true,
                    exemptionVerified = true,
                )
            },
            deviceLabel = "test glasses",
        ).also { syncByController[it] = sync }
    }

    private companion object {
        val syncByController = mutableMapOf<HarnessController, RecordingSync>()
        fun controllerSync(controller: HarnessController): RecordingSync = syncByController.getValue(controller)

        fun grantedPermissions(): PermissionStatus =
            PermissionStatus(
                microphoneGranted = true,
                cameraGranted = true,
                fineLocationGranted = true,
                coarseLocationGranted = false,
                backgroundLocationGranted = false,
                notificationsGranted = true,
                requireLocation = false,
            )

        fun credential(): ClientCredential =
            ClientCredential("private", "cert", listOf("ca"))

        fun pairedHome(): PairedHome =
            PairedHome(
                instanceId = "home",
                homeLabel = "home",
                relayOrigin = null,
                caChainFingerprint = "sha256:ca",
                clientCertFingerprint = "sha256:client",
                observerHandle = "glasses",
                deviceToken = null,
                expiresAt = null,
                state = IdentityState.PAIRED,
            )

        fun validPairLink(): String {
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
            if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 31])
            return out.toString()
        }
    }
}
