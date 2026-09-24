// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.crypto.pemToDer
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.identity.AtomicFileWriter
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.DurableTxnHook
import app.solstone.core.identity.DurableTxnStep
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.GraphRevisions
import app.solstone.core.identity.ObtainResult
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingLease
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.PersistenceIssue
import app.solstone.core.identity.PushKeyAccess
import app.solstone.core.identity.StoreInspectResult
import app.solstone.core.identity.SubscriptionHandle
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.PairedHome
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class FilePairingGraph(
    private val identityFile: File,
    private val credentialFile: File,
    private val endpointFile: File,
    private val commitMarkerFile: File,
    private val protector: SecretProtector,
    private val fileWriter: AtomicFileWriter = AtomicFileWriter.Default,
    private val stepHook: DurableTxnHook? = null,
    private val pushKeyFile: File? = null,
    private val pushKeyProtector: SecretProtector? = null,
) : PairingPublisher, PushKeyAccess {
    private val lock = Any()
    private val identityStore = FileIdentityStore(identityFile, protector, fileWriter = fileWriter)
    private val credentialStore = FileClientCredentialStore(credentialFile, protector, fileWriter = fileWriter)
    private val endpointStore = FileEndpointStore(endpointFile, fileWriter = fileWriter)

    private val identityBakFile = File(identityFile.parentFile, "${identityFile.name}.bak")
    private val credentialBakFile = File(credentialFile.parentFile, "${credentialFile.name}.bak")
    private val endpointBakFile = File(endpointFile.parentFile, "${endpointFile.name}.bak")

    private val sequenceGen = AtomicLong(0)
    private var pairingRev = 1L
    private var directRev = 1L
    private var relayRev = 1L

    private val subscribers = CopyOnWriteArrayList<GraphSubscriber>()

    @Volatile
    private var currentSnapshotState: PairingGraphSnapshot

    init {
        require((pushKeyFile == null) == (pushKeyProtector == null)) {
            "pushKeyFile and pushKeyProtector must both be null or both non-null"
        }
        currentSnapshotState = synchronized(lock) {
            try {
                recoverOrAdoptLocked().also { cleanBackupAndStagingFiles() }
            } catch (_: Exception) {
                // Keep the in-flight marker and backups so a later start can retry recovery.
                PairingGraphSnapshot.Uncertain(sequenceGen.incrementAndGet(), PersistenceIssue.PERSISTENCE_FAILED)
            }
        }
    }

    override fun currentSnapshot(): PairingGraphSnapshot = currentSnapshotState

    override fun subscribe(observer: (PairingGraphSnapshot) -> Unit): SubscriptionHandle {
        val subscriber = GraphSubscriber(observer)
        synchronized(lock) {
            subscribers.add(subscriber)
            // Queue the initial value while the mutation lock is held. A later
            // mutation can therefore never be queued ahead of this snapshot.
            subscriber.publish(currentSnapshotState)
        }
        return SubscriptionHandle {
            if (subscribers.remove(subscriber)) subscriber.cancel()
        }
    }

    override fun <T> withMutationBoundary(block: () -> T): T = synchronized(lock) {
        block()
    }

    override fun acquireDirectLease(): PairingLease.Direct? {
        val snap = currentSnapshotState as? PairingGraphSnapshot.Committed ?: return null
        if (!snap.isDirectEligible) return null
        val cred = credentialStore.load() ?: return null
        val ep = endpointStore.load() ?: return null
        return PairingLease.Direct(snap, cred, ep)
    }

    override fun acquireRelayLease(): PairingLease.Relay? {
        val snap = currentSnapshotState as? PairingGraphSnapshot.Committed ?: return null
        if (!snap.isRelayEligible) return null
        val cred = credentialStore.load() ?: return null
        val origin = snap.home.relayOrigin ?: return null
        val token = snap.home.deviceToken ?: return null
        return PairingLease.Relay(snap, cred, origin, snap.home.instanceId, token)
    }

    override fun validateLease(lease: PairingLease): Boolean {
        val snap = currentSnapshotState as? PairingGraphSnapshot.Committed ?: return false
        if (snap.pairing != lease.snapshot.pairing) return false
        if (snap.revisions.pairingRevision != lease.snapshot.revisions.pairingRevision) return false
        return when (lease) {
            is PairingLease.Direct -> snap.isDirectEligible && snap.revisions.directRouteRevision == lease.snapshot.revisions.directRouteRevision
            is PairingLease.Relay -> snap.isRelayEligible && snap.revisions.relayAccessRevision == lease.snapshot.revisions.relayAccessRevision
        }
    }

    override fun installOrReplace(
        home: PairedHome,
        credential: ClientCredential,
        directEndpoint: DirectEndpoint?,
        isDirectAssociated: Boolean,
    ): GraphMutationResult = synchronized(lock) {
        val op = if (currentSnapshotState is PairingGraphSnapshot.Committed) CommitInFlightOp.REPLACE else CommitInFlightOp.INSTALL
        val priorCommitted = currentSnapshotState as? PairingGraphSnapshot.Committed
        var inFlightMarker: PairingCommitMarker? = null
        var durableDecision = false
        try {
            // Step 1: STAGING_WRITE - backup prior files and write IN_FLIGHT marker
            if (identityFile.exists()) fileWriter.write(identityBakFile, identityFile.readBytes())
            if (credentialFile.exists()) fileWriter.write(credentialBakFile, credentialFile.readBytes())
            if (endpointFile.exists()) fileWriter.write(endpointBakFile, endpointFile.readBytes())

            val stagedMarker = PairingCommitMarker(
                status = CommitMarkerStatus.IN_FLIGHT,
                inFlightOp = op,
                inFlightStep = CommitInFlightStep.STAGING_WRITE,
                priorStatus = if (priorCommitted != null) CommitMarkerStatus.COMMITTED else CommitMarkerStatus.ABSENT,
                priorInstanceId = priorCommitted?.home?.instanceId,
                priorClientCertSha256 = priorCommitted?.home?.clientCertFingerprint,
                priorHasDirectEndpoint = priorCommitted?.hasDirectEndpoint ?: false,
                priorDirectAssociated = priorCommitted?.directAssociated ?: false,
                priorHasRelayAccess = priorCommitted?.home?.let { it.relayOrigin != null && it.deviceToken != null } ?: false,
                priorIdentityChecksum = if (identityBakFile.exists()) PairingCommitMarker.checksumOf(identityBakFile) else null,
                priorCredentialChecksum = if (credentialBakFile.exists()) PairingCommitMarker.checksumOf(credentialBakFile) else null,
                priorEndpointChecksum = if (endpointBakFile.exists()) PairingCommitMarker.checksumOf(endpointBakFile) else null,
            )
            inFlightMarker = stagedMarker
            writeCommitMarker(stagedMarker)
            stepHook?.onStep(DurableTxnStep.STAGING_WRITE, op.name)

            // Step 2: RENAME_REPLACE - write live files
            credentialStore.save(credential)
            identityStore.save(home)
            if (directEndpoint != null) {
                endpointStore.save(app.solstone.core.pl.DirectEndpoint(directEndpoint.host, directEndpoint.port))
            } else {
                endpointStore.clear()
            }
            stepHook?.onStep(DurableTxnStep.RENAME_REPLACE, op.name)

            // Step 3: DURABILITY_ACK
            stepHook?.onStep(DurableTxnStep.DURABILITY_ACK, op.name)

            // Step 4: READ_BACK
            val identInspect = identityStore.inspect()
            val credInspect = credentialStore.inspect()
            val epInspect = endpointStore.inspect()

            val identOk = identInspect is StoreInspectResult.Ready && identInspect.value == home
            val credOk = credInspect is StoreInspectResult.Ready && credInspect.value == credential
            val epOk = if (directEndpoint != null) {
                epInspect is StoreInspectResult.Ready && epInspect.value == directEndpoint
            } else {
                epInspect is StoreInspectResult.Missing
            }

            if (!identOk || !credOk || !epOk) {
                if (!rollbackInFlightLocked(stagedMarker)) {
                    return GraphMutationResult.DurabilityUncertain(IllegalStateException("Read-back and rollback failed"))
                }
                inFlightMarker = null
                stepHook?.onStep(DurableTxnStep.READ_BACK, op.name)
                return GraphMutationResult.PersistenceFailed(IllegalStateException("Read-back verification failed"))
            }
            stepHook?.onStep(DurableTxnStep.READ_BACK, op.name)

            // Step 5: DURABLE_COMMIT_DECISION
            val commitMarker = PairingCommitMarker(
                status = CommitMarkerStatus.COMMITTED,
                instanceId = home.instanceId,
                clientCertSha256 = home.clientCertFingerprint,
                hasDirectEndpoint = directEndpoint != null,
                directAssociated = directEndpoint != null && isDirectAssociated,
                hasRelayAccess = home.relayOrigin != null && home.deviceToken != null,
                identityChecksum = PairingCommitMarker.checksumOf(identityFile),
                credentialChecksum = PairingCommitMarker.checksumOf(credentialFile),
                endpointChecksum = if (directEndpoint != null) PairingCommitMarker.checksumOf(endpointFile) else null,
            )
            writeCommitMarker(commitMarker)
            durableDecision = true
            stepHook?.onStep(DurableTxnStep.DURABLE_COMMIT_DECISION, op.name)

            // Step 6: CLEANUP
            cleanBackupAndStagingFiles()
            stepHook?.onStep(DurableTxnStep.CLEANUP, op.name)

            pairingRev++
            if (directEndpoint != null && isDirectAssociated) directRev++
            relayRev++

            val newCommitted = PairingGraphSnapshot.Committed(
                sequenceNumber = sequenceGen.incrementAndGet(),
                revisions = GraphRevisions(pairingRev, directRev, relayRev),
                home = home,
                hasDirectEndpoint = directEndpoint != null,
                directAssociated = directEndpoint != null && isDirectAssociated,
                relayLiveEligible = home.relayOrigin != null && home.deviceToken != null,
            )
            currentSnapshotState = newCommitted
            notifySubscribers(newCommitted)
            GraphMutationResult.Applied(newCommitted)
        } catch (e: Exception) {
            if (!durableDecision) {
                val marker = inFlightMarker
                if (marker != null) {
                    if (!rollbackInFlightLocked(marker)) return GraphMutationResult.DurabilityUncertain(e)
                } else cleanBackupAndStagingFiles()
                GraphMutationResult.PersistenceFailed(e)
            } else {
                val recovered = PairingCommitMarker.parse(commitMarkerFile)?.let(::loadCommittedFromMarker)
                if (recovered is PairingGraphSnapshot.Committed) {
                    pairingRev++
                    if (recovered.directAssociated) directRev++
                    relayRev++
                    val committed = recovered.copy(revisions = GraphRevisions(pairingRev, directRev, relayRev))
                    currentSnapshotState = committed
                    cleanBackupAndStagingFiles()
                    notifySubscribers(committed)
                    GraphMutationResult.Applied(committed)
                } else {
                    val uncertain = PairingGraphSnapshot.Uncertain(
                        sequenceNumber = sequenceGen.incrementAndGet(),
                        reason = PersistenceIssue.PERSISTENCE_FAILED,
                    )
                    currentSnapshotState = uncertain
                    notifySubscribers(uncertain)
                    GraphMutationResult.DurabilityUncertain(e)
                }
            }
        }
    }

    private fun restoreRelayAccessBackup(backupWritten: Boolean): Boolean {
        if (!backupWritten) {
            identityBakFile.delete()
        } else if (identityBakFile.exists()) {
            try {
                fileWriter.write(identityFile, identityBakFile.readBytes())
                identityBakFile.delete()
            } catch (_: Exception) {
                // do not delete bak
            }
        }
        val marker = PairingCommitMarker.parse(commitMarkerFile)
        return marker != null &&
            marker.status == CommitMarkerStatus.COMMITTED &&
            PairingCommitMarker.checksumOf(identityFile) == marker.identityChecksum
    }

    override fun updateRelayAccess(
        expectedPairing: PairingGeneration,
        relayOrigin: String,
        deviceToken: String,
        expiresAt: String?,
    ): GraphMutationResult = synchronized(lock) {
        val current = currentSnapshotState as? PairingGraphSnapshot.Committed
            ?: return GraphMutationResult.Conflict("No committed pairing")
        if (current.pairing != expectedPairing) {
            return GraphMutationResult.Conflict("Pairing mismatch")
        }

        var backupWritten = false
        var markerWritten = false
        try {
            if (identityFile.exists()) {
                fileWriter.write(identityBakFile, identityFile.readBytes())
                backupWritten = true
            }
            stepHook?.onStep(DurableTxnStep.STAGING_WRITE, CommitInFlightOp.ACCESS_UPDATE.name)

            val updatedHome = current.home.copy(
                relayOrigin = relayOrigin,
                deviceToken = deviceToken,
                expiresAt = expiresAt,
            )
            identityStore.save(updatedHome)
            stepHook?.onStep(DurableTxnStep.RENAME_REPLACE, CommitInFlightOp.ACCESS_UPDATE.name)
            stepHook?.onStep(DurableTxnStep.DURABILITY_ACK, CommitInFlightOp.ACCESS_UPDATE.name)

            val inspect = identityStore.inspect()
            if (inspect !is StoreInspectResult.Ready || inspect.value != updatedHome) {
                val matches = restoreRelayAccessBackup(backupWritten)
                stepHook?.onStep(DurableTxnStep.READ_BACK, CommitInFlightOp.ACCESS_UPDATE.name)
                if (!matches) {
                    val uncertain = PairingGraphSnapshot.Uncertain(
                        sequenceNumber = sequenceGen.incrementAndGet(),
                        reason = PersistenceIssue.PERSISTENCE_FAILED,
                    )
                    currentSnapshotState = uncertain
                    notifySubscribers(uncertain)
                }
                return GraphMutationResult.PersistenceFailed(IllegalStateException("Relay access readback failed"))
            }
            stepHook?.onStep(DurableTxnStep.READ_BACK, CommitInFlightOp.ACCESS_UPDATE.name)

            stepHook?.onStep(DurableTxnStep.DURABLE_COMMIT_DECISION, CommitInFlightOp.ACCESS_UPDATE.name)
            val marker = PairingCommitMarker(
                status = CommitMarkerStatus.COMMITTED,
                instanceId = updatedHome.instanceId,
                clientCertSha256 = updatedHome.clientCertFingerprint,
                hasDirectEndpoint = current.hasDirectEndpoint,
                directAssociated = current.directAssociated,
                hasRelayAccess = true,
                identityChecksum = PairingCommitMarker.checksumOf(identityFile),
                credentialChecksum = PairingCommitMarker.checksumOf(credentialFile),
                endpointChecksum = if (current.hasDirectEndpoint) PairingCommitMarker.checksumOf(endpointFile) else null,
            )
            writeCommitMarker(marker)
            markerWritten = true

            cleanBackupAndStagingFiles()
            stepHook?.onStep(DurableTxnStep.CLEANUP, CommitInFlightOp.ACCESS_UPDATE.name)

            relayRev++
            val updatedSnapshot = current.copy(
                sequenceNumber = sequenceGen.incrementAndGet(),
                revisions = GraphRevisions(pairingRev, directRev, relayRev),
                home = updatedHome,
                relayLiveEligible = true,
            )
            currentSnapshotState = updatedSnapshot
            notifySubscribers(updatedSnapshot)
            GraphMutationResult.Applied(updatedSnapshot)
        } catch (e: Exception) {
            if (!markerWritten) {
                val matches = restoreRelayAccessBackup(backupWritten)
                if (!matches) {
                    val uncertain = PairingGraphSnapshot.Uncertain(
                        sequenceNumber = sequenceGen.incrementAndGet(),
                        reason = PersistenceIssue.PERSISTENCE_FAILED,
                    )
                    currentSnapshotState = uncertain
                    notifySubscribers(uncertain)
                }
                GraphMutationResult.PersistenceFailed(e)
            } else {
                val recovered = PairingCommitMarker.parse(commitMarkerFile)?.let(::loadCommittedFromMarker)
                if (recovered is PairingGraphSnapshot.Committed) {
                    relayRev++
                    val committed = recovered.copy(
                        sequenceNumber = sequenceGen.incrementAndGet(),
                        revisions = GraphRevisions(pairingRev, directRev, relayRev),
                        relayLiveEligible = true,
                    )
                    currentSnapshotState = committed
                    cleanBackupAndStagingFiles()
                    notifySubscribers(committed)
                    GraphMutationResult.Applied(committed)
                } else {
                    val uncertain = PairingGraphSnapshot.Uncertain(
                        sequenceNumber = sequenceGen.incrementAndGet(),
                        reason = PersistenceIssue.PERSISTENCE_FAILED,
                    )
                    currentSnapshotState = uncertain
                    notifySubscribers(uncertain)
                    GraphMutationResult.DurabilityUncertain(e)
                }
            }
        }
    }

    override fun revokeRelayAccess(
        expectedPairing: PairingGeneration,
    ): GraphMutationResult = synchronized(lock) {
        val current = currentSnapshotState as? PairingGraphSnapshot.Committed
            ?: return GraphMutationResult.Conflict("No committed pairing")
        if (current.pairing != expectedPairing) {
            return GraphMutationResult.Conflict("Pairing mismatch")
        }

        // Immediately disable live relay without advancing pairing or direct route revision
        relayRev++
        val liveDisabledSnapshot = current.copy(
            sequenceNumber = sequenceGen.incrementAndGet(),
            revisions = GraphRevisions(pairingRev, directRev, relayRev),
            relayLiveEligible = false,
        )
        currentSnapshotState = liveDisabledSnapshot
        notifySubscribers(liveDisabledSnapshot)

        var backupWritten = false
        var markerWritten = false
        try {
            if (identityFile.exists()) {
                fileWriter.write(identityBakFile, identityFile.readBytes())
                backupWritten = true
            }
            val strippedHome = current.home.copy(
                relayOrigin = null,
                deviceToken = null,
                expiresAt = null,
            )
            identityStore.save(strippedHome)
            check(identityStore.load() == strippedHome) { "Relay revocation readback failed" }
            val marker = PairingCommitMarker(
                status = CommitMarkerStatus.COMMITTED,
                instanceId = strippedHome.instanceId,
                clientCertSha256 = strippedHome.clientCertFingerprint,
                hasDirectEndpoint = current.hasDirectEndpoint,
                directAssociated = current.directAssociated,
                hasRelayAccess = false,
                identityChecksum = PairingCommitMarker.checksumOf(identityFile),
                credentialChecksum = PairingCommitMarker.checksumOf(credentialFile),
                endpointChecksum = if (current.hasDirectEndpoint) PairingCommitMarker.checksumOf(endpointFile) else null,
            )
            writeCommitMarker(marker)
            markerWritten = true
            cleanBackupAndStagingFiles()

            val strippedSnapshot = liveDisabledSnapshot.copy(
                home = strippedHome,
            )
            currentSnapshotState = strippedSnapshot
            notifySubscribers(strippedSnapshot)
            GraphMutationResult.Applied(strippedSnapshot)
        } catch (e: Exception) {
            if (markerWritten) {
                val recovered = PairingCommitMarker.parse(commitMarkerFile)?.let(::loadCommittedFromMarker)
                if (recovered is PairingGraphSnapshot.Committed) {
                    val stripped = recovered.copy(revisions = liveDisabledSnapshot.revisions, relayLiveEligible = false)
                    currentSnapshotState = stripped
                    cleanBackupAndStagingFiles()
                    notifySubscribers(stripped)
                    return GraphMutationResult.Applied(stripped)
                }
            }
            if (markerWritten || !restoreRelayAccessBackup(backupWritten)) {
                val uncertain = PairingGraphSnapshot.Uncertain(
                    sequenceNumber = sequenceGen.incrementAndGet(),
                    reason = PersistenceIssue.PERSISTENCE_FAILED,
                )
                currentSnapshotState = uncertain
                notifySubscribers(uncertain)
            }
            GraphMutationResult.PersistenceFailed(e)
        }
    }

    override fun forget(): GraphMutationResult = synchronized(lock) {
        val priorCommitted = currentSnapshotState as? PairingGraphSnapshot.Committed
        try {
            // Step 1: STAGING_WRITE - backup live files and write IN_FLIGHT(FORGET)
            if (identityFile.exists()) fileWriter.write(identityBakFile, identityFile.readBytes())
            if (credentialFile.exists()) fileWriter.write(credentialBakFile, credentialFile.readBytes())
            if (endpointFile.exists()) fileWriter.write(endpointBakFile, endpointFile.readBytes())

            val inFlightMarker = PairingCommitMarker(
                status = CommitMarkerStatus.IN_FLIGHT,
                inFlightOp = CommitInFlightOp.FORGET,
                inFlightStep = CommitInFlightStep.STAGING_WRITE,
                priorStatus = if (priorCommitted != null) CommitMarkerStatus.COMMITTED else CommitMarkerStatus.ABSENT,
                priorInstanceId = priorCommitted?.home?.instanceId,
                priorClientCertSha256 = priorCommitted?.home?.clientCertFingerprint,
                priorHasDirectEndpoint = priorCommitted?.hasDirectEndpoint ?: false,
                priorDirectAssociated = priorCommitted?.directAssociated ?: false,
                priorHasRelayAccess = priorCommitted?.home?.let { it.relayOrigin != null && it.deviceToken != null } ?: false,
                priorIdentityChecksum = if (identityBakFile.exists()) PairingCommitMarker.checksumOf(identityBakFile) else null,
                priorCredentialChecksum = if (credentialBakFile.exists()) PairingCommitMarker.checksumOf(credentialBakFile) else null,
                priorEndpointChecksum = if (endpointBakFile.exists()) PairingCommitMarker.checksumOf(endpointBakFile) else null,
            )
            writeCommitMarker(inFlightMarker)
            stepHook?.onStep(DurableTxnStep.STAGING_WRITE, CommitInFlightOp.FORGET.name)

            // Step 2: DURABLE_COMMIT_DECISION - write ABSENT marker
            writeCommitMarker(PairingCommitMarker.absent())
            stepHook?.onStep(DurableTxnStep.DURABLE_COMMIT_DECISION, CommitInFlightOp.FORGET.name)

            // Step 3: RENAME_REPLACE - delete artifacts
            pushKeyFile?.delete()
            identityStore.clear()
            credentialStore.clear()
            endpointStore.clear()
            stepHook?.onStep(DurableTxnStep.RENAME_REPLACE, CommitInFlightOp.FORGET.name)

            // Step 4: CLEANUP
            cleanBackupAndStagingFiles()
            stepHook?.onStep(DurableTxnStep.CLEANUP, CommitInFlightOp.FORGET.name)

            pairingRev++
            directRev++
            relayRev++

            val absent = PairingGraphSnapshot.Absent(sequenceGen.incrementAndGet())
            currentSnapshotState = absent
            notifySubscribers(absent)
            GraphMutationResult.Cleared(absent)
        } catch (e: Exception) {
            val marker = PairingCommitMarker.parse(commitMarkerFile)
            if (marker?.status == CommitMarkerStatus.ABSENT) {
                cleanBackupAndStagingFiles()
                pushKeyFile?.delete()
                identityStore.clear()
                credentialStore.clear()
                endpointStore.clear()
                pairingRev++
                directRev++
                relayRev++
                val absent = PairingGraphSnapshot.Absent(sequenceGen.incrementAndGet())
                currentSnapshotState = absent
                notifySubscribers(absent)
                GraphMutationResult.Cleared(absent)
            } else {
                if (marker?.status == CommitMarkerStatus.IN_FLIGHT) {
                    if (!rollbackInFlightLocked(marker)) return GraphMutationResult.DurabilityUncertain(e)
                } else {
                    // Backup preparation failed before any live file or marker changed.
                    cleanBackupAndStagingFiles()
                }
                GraphMutationResult.PersistenceFailed(e)
            }
        }
    }

    override fun readPushKey(generation: PairingGeneration): ByteArray? = synchronized(lock) {
        val snap = currentSnapshotState as? PairingGraphSnapshot.Committed ?: return null
        if (snap.pairing != generation) return null
        val parsed = readPushKeyFileLocked() ?: return null
        if (parsed.generation != generation) return null
        parsed.keyBytes
    }

    override fun obtainPushKey(generation: PairingGeneration): ObtainResult = synchronized(lock) {
        val file = pushKeyFile
        val prot = pushKeyProtector
        if (file == null || prot == null) {
            return ObtainResult.Failed(IllegalStateException("push key store is not configured"))
        }
        val snap = currentSnapshotState as? PairingGraphSnapshot.Committed
        if (snap == null || snap.pairing != generation) {
            return ObtainResult.Refused
        }
        val existing = readPushKeyFileLocked()
        if (existing != null && existing.generation == generation) {
            return ObtainResult.Obtained(existing.keyBytes)
        }

        val keyBytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(keyBytes)
        val payload = encodePushKeyPayload(generation, keyBytes)
        try {
            val wrapped = prot.protect(payload)
            fileWriter.write(file, WRAP_MARKER + wrapped)
            ObtainResult.Obtained(keyBytes)
        } catch (e: Exception) {
            ObtainResult.Failed(e)
        }
    }

    private data class ParsedPushKeyPayload(
        val generation: PairingGeneration,
        val keyBytes: ByteArray,
    )

    private fun readPushKeyFileLocked(): ParsedPushKeyPayload? {
        val file = pushKeyFile ?: return null
        val prot = pushKeyProtector ?: return null
        if (!file.exists()) return null
        val bytes = try { file.readBytes() } catch (_: Exception) { return null }
        if (!bytes.startsWithMarker()) return null
        val wrapped = bytes.copyOfRange(WRAP_MARKER.size, bytes.size)
        val payload = try { prot.unprotect(wrapped) } catch (_: Exception) { return null }
        return parsePushKeyPayload(payload)
    }

    private fun parsePushKeyPayload(payload: ByteArray): ParsedPushKeyPayload? {
        if (payload.size < 6 + 2 + 2 + 32) return null
        val magic = "SOLPK1".toByteArray(Charsets.US_ASCII)
        if (!payload.copyOfRange(0, 6).contentEquals(magic)) return null
        var offset = 6
        if (offset + 2 > payload.size) return null
        val instLen = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
        offset += 2
        if (offset + instLen > payload.size) return null
        val instanceId = String(payload, offset, instLen, Charsets.UTF_8)
        offset += instLen

        if (offset + 2 > payload.size) return null
        val certLen = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
        offset += 2
        if (offset + certLen > payload.size) return null
        val certFingerprint = String(payload, offset, certLen, Charsets.UTF_8)
        offset += certLen

        if (payload.size - offset != 32) return null
        val keyBytes = payload.copyOfRange(offset, offset + 32)
        return ParsedPushKeyPayload(PairingGeneration(instanceId, certFingerprint), keyBytes)
    }

    private fun encodePushKeyPayload(generation: PairingGeneration, keyBytes: ByteArray): ByteArray {
        require(keyBytes.size == 32)
        val magic = "SOLPK1".toByteArray(Charsets.US_ASCII)
        val instBytes = generation.instanceId.toByteArray(Charsets.UTF_8)
        val certBytes = generation.clientCertFingerprint.toByteArray(Charsets.UTF_8)
        val out = ByteArray(6 + 2 + instBytes.size + 2 + certBytes.size + 32)
        System.arraycopy(magic, 0, out, 0, 6)
        var offset = 6
        out[offset] = ((instBytes.size shr 8) and 0xFF).toByte()
        out[offset + 1] = (instBytes.size and 0xFF).toByte()
        offset += 2
        System.arraycopy(instBytes, 0, out, offset, instBytes.size)
        offset += instBytes.size

        out[offset] = ((certBytes.size shr 8) and 0xFF).toByte()
        out[offset + 1] = (certBytes.size and 0xFF).toByte()
        offset += 2
        System.arraycopy(certBytes, 0, out, offset, certBytes.size)
        offset += certBytes.size

        System.arraycopy(keyBytes, 0, out, offset, 32)
        return out
    }

    override fun associateDirectIfProven(
        expectedPairing: PairingGeneration,
        endpoint: DirectEndpoint,
        proof: () -> Boolean,
    ): Boolean = synchronized(lock) {
        val current = currentSnapshotState as? PairingGraphSnapshot.Committed ?: return false
        if (current.pairing != expectedPairing) return false

        val proven = try {
            proof()
        } catch (_: Exception) {
            false
        }
        if (!proven) return false

        var priorEndpoint: ByteArray? = null
        var endpointSaved = false
        var markerWritten = false
        try {
            priorEndpoint = if (endpointFile.exists()) endpointFile.readBytes() else null
            endpointStore.save(app.solstone.core.pl.DirectEndpoint(endpoint.host, endpoint.port))
            endpointSaved = true
            check(endpointStore.load() == endpoint) { "Direct endpoint readback failed" }
            val marker = PairingCommitMarker(
                status = CommitMarkerStatus.COMMITTED,
                instanceId = current.home.instanceId,
                clientCertSha256 = current.home.clientCertFingerprint,
                hasDirectEndpoint = true,
                directAssociated = true,
                hasRelayAccess = current.home.relayOrigin != null && current.home.deviceToken != null,
                identityChecksum = PairingCommitMarker.checksumOf(identityFile),
                credentialChecksum = PairingCommitMarker.checksumOf(credentialFile),
                endpointChecksum = PairingCommitMarker.checksumOf(endpointFile),
            )
            writeCommitMarker(marker)
            markerWritten = true
            directRev++
            val updated = current.copy(
                sequenceNumber = sequenceGen.incrementAndGet(),
                revisions = GraphRevisions(pairingRev, directRev, relayRev),
                hasDirectEndpoint = true,
                directAssociated = true,
            )
            currentSnapshotState = updated
            notifySubscribers(updated)
            return true
        } catch (_: Exception) {
            if (markerWritten) return true
            if (endpointSaved) {
                try {
                    val bytes = priorEndpoint
                    if (bytes == null) {
                        check(!endpointFile.exists() || endpointFile.delete()) { "Endpoint rollback failed" }
                    } else {
                        fileWriter.write(endpointFile, bytes)
                    }
                } catch (_: Exception) {
                    // Reflect the actual bytes if rollback also fails.
                    val recovered = PairingCommitMarker.parse(commitMarkerFile)?.let(::loadCommittedFromMarker)
                        ?: PairingGraphSnapshot.Uncertain(sequenceGen.incrementAndGet(), PersistenceIssue.PERSISTENCE_FAILED)
                    currentSnapshotState = if (recovered is PairingGraphSnapshot.Committed) {
                        recovered.copy(revisions = current.revisions, relayLiveEligible = current.relayLiveEligible)
                    } else recovered
                    notifySubscribers(currentSnapshotState)
                }
            }
            return false
        }
    }

    private fun recoverOrAdoptLocked(): PairingGraphSnapshot {
        val markerExists = commitMarkerFile.exists()
        val marker = PairingCommitMarker.parse(commitMarkerFile)
        if (marker == null) {
            if (markerExists) {
                return PairingGraphSnapshot.Uncertain(sequenceGen.incrementAndGet(), PersistenceIssue.PERSISTENCE_FAILED)
            }
            return adoptLegacyLocked()
        }

        if (marker.status == CommitMarkerStatus.IN_FLIGHT) {
            restorePriorFromBackupLocked(marker)
            val restoredMarker = PairingCommitMarker.parse(commitMarkerFile)
            if (restoredMarker == null || restoredMarker.status == CommitMarkerStatus.ABSENT) {
                return PairingGraphSnapshot.Absent(sequenceGen.incrementAndGet())
            }
            return loadCommittedFromMarker(restoredMarker)
        }

        if (marker.status == CommitMarkerStatus.ABSENT) {
            cleanBackupAndStagingFiles()
            pushKeyFile?.delete()
            identityFile.delete()
            credentialFile.delete()
            endpointFile.delete()
            return PairingGraphSnapshot.Absent(sequenceGen.incrementAndGet())
        }

        if (marker.status == CommitMarkerStatus.UNCERTAIN) {
            return PairingGraphSnapshot.Uncertain(sequenceGen.incrementAndGet(), PersistenceIssue.PERSISTENCE_FAILED)
        }

        return loadCommittedFromMarker(marker)
    }

    private fun loadCommittedFromMarker(marker: PairingCommitMarker): PairingGraphSnapshot {
        val identInspect = identityStore.inspect()
        val credInspect = credentialStore.inspect()
        val epInspect = endpointStore.inspect()

        if (identInspect !is StoreInspectResult.Ready || credInspect !is StoreInspectResult.Ready) {
            return PairingGraphSnapshot.Uncertain(sequenceGen.incrementAndGet(), PersistenceIssue.PERSISTENCE_FAILED)
        }

        val home = identInspect.value
        val credential = credInspect.value
        val identMatches = PairingCommitMarker.checksumOf(identityFile) == marker.identityChecksum
        val credMatches = PairingCommitMarker.checksumOf(credentialFile) == marker.credentialChecksum
        val pairingMatches = marker.instanceId == home.instanceId &&
            marker.clientCertSha256 == home.clientCertFingerprint &&
            certFingerprintMatches(credential.clientCertPem, home.clientCertFingerprint)
        if (!identMatches || !credMatches || !pairingMatches) {
            return PairingGraphSnapshot.Uncertain(sequenceGen.incrementAndGet(), PersistenceIssue.PERSISTENCE_FAILED)
        }

        val epPresent = epInspect is StoreInspectResult.Ready
        val epMatches = epPresent == marker.hasDirectEndpoint &&
            if (epPresent) PairingCommitMarker.checksumOf(endpointFile) == marker.endpointChecksum else marker.endpointChecksum == null
        val relayPresent = home.relayOrigin != null && home.deviceToken != null
        if (!epMatches || relayPresent != marker.hasRelayAccess || (marker.directAssociated && !epPresent)) {
            return PairingGraphSnapshot.Uncertain(sequenceGen.incrementAndGet(), PersistenceIssue.PERSISTENCE_FAILED)
        }

        return PairingGraphSnapshot.Committed(
            sequenceNumber = sequenceGen.incrementAndGet(),
            revisions = GraphRevisions(pairingRev, directRev, relayRev),
            home = home,
            hasDirectEndpoint = epPresent,
            directAssociated = marker.directAssociated,
            relayLiveEligible = marker.hasRelayAccess,
        )
    }

    private fun adoptLegacyLocked(): PairingGraphSnapshot {
        val identInspect = identityStore.inspect()
        val credInspect = credentialStore.inspect()
        val epInspect = endpointStore.inspect()

        if (identInspect is StoreInspectResult.Missing && credInspect is StoreInspectResult.Missing && epInspect is StoreInspectResult.Missing) {
            writeCommitMarker(PairingCommitMarker.absent())
            return PairingGraphSnapshot.Absent(sequenceGen.incrementAndGet())
        }

        if (identInspect !is StoreInspectResult.Ready || credInspect !is StoreInspectResult.Ready) {
            writeCommitMarker(PairingCommitMarker.uncertain())
            return PairingGraphSnapshot.Uncertain(sequenceGen.incrementAndGet(), PersistenceIssue.PERSISTENCE_FAILED)
        }

        val home = identInspect.value
        val cred = credInspect.value

        if (!certFingerprintMatches(cred.clientCertPem, home.clientCertFingerprint)) {
            writeCommitMarker(PairingCommitMarker.uncertain())
            return PairingGraphSnapshot.Uncertain(sequenceGen.incrementAndGet(), PersistenceIssue.PERSISTENCE_FAILED)
        }

        if (epInspect is StoreInspectResult.Unreadable) {
            writeCommitMarker(PairingCommitMarker.uncertain())
            return PairingGraphSnapshot.Uncertain(sequenceGen.incrementAndGet(), PersistenceIssue.PERSISTENCE_FAILED)
        }

        val epPresent = epInspect is StoreInspectResult.Ready
        val marker = PairingCommitMarker(
            status = CommitMarkerStatus.COMMITTED,
            instanceId = home.instanceId,
            clientCertSha256 = home.clientCertFingerprint,
            hasDirectEndpoint = epPresent,
            directAssociated = false,
            hasRelayAccess = home.relayOrigin != null && home.deviceToken != null,
            identityChecksum = PairingCommitMarker.checksumOf(identityFile),
            credentialChecksum = PairingCommitMarker.checksumOf(credentialFile),
            endpointChecksum = if (epPresent) PairingCommitMarker.checksumOf(endpointFile) else null,
        )
        writeCommitMarker(marker)

        return PairingGraphSnapshot.Committed(
            sequenceNumber = sequenceGen.incrementAndGet(),
            revisions = GraphRevisions(pairingRev, directRev, relayRev),
            home = home,
            hasDirectEndpoint = epPresent,
            directAssociated = false,
            relayLiveEligible = home.relayOrigin != null && home.deviceToken != null,
        )
    }

    private fun certFingerprintMatches(certPem: String, expectedFingerprint: String): Boolean {
        val expected = expectedFingerprint.removePrefix("sha256:").lowercase()
        val computed = runCatching {
            val der = pemToDer(certPem, "CERTIFICATE")
            sha256Hex(der).lowercase()
        }.getOrNull()
        if (computed != null && computed == expected) return true
        val rawClean = certPem.replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\r", "")
            .replace("\n", "")
            .trim()
            .lowercase()
        return rawClean == expected
    }

    private fun rollbackInFlightLocked(marker: PairingCommitMarker): Boolean = try {
        restorePriorFromBackupLocked(marker)
        val restored = PairingCommitMarker.parse(commitMarkerFile)?.let { prior ->
            if (prior.status == CommitMarkerStatus.ABSENT) PairingGraphSnapshot.Absent(sequenceGen.incrementAndGet())
            else loadCommittedFromMarker(prior)
        } ?: PairingGraphSnapshot.Uncertain(sequenceGen.incrementAndGet(), PersistenceIssue.PERSISTENCE_FAILED)
        if (restored !is PairingGraphSnapshot.Committed || currentSnapshotState !is PairingGraphSnapshot.Committed) {
            currentSnapshotState = restored
            notifySubscribers(restored)
        }
        true
    } catch (_: Exception) {
        val uncertain = PairingGraphSnapshot.Uncertain(sequenceGen.incrementAndGet(), PersistenceIssue.PERSISTENCE_FAILED)
        currentSnapshotState = uncertain
        notifySubscribers(uncertain)
        false
    }

    private fun restorePriorFromBackupLocked(marker: PairingCommitMarker) {
        require(marker.status == CommitMarkerStatus.IN_FLIGHT) { "Rollback requires an in-flight marker" }
        if (marker.priorStatus == null || marker.priorStatus == CommitMarkerStatus.ABSENT) {
            pushKeyFile?.delete()
            identityFile.delete()
            credentialFile.delete()
            endpointFile.delete()
            cleanBackupAndStagingFiles()
            writeCommitMarker(PairingCommitMarker.absent())
        } else if (marker.priorStatus == CommitMarkerStatus.COMMITTED) {
            if (identityBakFile.exists()) {
                fileWriter.write(identityFile, identityBakFile.readBytes())
            }
            if (credentialBakFile.exists()) {
                fileWriter.write(credentialFile, credentialBakFile.readBytes())
            }
            if (endpointBakFile.exists()) {
                fileWriter.write(endpointFile, endpointBakFile.readBytes())
            } else {
                endpointFile.delete()
            }
            val priorMarker = PairingCommitMarker(
                status = CommitMarkerStatus.COMMITTED,
                instanceId = marker.priorInstanceId,
                clientCertSha256 = marker.priorClientCertSha256,
                hasDirectEndpoint = marker.priorHasDirectEndpoint,
                directAssociated = marker.priorDirectAssociated,
                hasRelayAccess = marker.priorHasRelayAccess,
                identityChecksum = marker.priorIdentityChecksum,
                credentialChecksum = marker.priorCredentialChecksum,
                endpointChecksum = marker.priorEndpointChecksum,
            )
            writeCommitMarker(priorMarker)
            cleanBackupAndStagingFiles()
        }
    }

    private fun writeCommitMarker(marker: PairingCommitMarker) {
        val bytes = marker.toSerializedString().toByteArray()
        fileWriter.write(commitMarkerFile, bytes)
    }

    private fun cleanBackupAndStagingFiles() {
        identityBakFile.delete()
        credentialBakFile.delete()
        endpointBakFile.delete()
    }

    private fun notifySubscribers(snapshot: PairingGraphSnapshot) {
        for (subscriber in subscribers) {
            subscriber.publish(snapshot)
        }
    }

    private class GraphSubscriber(
        private val observer: (PairingGraphSnapshot) -> Unit,
    ) {
        private val cancelled = AtomicBoolean(false)
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FilePairingGraph-subscriber").apply { isDaemon = true }
        }

        fun publish(snapshot: PairingGraphSnapshot) {
            if (cancelled.get()) return
            executor.submit {
                if (!cancelled.get()) runCatching { observer(snapshot) }
            }
        }

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) executor.shutdownNow()
        }
    }
}
