// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingLease
import app.solstone.core.identity.PairingProvenance
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.StoreInspectResult
import java.io.Closeable
import java.util.UUID

private const val MIGRATION_PROTOCOL_UNSUPPORTED_REASON = "migration_protocol_unsupported"

enum class PairingMigrationStage {
    OFFER_NOT_SHOWN,
    SHOWN_DEFERRED,
    LISTING,
    AWAITING_SELECTION,
    READY_TO_SUBMIT,
    SUBMITTED_UNKNOWN,
    TERMINAL_KEEP_BOTH,
    TERMINAL_REPLACED,
    TERMINAL_REFUSED,
}

enum class PairingMigrationChoice(val wire: String) {
    KEEP_BOTH("new_device"),
    REPLACE("replace_device"),
}

data class PairingMigrationRecord(
    val generation: PairingGeneration,
    val callerCid: String,
    val stage: PairingMigrationStage,
    val requestSequence: Long = 0,
    val selectedCid: String? = null,
    val choice: PairingMigrationChoice? = null,
    val operationId: String? = null,
    /** Exact UTF-8 canonical PUT body, persisted before any request. */
    val canonicalPutPayload: String? = null,
    val echoMismatch: Boolean = false,
    val refusalReason: String? = null,
)

fun PairingMigrationRecord.canOpenDeviceChoice(): Boolean = when (stage) {
    PairingMigrationStage.TERMINAL_KEEP_BOTH,
    PairingMigrationStage.TERMINAL_REPLACED -> false
    PairingMigrationStage.TERMINAL_REFUSED -> refusalReason == MIGRATION_PROTOCOL_UNSUPPORTED_REASON
    else -> true
}

interface PairingMigrationStore {
    fun inspect(): StoreInspectResult<PairingMigrationRecord>
    fun save(record: PairingMigrationRecord)
    fun clear()
}

fun interface PairingMigrationClientOpener {
    fun open(lease: PairingLease): PlHttpClient
}

data class PairingMigrationListing(
    val generation: PairingGeneration,
    val requestSequence: Long,
    val clients: List<MigrationClient>,
)

enum class PairingMigrationPendingReason {
    DECISION_UNKNOWN,
    LIST_OFFLINE,
    LIST_UNAVAILABLE,
    SAVED_CONNECTION_REFUSED,
}

sealed interface PairingMigrationResult {
    data object NoOffer : PairingMigrationResult
    data class Offer(val record: PairingMigrationRecord) : PairingMigrationResult
    data class Listing(val value: PairingMigrationListing) : PairingMigrationResult
    data class Terminal(val record: PairingMigrationRecord, val definitePrecommitRefusal: Boolean = false) : PairingMigrationResult
    data class Pending(
        val record: PairingMigrationRecord,
        val reason: PairingMigrationPendingReason = PairingMigrationPendingReason.DECISION_UNKNOWN,
    ) : PairingMigrationResult
    data object StaleGeneration : PairingMigrationResult
    data object Unavailable : PairingMigrationResult
    data object InvalidSelection : PairingMigrationResult
    data object ConfirmationRequired : PairingMigrationResult
}

/**
 * Small owner for the post-pair device choice. It never creates credentials: each request obtains
 * an existing authenticated lease from PairingPublisher and fences the result to its generation.
 */
