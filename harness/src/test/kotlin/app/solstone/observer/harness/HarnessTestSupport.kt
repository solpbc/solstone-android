// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.model.QueueState
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.platform.camera.still.CameraLock
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.PermissionStatusReader

internal fun grantedPermissions(): PermissionStatus =
    PermissionStatus(
        microphoneGranted = true,
        cameraGranted = true,
        fineLocationGranted = true,
        coarseLocationGranted = false,
        backgroundLocationGranted = false,
        notificationsGranted = true,
    )

internal class FakeLifecycle : ObserverLifecycle {
    var starts = 0
    var stops = 0

    override fun start() {
        starts += 1
    }

    override fun stop() {
        stops += 1
    }
}

internal class MutablePermissionReader(var status: PermissionStatus) : PermissionStatusReader {
    override fun read(): PermissionStatus = status
}

internal class FakeEndpointStore(var endpoint: DirectEndpoint? = null) : EndpointStore {
    override fun save(endpoint: DirectEndpoint) {
        this.endpoint = endpoint
    }

    override fun load(): DirectEndpoint? = endpoint

    override fun clear() {
        endpoint = null
    }
}

internal class FakeCredentialStore(var credential: ClientCredential? = null) : ClientCredentialStore {
    override fun save(credential: ClientCredential) {
        this.credential = credential
    }

    override fun load(): ClientCredential? = credential

    override fun clear() {
        credential = null
    }
}

internal class FakeIdentityStore(var home: PairedHome? = null) : IdentityStore {
    override fun save(home: PairedHome) {
        this.home = home
    }

    override fun load(): PairedHome? = home

    override fun clear() {
        home = null
    }
}

internal open class RecordingCameraLock : CameraLock {
    var held = false
    val events = mutableListOf<String>()

    override fun tryAcquire(): Boolean {
        events += "acquire"
        return if (held) {
            false
        } else {
            held = true
            true
        }
    }

    override fun release() {
        events += "release"
        held = false
    }
}

internal class FakeEvidenceReader(
    private val segments: List<HarnessEvidenceSegment> = emptyList(),
    private val sync: HarnessSyncState = HarnessSyncState(0, null, null),
) : EvidenceReader {
    override fun listEvidence(): List<HarnessEvidenceSegment> = segments
    override fun pendingCount(): Int = sync.pendingCount
    override fun syncState(): HarnessSyncState = sync
}

internal class RecordingSyncEnqueue : SyncEnqueue {
    var calls = 0
    var enqueuePeriodicCalls = 0

    override fun enqueuePeriodic() {
        enqueuePeriodicCalls += 1
    }

    override fun enqueueNow() {
        calls += 1
    }
}

internal data class Fixture(
    val controller: HarnessController,
    val permissions: MutablePermissionReader,
    val lifecycle: FakeLifecycle,
    val heartbeat: MutableHeartbeat,
    val cameraLock: RecordingCameraLock,
    val sync: RecordingSyncEnqueue,
    val endpointStore: FakeEndpointStore,
    val credentialStore: FakeCredentialStore,
    val identityStore: FakeIdentityStore,
)

internal class MutableHeartbeat(var fresh: Boolean = true) : HeartbeatFreshness {
    override fun isFresh(): Boolean = fresh
}

internal fun fixture(
    permissionStatus: PermissionStatus = grantedPermissions(),
    cameraLock: RecordingCameraLock = RecordingCameraLock(),
    pairProbe: PairProbe = PairProbe { _, _ ->
        HarnessPairProbeResult(true, 200, 200, "ok", "home", "10.0.0.2", 7657)
    },
    relayPairProbe: RelayPairProbe = RelayPairProbe { link, _ ->
        HarnessPairProbeResult(true, 200, 200, "", "home", link.relayOrigin ?: "link.solstone.app", 443)
    },
    plStatusProbe: PlStatusProbe = PlStatusProbe { HarnessPlStatus.NotPaired },
    evidenceReader: EvidenceReader = FakeEvidenceReader(),
    bundleExport: BundleExport = BundleExport { HarnessExportResult("source", "dest", 0) },
    snapshot: SourceRuntimeSnapshot = SourceRuntimeSnapshot(
        engineRunning = true,
        providerEmitting = true,
        storageOk = true,
        exemptionVerified = true,
    ),
    endpointStore: FakeEndpointStore = FakeEndpointStore(DirectEndpoint("10.0.0.2", 7657)),
    credentialStore: FakeCredentialStore = FakeCredentialStore(credential()),
    identityStore: FakeIdentityStore = FakeIdentityStore(pairedHome()),
): Fixture {
    val permissions = MutablePermissionReader(permissionStatus)
    val lifecycle = FakeLifecycle()
    val heartbeat = MutableHeartbeat()
    val sync = RecordingSyncEnqueue()
    return Fixture(
        controller = HarnessController(
            permissionStatusReader = permissions,
            cameraLock = cameraLock,
            observerLifecycle = lifecycle,
            heartbeatFreshness = heartbeat,
            pairProbe = pairProbe,
            relayPairProbe = relayPairProbe,
            plStatusProbe = plStatusProbe,
            syncEnqueue = sync,
            evidenceReader = evidenceReader,
            bundleExport = bundleExport,
            endpointStore = endpointStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            sourceSnapshot = { snapshot },
            deviceLabel = "watch",
        ),
        permissions = permissions,
        lifecycle = lifecycle,
        heartbeat = heartbeat,
        cameraLock = cameraLock,
        sync = sync,
        endpointStore = endpointStore,
        credentialStore = credentialStore,
        identityStore = identityStore,
    )
}

internal fun pairedHome(
    state: IdentityState = IdentityState.PAIRED,
    instanceId: String = "home-1",
    relayOrigin: String? = null,
): PairedHome =
    PairedHome(
        instanceId = instanceId,
        homeLabel = "home",
        relayOrigin = relayOrigin,
        caChainFingerprint = "sha256:ca",
        clientCertFingerprint = "sha256:client",
        observerHandle = "watch-1",
        deviceToken = null,
        expiresAt = null,
        state = state,
    )

internal fun credential(): ClientCredential =
    ClientCredential("private", "cert", listOf("ca"))

internal fun evidenceSegment(
    id: String,
    stream: String,
    state: QueueState = QueueState.SEALED,
): HarnessEvidenceSegment =
    HarnessEvidenceSegment(
        id = id,
        day = "20260617",
        stream = stream,
        segment = "120000_300",
        state = state,
        byteSize = 10,
        sealedAt = 20,
        files = listOf(HarnessEvidenceFile("audio", "audio.m4a", "audio/mp4", "sha", 10)),
    )

internal fun validPairLink(): String {
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