class PairingMigrationOwner(
    private val publisher: PairingPublisher,
    private val store: PairingMigrationStore,
    private val openClient: PairingMigrationClientOpener,
    private val newOperationId: () -> String = { UUID.randomUUID().toString() },
) {
    private var activeListing: PairingMigrationListing? = null

    @Synchronized
    fun currentOffer(): PairingMigrationResult {
        val snapshot = publisher.currentSnapshot()
        if (snapshot !is PairingGraphSnapshot.Committed) {
            if (snapshot is PairingGraphSnapshot.Absent) {
                runCatching {
                    publisher.withMutationBoundary {
                        if (publisher.currentSnapshot() is PairingGraphSnapshot.Absent) store.clear()
                    }
                }
            }
            activeListing = null
            return if (snapshot is PairingGraphSnapshot.Absent) PairingMigrationResult.NoOffer else PairingMigrationResult.Unavailable
        }
        if (snapshot.provenance != PairingProvenance.FRESH_LINK) return PairingMigrationResult.NoOffer
        return ensureRecord(snapshot)?.let(PairingMigrationResult::Offer) ?: PairingMigrationResult.Unavailable
    }

    /** Claims the sole post-success modal by durably consuming the unseen state before presentation. */
    @Synchronized
    fun claimInitialPresentation(expected: PairingGeneration): Boolean {
        val record = currentRecord(expected) ?: return false
        if (record.stage != PairingMigrationStage.OFFER_NOT_SHOWN) return false
        return persist(record.copy(stage = PairingMigrationStage.SHOWN_DEFERRED))
    }

    @Synchronized
    fun defer(expected: PairingGeneration): PairingMigrationResult {
        val record = currentRecord(expected) ?: return PairingMigrationResult.Unavailable
        if (record.stage in TERMINAL_STAGES) return PairingMigrationResult.Terminal(record, record.stage == PairingMigrationStage.TERMINAL_REFUSED)
        if (record.stage == PairingMigrationStage.READY_TO_SUBMIT || record.stage == PairingMigrationStage.SUBMITTED_UNKNOWN) {
            return PairingMigrationResult.Pending(record)
        }
        val deferred = record.copy(stage = PairingMigrationStage.SHOWN_DEFERRED, selectedCid = null)
        return if (persist(deferred)) PairingMigrationResult.Offer(deferred) else PairingMigrationResult.Unavailable
    }

    @Synchronized
    fun listClients(expected: PairingGeneration): PairingMigrationResult {
        val record = currentRecord(expected) ?: return PairingMigrationResult.Unavailable
        if (record.stage !in setOf(
                PairingMigrationStage.OFFER_NOT_SHOWN,
                PairingMigrationStage.SHOWN_DEFERRED,
                PairingMigrationStage.LISTING,
                PairingMigrationStage.AWAITING_SELECTION,
            )
        ) return PairingMigrationResult.Pending(record)
        val sequence = record.requestSequence + 1
        val listingRecord = record.copy(
            stage = PairingMigrationStage.LISTING,
            requestSequence = sequence,
            selectedCid = null,
            choice = null,
            operationId = null,
            canonicalPutPayload = null,
            echoMismatch = false,
            refusalReason = null,
        )
        if (!persist(listingRecord)) return PairingMigrationResult.Unavailable
        val result = withCurrentClient(expected) { client -> getMigrationClients(client) }
        if (!isCurrent(expected)) return PairingMigrationResult.StaleGeneration
        if (result == null) {
            return PairingMigrationResult.Pending(listingRecord, PairingMigrationPendingReason.SAVED_CONNECTION_REFUSED)
        }
        val clients = when (result) {
            is MigrationApiResult.Success -> result.value
            is MigrationApiResult.Unknown -> return PairingMigrationResult.Pending(listingRecord, listFailureReason(result))
            else -> return PairingMigrationResult.Pending(listingRecord, PairingMigrationPendingReason.LIST_UNAVAILABLE)
        }
        if (!isCurrent(expected) || currentRecord(expected)?.requestSequence != sequence) return PairingMigrationResult.StaleGeneration
        val filtered = clients.filterNot { it.cid == expected.clientCertFingerprint }
        val listing = PairingMigrationListing(expected, sequence, filtered)
        activeListing = listing
        return PairingMigrationResult.Listing(listing)
    }

    @Synchronized
    fun selectTarget(listing: PairingMigrationListing, exactCid: String): PairingMigrationResult {
        if (!listingIsCurrent(listing) || listing.clients.none { it.cid == exactCid }) return PairingMigrationResult.InvalidSelection
        val record = currentRecord(listing.generation) ?: return PairingMigrationResult.Unavailable
        if (record.stage != PairingMigrationStage.LISTING || record.requestSequence != listing.requestSequence) {
            return PairingMigrationResult.StaleGeneration
        }
        val selected = record.copy(stage = PairingMigrationStage.AWAITING_SELECTION, selectedCid = exactCid)
        return if (persist(selected)) PairingMigrationResult.Offer(selected) else PairingMigrationResult.Unavailable
    }

    @Synchronized
    fun keepBoth(expected: PairingGeneration): PairingMigrationResult {
        val record = currentRecord(expected) ?: return PairingMigrationResult.Unavailable
        if (!canAnswer(record)) return PairingMigrationResult.Pending(record)
        return submit(record, PairingMigrationChoice.KEEP_BOTH, target = null)
    }

    @Synchronized
    fun retryUnsupported(expected: PairingGeneration): PairingMigrationResult {
        val record = currentRecord(expected) ?: return PairingMigrationResult.Unavailable
        if (record.stage != PairingMigrationStage.TERMINAL_REFUSED ||
            record.refusalReason != MIGRATION_PROTOCOL_UNSUPPORTED_REASON
        ) return PairingMigrationResult.Pending(record)
        val reopened = record.copy(
            stage = PairingMigrationStage.SHOWN_DEFERRED,
            selectedCid = null,
            choice = null,
            operationId = null,
            canonicalPutPayload = null,
            echoMismatch = false,
            refusalReason = null,
        )
        return if (persist(reopened)) PairingMigrationResult.Offer(reopened) else PairingMigrationResult.Unavailable
    }

    @Synchronized
    fun replaceSelected(
        listing: PairingMigrationListing,
        exactCid: String,
        nativeConfirmed: Boolean,
    ): PairingMigrationResult {
        if (!nativeConfirmed) return PairingMigrationResult.ConfirmationRequired
        if (!listingIsCurrent(listing) || listing.clients.none { it.cid == exactCid }) return PairingMigrationResult.InvalidSelection
        val record = currentRecord(listing.generation) ?: return PairingMigrationResult.Unavailable
        if (record.stage != PairingMigrationStage.AWAITING_SELECTION ||
            record.requestSequence != listing.requestSequence || record.selectedCid != exactCid
        ) return PairingMigrationResult.StaleGeneration
        return submit(record, PairingMigrationChoice.REPLACE, exactCid)
    }

    /** Read current migration state first, then settle unknown PUT outcomes only from an exact PUT echo. */
    @Synchronized
    fun resume(expected: PairingGeneration): PairingMigrationResult {
        val record = currentRecord(expected) ?: return PairingMigrationResult.Unavailable
        if (record.stage == PairingMigrationStage.READY_TO_SUBMIT) {
            // READY_TO_SUBMIT is persisted before SUBMITTED_UNKNOWN, which is persisted before the
            // request leaves; a record still here has never been sent, so this is its first send.
            val submitted = record.copy(stage = PairingMigrationStage.SUBMITTED_UNKNOWN)
            if (!persist(submitted)) return PairingMigrationResult.Unavailable
            return sendPersisted(submitted, firstSend = true)
        }
        if (record.stage != PairingMigrationStage.SUBMITTED_UNKNOWN) {
            return if (record.stage in TERMINAL_STAGES) PairingMigrationResult.Terminal(record, record.stage == PairingMigrationStage.TERMINAL_REFUSED)
            else PairingMigrationResult.Offer(record)
        }
        val stateResult = withCurrentClient(expected) { client -> getMigrationState(client) }
        if (!isCurrent(expected)) return PairingMigrationResult.StaleGeneration
        if (stateResult !is MigrationApiResult.Success) return PairingMigrationResult.Pending(record)
        if (!isCurrent(expected)) return PairingMigrationResult.StaleGeneration
        return sendPersisted(record, firstSend = false)
    }

    private fun submit(
        record: PairingMigrationRecord,
        choice: PairingMigrationChoice,
        target: String?,
    ): PairingMigrationResult {
        if (!isCurrent(record.generation)) return PairingMigrationResult.StaleGeneration
        if (choice == PairingMigrationChoice.REPLACE && target == null) return PairingMigrationResult.InvalidSelection
        val operationId = newOperationId().also { require(UUID_PATTERN.matches(it)) }
        val payload = canonicalMigrationDecisionPayload(operationId, choice, target)
        val ready = record.copy(
            stage = PairingMigrationStage.READY_TO_SUBMIT,
            choice = choice,
            selectedCid = target,
            operationId = operationId,
            canonicalPutPayload = payload.toString(Charsets.UTF_8),
            refusalReason = null,
        )
        if (!persist(ready)) return PairingMigrationResult.Unavailable
        val submitted = ready.copy(stage = PairingMigrationStage.SUBMITTED_UNKNOWN)
        if (!persist(submitted)) return PairingMigrationResult.Unavailable
        return sendPersisted(submitted, firstSend = true)
    }

    /**
     * [firstSend] is true only when this request has never left the device. A replay of an operation
     * the journal may already have applied cannot reopen the picker on a missing target: the target
     * may be missing precisely because this replacement retired it, so the outcome stays unknown.
     */
    private fun sendPersisted(record: PairingMigrationRecord, firstSend: Boolean): PairingMigrationResult {
        if (record.operationId == null || record.canonicalPutPayload == null || record.choice == null) {
            return PairingMigrationResult.Pending(record)
        }
        val payload = record.canonicalPutPayload.toByteArray(Charsets.UTF_8)
        val expectedPayload = runCatching {
            canonicalMigrationDecisionPayload(record.operationId, record.choice, record.selectedCid)
        }.getOrNull() ?: return PairingMigrationResult.Pending(record)
        if (!payload.contentEquals(expectedPayload)) {
            return PairingMigrationResult.Pending(record)
        }
        val result = withCurrentClient(record.generation) { client -> putMigrationDecision(client, payload) }
            ?: return PairingMigrationResult.Pending(record)
        if (!isCurrent(record.generation)) return PairingMigrationResult.StaleGeneration
        when (result) {
            is MigrationApiResult.Refused -> {
                if (!isDefiniteMigrationPrecommitRefusal(result.reasonCode)) return PairingMigrationResult.Pending(record)
                val refused = record.copy(
                    stage = PairingMigrationStage.TERMINAL_REFUSED,
                    echoMismatch = false,
                    refusalReason = result.reasonCode,
                )
                return if (persist(refused)) PairingMigrationResult.Terminal(refused, definitePrecommitRefusal = true)
                else PairingMigrationResult.Unavailable
            }
            is MigrationApiResult.TargetUnavailable -> {
                if (!firstSend) return PairingMigrationResult.Pending(record)
                val reopened = record.copy(
                    stage = PairingMigrationStage.SHOWN_DEFERRED,
                    selectedCid = null,
                    choice = null,
                    operationId = null,
                    canonicalPutPayload = null,
                    echoMismatch = false,
                    refusalReason = result.reasonCode,
                )
                return if (persist(reopened)) PairingMigrationResult.Offer(reopened) else PairingMigrationResult.Unavailable
            }
            is MigrationApiResult.Unknown -> return PairingMigrationResult.Pending(record)
            is MigrationApiResult.Success -> {
                val response = result.value
                val expectedState = if (record.choice == PairingMigrationChoice.KEEP_BOTH) "new_device" else "replaced_device"
                val targetMatches = if (record.choice == PairingMigrationChoice.KEEP_BOTH) {
                    response.replacedCid == null
                } else {
                    response.replacedCid == record.selectedCid
                }
                if (response.operationId != record.operationId || response.cid != record.callerCid ||
                    response.state != expectedState || !targetMatches
                ) {
                    val mismatched = record.copy(echoMismatch = true)
                    return if (persist(mismatched)) PairingMigrationResult.Pending(mismatched) else PairingMigrationResult.Unavailable
                }
                val terminal = record.copy(
                    stage = if (record.choice == PairingMigrationChoice.KEEP_BOTH) PairingMigrationStage.TERMINAL_KEEP_BOTH else PairingMigrationStage.TERMINAL_REPLACED,
                    echoMismatch = false,
                )
                return if (persist(terminal)) PairingMigrationResult.Terminal(terminal) else PairingMigrationResult.Unavailable
            }
        }
    }

    private fun canAnswer(record: PairingMigrationRecord): Boolean =
        record.stage in setOf(
            PairingMigrationStage.OFFER_NOT_SHOWN,
            PairingMigrationStage.SHOWN_DEFERRED,
            PairingMigrationStage.LISTING,
            PairingMigrationStage.AWAITING_SELECTION,
        )

    private fun listingIsCurrent(listing: PairingMigrationListing): Boolean =
        activeListing == listing && isCurrent(listing.generation)

    private fun listFailureReason(result: MigrationApiResult.Unknown): PairingMigrationPendingReason = when {
        result.status == 401 || result.status == 403 || result.reasonCode in SAVED_CONNECTION_REFUSALS ->
            PairingMigrationPendingReason.SAVED_CONNECTION_REFUSED
        result.status == null || result.status == 408 || result.status == 429 || result.status >= 500 ->
            PairingMigrationPendingReason.LIST_OFFLINE
        else -> PairingMigrationPendingReason.LIST_UNAVAILABLE
    }

    private fun currentRecord(expected: PairingGeneration): PairingMigrationRecord? {
        val snapshot = publisher.currentSnapshot() as? PairingGraphSnapshot.Committed ?: return null
        if (snapshot.pairing != expected || snapshot.provenance != PairingProvenance.FRESH_LINK) return null
        return try {
            when (val inspected = store.inspect()) {
                is StoreInspectResult.Ready -> when {
                    inspected.value.generation == expected && inspected.value.callerCid == expected.clientCertFingerprint -> inspected.value
                    inspected.value.generation != expected -> ensureRecord(snapshot)
                    else -> null
                }
                StoreInspectResult.Missing -> ensureRecord(snapshot)
                is StoreInspectResult.Unreadable -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun persist(record: PairingMigrationRecord): Boolean {
        return try {
            publisher.withMutationBoundary {
                if (!isCurrent(record.generation)) false else {
                    store.save(record)
                    true
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isCurrent(generation: PairingGeneration): Boolean =
        (publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing == generation

    private fun <T> withCurrentClient(
        generation: PairingGeneration,
        block: (PlHttpClient) -> T,
    ): T? {
        if (!isCurrent(generation)) return null
        val lease = publisher.acquireDirectLease() ?: publisher.acquireRelayLease() ?: return null
        if (lease.snapshot.pairing != generation) return null
        val client = try {
            openClient.open(lease)
        } catch (_: Exception) {
            return null
        }
        return try {
            if (!isCurrent(generation)) null else block(client).takeIf { isCurrent(generation) }
        } catch (_: Exception) {
            null
        } finally {
            (client as? Closeable)?.let { runCatching { it.close() } }
        }
    }

    private fun ensureRecord(snapshot: PairingGraphSnapshot.Committed): PairingMigrationRecord? = try {
        when (val inspected = store.inspect()) {
            is StoreInspectResult.Ready -> when {
                inspected.value.generation == snapshot.pairing && inspected.value.callerCid == snapshot.home.clientCertFingerprint -> inspected.value
                inspected.value.generation == snapshot.pairing -> null
                else -> PairingMigrationRecord(snapshot.pairing, snapshot.home.clientCertFingerprint, PairingMigrationStage.OFFER_NOT_SHOWN)
                    .takeIf(::persist)
            }
            StoreInspectResult.Missing -> PairingMigrationRecord(snapshot.pairing, snapshot.home.clientCertFingerprint, PairingMigrationStage.OFFER_NOT_SHOWN)
                .takeIf(::persist)
            is StoreInspectResult.Unreadable -> null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        val TERMINAL_STAGES = setOf(
            PairingMigrationStage.TERMINAL_KEEP_BOTH,
            PairingMigrationStage.TERMINAL_REPLACED,
            PairingMigrationStage.TERMINAL_REFUSED,
        )
        val CID_PATTERN = Regex("sha256:[0-9a-f]{64}")
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val SAVED_CONNECTION_REFUSALS = setOf("paired_device_not_found", "migration_forbidden")
    }
}
