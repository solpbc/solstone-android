// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.GraphRevisions
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingLease
import app.solstone.core.identity.PairingProvenance
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.StoreInspectResult
import app.solstone.core.identity.SubscriptionHandle
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingMigrationOwnerTest {
    private val caller = "sha256:${"a".repeat(64)}"
    private val other = "sha256:${"b".repeat(64)}"

    @Test
    fun deviceChoiceRemainsAvailableOnlyForUnsupportedRefusalAmongTerminalStages() {
        val generation = (Publisher().current as PairingGraphSnapshot.Committed).pairing
        fun record(stage: PairingMigrationStage, reason: String? = null) =
            PairingMigrationRecord(generation, caller, stage, refusalReason = reason)

        assertTrue(record(PairingMigrationStage.TERMINAL_REFUSED, "migration_protocol_unsupported").canOpenDeviceChoice())
        assertFalse(record(PairingMigrationStage.TERMINAL_REFUSED, "migration_request_invalid").canOpenDeviceChoice())
        assertFalse(record(PairingMigrationStage.TERMINAL_KEEP_BOTH).canOpenDeviceChoice())
        assertFalse(record(PairingMigrationStage.TERMINAL_REPLACED).canOpenDeviceChoice())
        assertTrue(record(PairingMigrationStage.SHOWN_DEFERRED).canOpenDeviceChoice())
    }

    @Test
    fun freshDirectCommitRecoveryClaimsOneModalAndDeferMakesNoRequest() {
        val publisher = Publisher(route = Route.DIRECT)
        val store = MemoryStore()
        val client = ScriptClient()
        val owner = owner(publisher, store, client)

        val first = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record
        assertEquals(PairingMigrationStage.OFFER_NOT_SHOWN, first.stage)
        assertTrue(owner.claimInitialPresentation(first.generation))
        assertFalse(owner.claimInitialPresentation(first.generation))
        assertIs<PairingMigrationResult.Offer>(owner.defer(first.generation))
        assertEquals(PairingMigrationStage.SHOWN_DEFERRED, (store.inspect() as StoreInspectResult.Ready).value.stage)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun legacyUnknownAndCorruptPairingNeverOfferOrRequest() {
        val legacyStore = MemoryStore()
        val legacyClient = ScriptClient()
        val legacyOwner = owner(Publisher(PairingProvenance.UNKNOWN_LEGACY), legacyStore, legacyClient)
        assertIs<PairingMigrationResult.NoOffer>(legacyOwner.currentOffer())
        assertIs<StoreInspectResult.Missing>(legacyStore.inspect())

        val corruptStore = MemoryStore().apply { unreadable = true }
        val corruptClient = ScriptClient()
        assertIs<PairingMigrationResult.Unavailable>(owner(Publisher(), corruptStore, corruptClient).currentOffer())
        assertTrue(corruptClient.requests.isEmpty())
    }

    @Test
    fun uncertainCredentialGraphNeverOffersOrRequests() {
        val publisher = Publisher().apply {
            current = PairingGraphSnapshot.Uncertain(2, app.solstone.core.identity.PersistenceIssue.PERSISTENCE_FAILED)
        }
        val client = ScriptClient()
        assertIs<PairingMigrationResult.Unavailable>(owner(publisher, MemoryStore(), client).currentOffer())
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun storeWriteFailurePreventsPutAndOfferPublication() {
        val publisher = Publisher()
        val store = MemoryStore().apply { failSave = true }
        val client = ScriptClient()
        val owner = owner(publisher, store, client)
        assertIs<PairingMigrationResult.Unavailable>(owner.currentOffer())
        assertIs<PairingMigrationResult.Unavailable>(owner.keepBoth((publisher.current as PairingGraphSnapshot.Committed).pairing))
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun keepBothPersistsTheCompleteOperationBeforePutAndAcceptsExactEcho() {
        val publisher = Publisher()
        val store = MemoryStore()
        var openRoute: Route? = null
        val client = ScriptClient { method, _, _, body ->
            if (method == "PUT") {
                val saved = assertIs<StoreInspectResult.Ready<PairingMigrationRecord>>(store.inspect()).value
                assertEquals(PairingMigrationStage.SUBMITTED_UNKNOWN, saved.stage)
                assertEquals(body!!.toList(), saved.canonicalPutPayload!!.toByteArray(Charsets.UTF_8).toList())
                putSuccess(body, caller)
            } else error("unexpected method $method")
        }
        val owner = PairingMigrationOwner(publisher, store, PairingMigrationClientOpener { lease ->
            openRoute = if (lease is PairingLease.Direct) Route.DIRECT else Route.RELAY
            client
        }, newOperationId = { "123e4567-e89b-42d3-a456-426614174004" })

        val offered = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record
        val result = assertIs<PairingMigrationResult.Terminal>(owner.keepBoth(offered.generation))
        assertEquals(PairingMigrationStage.TERMINAL_KEEP_BOTH, result.record.stage)
        assertEquals(Route.DIRECT, openRoute)
        assertEquals(listOf("PUT /app/network/api/clients/self/migration"), client.requests.map { "${it.method} ${it.path}" })
    }

    @Test
    fun terminalAnswerIsReportedOnlyAfterItsStateIsPersisted() {
        listOf(
            Pair(PairingMigrationStage.TERMINAL_KEEP_BOTH, HttpResponse(200, emptyMap(), ByteArray(0))),
            Pair(PairingMigrationStage.TERMINAL_REFUSED, HttpResponse(400, emptyMap(), "{\"reason_code\":\"migration_request_invalid\"}".toByteArray())),
        ).forEach { (terminalStage, response) ->
            val publisher = Publisher()
            val store = MemoryStore().apply { failStage = terminalStage }
            val client = ScriptClient { method, path, _, body ->
                if (method == "PUT" && path == "/app/network/api/clients/self/migration") {
                    if (response.status == 200) putSuccess(body!!, caller) else response
                } else {
                    error("unexpected request $method $path")
                }
            }
            val owner = owner(publisher, store, client)
            val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation

            assertIs<PairingMigrationResult.Unavailable>(owner.keepBoth(generation))
            assertEquals(PairingMigrationStage.SUBMITTED_UNKNOWN, store.value?.stage)
            assertEquals(listOf("PUT"), client.requests.map { it.method })
        }
    }

    @Test
    fun relayLeaseUsesSameOwnerAndLostPutReconcilesGetThenReplaysIdenticalBytes() {
        val publisher = Publisher(route = Route.RELAY)
        val store = MemoryStore()
        val exactBodies = mutableListOf<String>()
        val client = ScriptClient { method, path, _, body ->
            when (method to path) {
                "PUT" to "/app/network/api/clients/self/migration" -> {
                    val saved = assertIs<StoreInspectResult.Ready<PairingMigrationRecord>>(store.inspect()).value
                    val raw = body!!.toString(Charsets.UTF_8)
                    assertEquals(saved.canonicalPutPayload, raw)
                    exactBodies += raw
                    if (exactBodies.size == 1) HttpResponse(503, emptyMap(), ByteArray(0)) else putSuccess(body, caller)
                }
                "GET" to "/app/network/api/clients/self/migration" -> state("none", null, null, null)
                else -> error("unexpected request $method $path")
            }
        }
        var route: Route? = null
        val owner = PairingMigrationOwner(publisher, store, PairingMigrationClientOpener { lease ->
            route = if (lease is PairingLease.Relay) Route.RELAY else Route.DIRECT
            client
        }, newOperationId = { "123e4567-e89b-42d3-a456-426614174004" })

        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        assertIs<PairingMigrationResult.Pending>(owner.keepBoth(generation))
        val restartedOwner = PairingMigrationOwner(
            publisher,
            store,
            PairingMigrationClientOpener { client },
            newOperationId = { "123e4567-e89b-42d3-a456-426614174004" },
        )
        assertIs<PairingMigrationResult.Terminal>(restartedOwner.resume(generation))
        assertEquals(Route.RELAY, route)
        assertEquals(2, exactBodies.size)
        assertEquals(exactBodies.first(), exactBodies.last())
        assertEquals(listOf("PUT", "GET", "PUT"), client.requests.map { it.method })
    }

    @Test
    fun terminalLookingGetStateRequiresIdenticalPutReplayAndExactPutEcho() {
        val publisher = Publisher()
        val store = MemoryStore()
        val putBodies = mutableListOf<ByteArray>()
        val client = ScriptClient { method, path, _, body ->
            when (method to path) {
                "PUT" to "/app/network/api/clients/self/migration" -> {
                    putBodies += body!!.copyOf()
                    if (putBodies.size == 1) HttpResponse(503, emptyMap(), ByteArray(0))
                    else putSuccess(body, caller)
                }
                "GET" to "/app/network/api/clients/self/migration" -> {
                    state("new_device", null, null, null)
                }
                else -> error("unexpected request $method $path")
            }
        }
        val owner = owner(publisher, store, client)
        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        assertIs<PairingMigrationResult.Pending>(owner.keepBoth(generation))
        val terminal = assertIs<PairingMigrationResult.Terminal>(owner.resume(generation))
        assertEquals(PairingMigrationStage.TERMINAL_KEEP_BOTH, terminal.record.stage)
        assertEquals(listOf("PUT", "GET", "PUT"), client.requests.map { it.method })
        assertEquals(2, putBodies.size)
        assertEquals(putBodies.first().toList(), putBodies.last().toList())
        assertEquals(
            terminal.record.canonicalPutPayload!!.toByteArray(Charsets.UTF_8).toList(),
            putBodies.first().toList(),
        )
    }

    @Test
    fun replayOfSubmittedReplacementStaysPendingWhenTargetIsGoneInsteadOfReopeningTheChoice() {
        val publisher = Publisher()
        val store = MemoryStore()
        var puts = 0
        val client = ScriptClient { method, path, _, _ ->
            when (method to path) {
                "GET" to "/app/network/api/clients" -> listResponse(listOf(clientRow(other, "Old phone")))
                "PUT" to "/app/network/api/clients/self/migration" -> {
                    puts++
                    // First send: response lost. Replay: the journal no longer lists the target, which is
                    // exactly what a committed replacement looks like from the outside.
                    if (puts == 1) HttpResponse(503, emptyMap(), ByteArray(0))
                    else HttpResponse(404, emptyMap(), "{\"reason_code\":\"paired_device_not_found\"}".toByteArray())
                }
                "GET" to "/app/network/api/clients/self/migration" -> state("none", null, null, null)
                else -> error("unexpected request $method $path")
            }
        }
        val owner = owner(publisher, store, client)
        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        val listing = assertIs<PairingMigrationResult.Listing>(owner.listClients(generation)).value
        assertIs<PairingMigrationResult.Offer>(owner.selectTarget(listing, other))
        val submitted = assertIs<PairingMigrationResult.Pending>(
            owner.replaceSelected(listing, other, nativeConfirmed = true),
        ).record
        assertEquals(PairingMigrationStage.SUBMITTED_UNKNOWN, submitted.stage)

        val replayed = assertIs<PairingMigrationResult.Pending>(owner.resume(generation))
        assertEquals(PairingMigrationPendingReason.DECISION_UNKNOWN, replayed.reason)
        assertEquals(PairingMigrationStage.SUBMITTED_UNKNOWN, replayed.record.stage)
        assertEquals(submitted.operationId, replayed.record.operationId)
        assertEquals(submitted.canonicalPutPayload, replayed.record.canonicalPutPayload)
        assertEquals(other, replayed.record.selectedCid)
        assertEquals(2, puts)

        // The durable record is unchanged and a conflicting answer stays disabled.
        val saved = assertIs<StoreInspectResult.Ready<PairingMigrationRecord>>(store.inspect()).value
        assertEquals(submitted, saved)
        assertIs<PairingMigrationResult.Pending>(owner.keepBoth(generation))
        assertIs<PairingMigrationResult.Pending>(owner.listClients(generation))
        assertEquals(2, puts)
    }

    @Test
    fun failedGetKeepsDecisionPendingWithoutReplayingPut() {
        val publisher = Publisher()
        val store = MemoryStore()
        val client = ScriptClient { method, path, _, _ ->
            when (method to path) {
                "PUT" to "/app/network/api/clients/self/migration" ->
                    HttpResponse(503, emptyMap(), ByteArray(0))
                "GET" to "/app/network/api/clients/self/migration" ->
                    HttpResponse(503, emptyMap(), ByteArray(0))
                else -> error("unexpected request $method $path")
            }
        }
        val owner = owner(publisher, store, client)
        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        val submitted = assertIs<PairingMigrationResult.Pending>(owner.keepBoth(generation)).record

        val stillPending = assertIs<PairingMigrationResult.Pending>(owner.resume(generation)).record

        assertEquals(PairingMigrationStage.SUBMITTED_UNKNOWN, stillPending.stage)
        assertEquals(submitted.operationId, stillPending.operationId)
        assertEquals(submitted.canonicalPutPayload, stillPending.canonicalPutPayload)
        assertEquals(listOf("PUT", "GET"), client.requests.map { it.method })
    }

    @Test
    fun emptyListAndExactCidSelectionDoNotTreatDisplayLabelsAsIdentity() {
        val publisher = Publisher()
        val store = MemoryStore()
        val client = ScriptClient { method, path, _, _ ->
            if (method == "GET" && path == "/app/network/api/clients") {
                assertEquals(PairingMigrationStage.LISTING, store.value?.stage)
                assertEquals(1L, store.value?.requestSequence)
                listResponse(listOf(clientRow(caller, "Same"), clientRow(other, "Same")))
            } else error("unexpected request $method $path")
        }
        val owner = owner(publisher, store, client)
        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        val listing = assertIs<PairingMigrationResult.Listing>(owner.listClients(generation)).value
        assertEquals(listOf(other), listing.clients.map(MigrationClient::cid))
        assertIs<PairingMigrationResult.InvalidSelection>(owner.selectTarget(listing, caller))
        assertIs<PairingMigrationResult.InvalidSelection>(owner.selectTarget(listing, "Same"))
        assertEquals(emptyList<String>(), client.requests.filter { it.method == "PUT" }.map { it.path })
    }

    @Test
    fun emptyUnavailableAndReplacedListingsCannotSelectAnUnlistedCid() {
        val emptyPublisher = Publisher()
        val emptyClient = ScriptClient { method, path, _, _ ->
            if (method == "GET" && path == "/app/network/api/clients") listResponse(emptyList())
            else error("unexpected request $method $path")
        }
        val emptyOwner = owner(emptyPublisher, MemoryStore(), emptyClient)
        val generation = assertIs<PairingMigrationResult.Offer>(emptyOwner.currentOffer()).record.generation
        val first = assertIs<PairingMigrationResult.Listing>(emptyOwner.listClients(generation)).value
        assertTrue(first.clients.isEmpty())
        val second = assertIs<PairingMigrationResult.Listing>(emptyOwner.listClients(generation)).value
        assertIs<PairingMigrationResult.InvalidSelection>(emptyOwner.selectTarget(first, other))
        assertIs<PairingMigrationResult.InvalidSelection>(emptyOwner.selectTarget(second, other))

        val unavailableClient = ScriptClient { _, _, _, _ -> error("no authenticated route") }
        val unavailableOwner = owner(Publisher(route = Route.NONE), MemoryStore(), unavailableClient)
        val unavailableGeneration = assertIs<PairingMigrationResult.Offer>(unavailableOwner.currentOffer()).record.generation
        val pending = assertIs<PairingMigrationResult.Pending>(unavailableOwner.listClients(unavailableGeneration))
        assertEquals(PairingMigrationStage.LISTING, pending.record.stage)
        assertTrue(unavailableClient.requests.isEmpty())
    }

    @Test
    fun listFailuresCarryTheirProvenRecoveryCondition() {
        listOf(
            Pair(HttpResponse(503, emptyMap(), ByteArray(0)), PairingMigrationPendingReason.LIST_OFFLINE),
            Pair(HttpResponse(200, emptyMap(), ByteArray(0)), PairingMigrationPendingReason.LIST_UNAVAILABLE),
            Pair(
                HttpResponse(403, emptyMap(), "{\"reason_code\":\"paired_device_not_found\"}".toByteArray()),
                PairingMigrationPendingReason.SAVED_CONNECTION_REFUSED,
            ),
        ).forEach { (response, expectedReason) ->
            val publisher = Publisher()
            val client = ScriptClient { method, path, _, _ ->
                if (method == "GET" && path == "/app/network/api/clients") response
                else error("unexpected request $method $path")
            }
            val owner = owner(publisher, MemoryStore(), client)
            val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
            val pending = assertIs<PairingMigrationResult.Pending>(owner.listClients(generation))
            assertEquals(expectedReason, pending.reason)
            assertTrue(client.requests.none { it.method == "PUT" })
        }

        val unavailablePublisher = Publisher(route = Route.NONE)
        val unavailableOwner = owner(unavailablePublisher, MemoryStore(), ScriptClient())
        val generation = assertIs<PairingMigrationResult.Offer>(unavailableOwner.currentOffer()).record.generation
        val pending = assertIs<PairingMigrationResult.Pending>(unavailableOwner.listClients(generation))
        assertEquals(PairingMigrationPendingReason.SAVED_CONNECTION_REFUSED, pending.reason)
    }

    @Test
    fun pinnedMissingTargetReopensOnlyAfterClearingTheRefusedOperation() {
        val publisher = Publisher()
        val store = MemoryStore()
        val replacement = "sha256:${"c".repeat(64)}"
        val operationIds = mutableListOf(
            "123e4567-e89b-42d3-a456-426614174004",
            "123e4567-e89b-42d3-a456-426614174005",
        )
        var puts = 0
        var lists = 0
        val client = ScriptClient { method, path, _, body ->
            when (method to path) {
                "GET" to "/app/network/api/clients" -> {
                    lists++
                    if (lists == 1) listResponse(listOf(clientRow(other, "Shared")))
                    else listResponse(listOf(clientRow(replacement, "Shared")))
                }
                "PUT" to "/app/network/api/clients/self/migration" -> {
                    puts++
                    if (puts == 1) HttpResponse(404, emptyMap(), "{\"reason_code\":\"paired_device_not_found\"}".toByteArray())
                    else putSuccess(body!!, caller, replaced = replacement)
                }
                else -> error("unexpected request $method $path")
            }
        }
        val owner = PairingMigrationOwner(
            publisher,
            store,
            PairingMigrationClientOpener { client },
            newOperationId = { operationIds.removeAt(0) },
        )
        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        val firstListing = assertIs<PairingMigrationResult.Listing>(owner.listClients(generation)).value
        assertIs<PairingMigrationResult.Offer>(owner.selectTarget(firstListing, other))
        val unavailable = assertIs<PairingMigrationResult.Offer>(
            owner.replaceSelected(firstListing, other, nativeConfirmed = true),
        ).record
        assertEquals(PairingMigrationStage.SHOWN_DEFERRED, unavailable.stage)
        assertEquals(TARGET_UNAVAILABLE_REASON, unavailable.refusalReason)
        assertNull(unavailable.selectedCid)
        assertNull(unavailable.operationId)
        assertNull(unavailable.canonicalPutPayload)

        val refreshed = assertIs<PairingMigrationResult.Listing>(owner.listClients(generation)).value
        assertEquals(listOf(replacement), refreshed.clients.map(MigrationClient::cid))
        assertIs<PairingMigrationResult.InvalidSelection>(owner.selectTarget(refreshed, other))
        assertIs<PairingMigrationResult.Offer>(owner.selectTarget(refreshed, replacement))
        val terminal = assertIs<PairingMigrationResult.Terminal>(
            owner.replaceSelected(refreshed, replacement, nativeConfirmed = true),
        )
        assertEquals(PairingMigrationStage.TERMINAL_REPLACED, terminal.record.stage)
        assertEquals(2, puts)
    }

    @Test
    fun unsupportedRefusalCanOnlyRetryAfterAnExplicitNewAttempt() {
        val publisher = Publisher()
        val store = MemoryStore()
        val operationIds = mutableListOf(
            "123e4567-e89b-42d3-a456-426614174004",
            "123e4567-e89b-42d3-a456-426614174005",
        )
        var puts = 0
        val client = ScriptClient { method, path, _, body ->
            if (method == "PUT" && path == "/app/network/api/clients/self/migration") {
                puts++
                if (puts == 1) {
                    HttpResponse(400, emptyMap(), "{\"reason_code\":\"migration_protocol_unsupported\"}".toByteArray())
                } else {
                    putSuccess(body!!, caller)
                }
            } else {
                error("unexpected request $method $path")
            }
        }
        val owner = PairingMigrationOwner(
            publisher,
            store,
            PairingMigrationClientOpener { client },
            newOperationId = { operationIds.removeAt(0) },
        )
        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        val unsupported = assertIs<PairingMigrationResult.Terminal>(owner.keepBoth(generation)).record
        assertEquals("migration_protocol_unsupported", unsupported.refusalReason)
        val reopened = assertIs<PairingMigrationResult.Offer>(owner.retryUnsupported(generation)).record
        assertEquals(PairingMigrationStage.SHOWN_DEFERRED, reopened.stage)
        assertNull(reopened.operationId)
        val terminal = assertIs<PairingMigrationResult.Terminal>(owner.keepBoth(generation)).record
        assertEquals(PairingMigrationStage.TERMINAL_KEEP_BOTH, terminal.stage)
        assertEquals(2, puts)

        val requestInvalidClient = ScriptClient { method, _, _, _ ->
            if (method == "PUT") {
                HttpResponse(400, emptyMap(), "{\"reason_code\":\"migration_request_invalid\"}".toByteArray())
            } else {
                error("unexpected request $method")
            }
        }
        val requestInvalidOwner = owner(Publisher(), MemoryStore(), requestInvalidClient)
        val requestInvalidGeneration = assertIs<PairingMigrationResult.Offer>(requestInvalidOwner.currentOffer()).record.generation
        val requestInvalid = assertIs<PairingMigrationResult.Terminal>(requestInvalidOwner.keepBoth(requestInvalidGeneration))
        val notRetryable = assertIs<PairingMigrationResult.Pending>(requestInvalidOwner.retryUnsupported(requestInvalidGeneration))
        assertEquals(PairingMigrationStage.TERMINAL_REFUSED, notRetryable.record.stage)
        assertEquals("migration_request_invalid", requestInvalid.record.refusalReason)
        assertEquals(listOf("PUT"), requestInvalidClient.requests.map { it.method })
    }

    @Test
    fun replaceRequiresNativeConfirmationAndPersistsTheExactSelectedCid() {
        val publisher = Publisher()
        val store = MemoryStore()
        val third = "sha256:${"c".repeat(64)}"
        val client = ScriptClient { method, path, _, body ->
            when (method to path) {
                "GET" to "/app/network/api/clients" -> listResponse(listOf(clientRow(caller, "same"), clientRow(other, "same"), clientRow(third, "same")))
                "PUT" to "/app/network/api/clients/self/migration" -> putSuccess(body!!, caller, replaced = other)
                else -> error("unexpected request $method $path")
            }
        }
        val owner = owner(publisher, store, client, "123e4567-e89b-42d3-a456-426614174004")
        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        val listing = assertIs<PairingMigrationResult.Listing>(owner.listClients(generation)).value
        assertEquals(2, listing.clients.size)
        assertIs<PairingMigrationResult.Offer>(owner.selectTarget(listing, other))
        assertIs<PairingMigrationResult.ConfirmationRequired>(owner.replaceSelected(listing, other, nativeConfirmed = false))
        assertFalse(client.requests.any { it.method == "PUT" })
        val replaced = assertIs<PairingMigrationResult.Terminal>(owner.replaceSelected(listing, other, nativeConfirmed = true))
        assertEquals(PairingMigrationStage.TERMINAL_REPLACED, replaced.record.stage)
        val saved = assertIs<StoreInspectResult.Ready<PairingMigrationRecord>>(store.inspect()).value
        assertEquals(other, saved.selectedCid)
        assertEquals("replace_device", (parseJson(saved.canonicalPutPayload!!) as Map<*, *>)["choice"])
    }

    @Test
    fun cancellingSelectedTargetOnlyDefersAndClearsItWithoutPutting() {
        val publisher = Publisher()
        val store = MemoryStore()
        val client = ScriptClient { method, path, _, _ ->
            if (method == "GET" && path == "/app/network/api/clients") listResponse(listOf(clientRow(other, "Device")))
            else error("unexpected request $method $path")
        }
        val owner = owner(publisher, store, client)
        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        val listing = assertIs<PairingMigrationResult.Listing>(owner.listClients(generation)).value
        assertIs<PairingMigrationResult.Offer>(owner.selectTarget(listing, other))

        val deferred = assertIs<PairingMigrationResult.Offer>(owner.defer(generation)).record
        assertEquals(PairingMigrationStage.SHOWN_DEFERRED, deferred.stage)
        assertNull(deferred.selectedCid)
        assertNull(deferred.operationId)
        assertTrue(client.requests.none { it.method == "PUT" })
    }

    @Test
    fun wrongEchoAndUnverifiableGetStayUnresolvedBeforeExactReplay() {
        val publisher = Publisher()
        val store = MemoryStore()
        var puts = 0
        val client = ScriptClient { method, path, _, body ->
            when (method to path) {
                "PUT" to "/app/network/api/clients/self/migration" -> {
                    puts++
                    if (puts == 1) putSuccess(body!!, other, op = "123e4567-e89b-42d3-a456-426614174005")
                    else putSuccess(body!!, caller)
                }
                "GET" to "/app/network/api/clients/self/migration" -> state("new_device", null, null, null)
                else -> error("unexpected request $method $path")
            }
        }
        val owner = owner(publisher, store, client, "123e4567-e89b-42d3-a456-426614174004")
        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        val pending = assertIs<PairingMigrationResult.Pending>(owner.keepBoth(generation)).record
        assertTrue(pending.echoMismatch)
        assertIs<PairingMigrationResult.Terminal>(owner.resume(generation))
        assertEquals(listOf("PUT", "GET", "PUT"), client.requests.map { it.method })
        assertEquals(2, puts)
    }

    @Test
    fun unresolvedReplacementCannotBeAnsweredWithConflictingKeepBothChoice() {
        val publisher = Publisher()
        val store = MemoryStore()
        val client = ScriptClient { method, path, _, _ ->
            when (method to path) {
                "GET" to "/app/network/api/clients" -> listResponse(listOf(clientRow(other, "Device")))
                "PUT" to "/app/network/api/clients/self/migration" -> HttpResponse(503, emptyMap(), ByteArray(0))
                else -> error("unexpected request $method $path")
            }
        }
        val owner = owner(publisher, store, client)
        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        val listing = assertIs<PairingMigrationResult.Listing>(owner.listClients(generation)).value
        assertIs<PairingMigrationResult.Offer>(owner.selectTarget(listing, other))
        assertIs<PairingMigrationResult.Pending>(owner.replaceSelected(listing, other, nativeConfirmed = true))
        val unresolved = assertIs<PairingMigrationResult.Pending>(owner.keepBoth(generation))
        assertEquals(PairingMigrationChoice.REPLACE, unresolved.record.choice)
        assertEquals(listOf("GET", "PUT"), client.requests.map { it.method })
    }

    @Test
    fun successfulPutRequiresExactOperationCallerChoiceAndTargetEchoes() {
        val wrongEchoes = listOf<(ByteArray) -> HttpResponse>(
            { body -> putSuccess(body, caller, op = "123e4567-e89b-42d3-a456-426614174005") },
            { body -> putSuccess(body, other) },
            { body -> putSuccess(body, caller, stateOverride = "replaced_device") },
            { body -> putSuccess(body, caller, replaced = other) },
        )
        wrongEchoes.forEach { response ->
            val publisher = Publisher()
            val store = MemoryStore()
            val client = ScriptClient { method, path, _, body ->
                if (method == "PUT" && path == "/app/network/api/clients/self/migration") response(body!!)
                else error("unexpected request $method $path")
            }
            val owner = owner(publisher, store, client)
            val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
            val pending = assertIs<PairingMigrationResult.Pending>(owner.keepBoth(generation)).record
            assertTrue(pending.echoMismatch)
            assertEquals(PairingMigrationStage.SUBMITTED_UNKNOWN, pending.stage)
        }

        val publisher = Publisher()
        val store = MemoryStore()
        val client = ScriptClient { method, path, _, body ->
            when (method to path) {
                "GET" to "/app/network/api/clients" -> listResponse(listOf(clientRow(other, "Device")))
                "PUT" to "/app/network/api/clients/self/migration" -> putSuccess(body!!, caller, replaced = caller)
                else -> error("unexpected request $method $path")
            }
        }
        val owner = owner(publisher, store, client)
        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        val listing = assertIs<PairingMigrationResult.Listing>(owner.listClients(generation)).value
        assertIs<PairingMigrationResult.Offer>(owner.selectTarget(listing, other))
        val pending = assertIs<PairingMigrationResult.Pending>(owner.replaceSelected(listing, other, nativeConfirmed = true))
        assertTrue(pending.record.echoMismatch)
    }

    @Test
    fun unpairInvalidatesUnknownDecisionAndNewGenerationStartsUnoffered() {
        val publisher = Publisher()
        val store = MemoryStore()
        val client = ScriptClient { method, _, _, _ ->
            if (method == "PUT") HttpResponse(503, emptyMap(), ByteArray(0)) else error("no recovery request expected")
        }
        val owner = owner(publisher, store, client)
        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        assertIs<PairingMigrationResult.Pending>(owner.keepBoth(generation))
        publisher.switchGeneration()
        assertIs<PairingMigrationResult.Unavailable>(owner.resume(generation))
        assertEquals(listOf("PUT"), client.requests.map { it.method })
        val next = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record
        assertEquals(PairingMigrationStage.OFFER_NOT_SHOWN, next.stage)
        assertNull(next.operationId)
        publisher.unpair()
        assertIs<PairingMigrationResult.NoOffer>(owner.currentOffer())
        assertIs<StoreInspectResult.Missing>(store.inspect())
        assertIs<PairingMigrationResult.Unavailable>(owner.resume(next.generation))
        assertEquals(listOf("PUT"), client.requests.map { it.method })
    }

    @Test
    fun definitePrecommitRefusalIsTerminalAndGenerationSwitchFencesListResult() {
        val publisher = Publisher()
        val store = MemoryStore()
        val refusalClient = ScriptClient { _, _, _, _ ->
            HttpResponse(400, emptyMap(), "{\"reason_code\":\"migration_request_invalid\"}".toByteArray())
        }
        val owner = owner(publisher, store, refusalClient)
        val generation = assertIs<PairingMigrationResult.Offer>(owner.currentOffer()).record.generation
        val refused = assertIs<PairingMigrationResult.Terminal>(owner.keepBoth(generation))
        assertTrue(refused.definitePrecommitRefusal)
        assertEquals(PairingMigrationStage.TERMINAL_REFUSED, refused.record.stage)

        val switchedStore = MemoryStore()
        lateinit var switchedOwner: PairingMigrationOwner
        val switchingClient = ScriptClient { _, _, _, _ ->
            publisher.switchGeneration()
            listResponse(listOf(clientRow(other, "device")))
        }
        switchedOwner = owner(publisher, switchedStore, switchingClient)
        val current = assertIs<PairingMigrationResult.Offer>(switchedOwner.currentOffer()).record.generation
        assertIs<PairingMigrationResult.StaleGeneration>(switchedOwner.listClients(current))
    }

    @Test
    fun soleDeviceSkipPersistsSkippedStageWithoutMigrationPut() {
        // Case 1: Empty clients array
        val publisher1 = Publisher()
        val store1 = MemoryStore()
        val client1 = ScriptClient { method, path, _, _ ->
            when (method to path) {
                "GET" to "/app/network/api/clients" -> listResponse(emptyList())
                else -> HttpResponse(500, emptyMap(), ByteArray(0))
            }
        }
        val owner1 = owner(publisher1, store1, client1)
        val gen1 = (publisher1.current as PairingGraphSnapshot.Committed).pairing
        val skipped1 = assertIs<InitialClientDecision.Skipped>(owner1.decideInitialClients(gen1)).record
        assertEquals(PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT, skipped1.stage)
        assertNull(skipped1.choice)
        assertNull(skipped1.selectedCid)
        assertNull(skipped1.operationId)
        assertNull(skipped1.canonicalPutPayload)
        assertNull(skipped1.refusalReason)
        assertFalse(skipped1.echoMismatch)
        assertEquals(1, client1.requests.size)
        assertEquals(Request("GET", "/app/network/api/clients", null), client1.requests.first())
        assertFalse(skipped1.canOpenDeviceChoice())

        // defer does not change stage
        val deferred = assertIs<PairingMigrationResult.Offer>(owner1.defer(gen1)).record
        assertEquals(PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT, deferred.stage)

        // keepBoth and resume do not PUT
        assertIs<PairingMigrationResult.Pending>(owner1.keepBoth(gen1))
        val resumed = assertIs<PairingMigrationResult.Offer>(owner1.resume(gen1)).record
        assertEquals(PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT, resumed.stage)
        assertEquals(1, client1.requests.size)

        // New owner on the same store: currentOffer is skipped, decideInitialClients returns Skipped with 0 new GETs
        val newOwner1 = owner(publisher1, store1, client1)
        val currentOffer = assertIs<PairingMigrationResult.Offer>(newOwner1.currentOffer()).record
        assertEquals(PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT, currentOffer.stage)
        assertIs<InitialClientDecision.Skipped>(newOwner1.decideInitialClients(gen1))
        assertEquals(1, client1.requests.size)

        // Case 2: Only this client-cert fingerprint in clients list
        val publisher2 = Publisher()
        val store2 = MemoryStore()
        val client2 = ScriptClient { method, path, _, _ ->
            when (method to path) {
                "GET" to "/app/network/api/clients" -> listResponse(listOf(clientRow(caller, "This Device")))
                else -> HttpResponse(500, emptyMap(), ByteArray(0))
            }
        }
        val owner2 = owner(publisher2, store2, client2)
        val gen2 = (publisher2.current as PairingGraphSnapshot.Committed).pairing
        val skipped2 = assertIs<InitialClientDecision.Skipped>(owner2.decideInitialClients(gen2)).record
        assertEquals(PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT, skipped2.stage)
        assertEquals(1, client2.requests.size)
    }

    @Test
    fun directAndRelayLeaseEmptySuccessBothSkip() {
        val directPublisher = Publisher(route = Route.DIRECT)
        val directStore = MemoryStore()
        val directClient = ScriptClient { _, _, _, _ -> listResponse(emptyList()) }
        val directOwner = owner(directPublisher, directStore, directClient)
        val directGen = (directPublisher.current as PairingGraphSnapshot.Committed).pairing
        assertIs<InitialClientDecision.Skipped>(directOwner.decideInitialClients(directGen))

        val relayPublisher = Publisher(route = Route.RELAY)
        val relayStore = MemoryStore()
        val relayClient = ScriptClient { _, _, _, _ -> listResponse(emptyList()) }
        val relayOwner = owner(relayPublisher, relayStore, relayClient)
        val relayGen = (relayPublisher.current as PairingGraphSnapshot.Committed).pairing
        assertIs<InitialClientDecision.Skipped>(relayOwner.decideInitialClients(relayGen))
    }

    @Test
    fun distinctCidWithMatchingDisplayLabelDoesNotSkip() {
        val publisher = Publisher()
        val store = MemoryStore()
        val client = ScriptClient { _, _, _, _ -> listResponse(listOf(clientRow(other, "Journal"))) }
        val owner = owner(publisher, store, client)
        val gen = (publisher.current as PairingGraphSnapshot.Committed).pairing
        val decision = assertIs<InitialClientDecision.OthersPresent>(owner.decideInitialClients(gen))
        assertEquals(PairingMigrationStage.OFFER_NOT_SHOWN, decision.record.stage)
        assertEquals(1, client.requests.size)
        assertTrue(client.requests.none { it.method == "PUT" })
    }

    @Test
    fun preflightFailureLeavesOfferNotShownAndDoesNotPut() {
        // Non-200
        val pub1 = Publisher()
        val store1 = MemoryStore()
        val client1 = ScriptClient { _, _, _, _ -> HttpResponse(500, emptyMap(), ByteArray(0)) }
        val owner1 = owner(pub1, store1, client1)
        val gen1 = (pub1.current as PairingGraphSnapshot.Committed).pairing
        assertIs<InitialClientDecision.PreflightFailed>(owner1.decideInitialClients(gen1))
        assertEquals(PairingMigrationStage.OFFER_NOT_SHOWN, store1.value?.stage)
        assertTrue(client1.requests.none { it.method == "PUT" })

        // Malformed 200
        val pub2 = Publisher()
        val store2 = MemoryStore()
        val client2 = ScriptClient { _, _, _, _ -> HttpResponse(200, emptyMap(), "not json".toByteArray()) }
        val owner2 = owner(pub2, store2, client2)
        val gen2 = (pub2.current as PairingGraphSnapshot.Committed).pairing
        assertIs<InitialClientDecision.PreflightFailed>(owner2.decideInitialClients(gen2))
        assertEquals(PairingMigrationStage.OFFER_NOT_SHOWN, store2.value?.stage)

        // Route.NONE (no lease)
        val pub3 = Publisher(route = Route.NONE)
        val store3 = MemoryStore()
        val client3 = ScriptClient()
        val owner3 = owner(pub3, store3, client3)
        val gen3 = (pub3.current as PairingGraphSnapshot.Committed).pairing
        assertIs<InitialClientDecision.PreflightFailed>(owner3.decideInitialClients(gen3))
        assertEquals(PairingMigrationStage.OFFER_NOT_SHOWN, store3.value?.stage)

        // After claimInitialPresentation, second decide does not GET
        val pub4 = Publisher()
        val store4 = MemoryStore()
        val client4 = ScriptClient { _, _, _, _ -> HttpResponse(503, emptyMap(), ByteArray(0)) }
        val owner4 = owner(pub4, store4, client4)
        val gen4 = (pub4.current as PairingGraphSnapshot.Committed).pairing
        assertIs<InitialClientDecision.PreflightFailed>(owner4.decideInitialClients(gen4))
        assertEquals(1, client4.requests.size)
        assertTrue(owner4.claimInitialPresentation(gen4))
        val notUnseen = assertIs<InitialClientDecision.NotUnseen>(owner4.decideInitialClients(gen4))
        assertEquals(PairingMigrationStage.SHOWN_DEFERRED, (notUnseen.result as PairingMigrationResult.Offer).record.stage)
        assertEquals(1, client4.requests.size)
    }

    @Test
    fun preflightSaveFailureLeavesOfferNotShown() {
        val publisher = Publisher()
        val store = MemoryStore()
        store.failStage = PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT
        val client = ScriptClient { _, _, _, _ -> listResponse(emptyList()) }
        val owner = owner(publisher, store, client)
        val gen = (publisher.current as PairingGraphSnapshot.Committed).pairing
        assertIs<InitialClientDecision.PreflightFailed>(owner.decideInitialClients(gen))
        assertEquals(PairingMigrationStage.OFFER_NOT_SHOWN, store.value?.stage)
    }

    @Test
    fun unknownLegacyDecideDoesNotCreateRecordAndDoesNotGet() {
        val publisher = Publisher(provenance = PairingProvenance.UNKNOWN_LEGACY)
        val store = MemoryStore()
        val client = ScriptClient()
        val owner = owner(publisher, store, client)
        val gen = (publisher.current as PairingGraphSnapshot.Committed).pairing
        val decision = assertIs<InitialClientDecision.NotUnseen>(owner.decideInitialClients(gen))
        assertIs<PairingMigrationResult.NoOffer>(decision.result)
        assertNull(store.value)
        assertEquals(0, client.requests.size)
    }

    @Test
    fun blockingGenerationFencePreventsSkipAndFencesStaleGeneration() {
        val publisher = Publisher()
        val store = MemoryStore()
        val enteredLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val client = ScriptClient { _, _, _, _ ->
            enteredLatch.countDown()
            releaseLatch.await(5, TimeUnit.SECONDS)
            listResponse(emptyList())
        }
        val owner = owner(publisher, store, client)
        val gen = (publisher.current as PairingGraphSnapshot.Committed).pairing

        val decisionRef = AtomicReference<InitialClientDecision>()
        val thread = thread {
            decisionRef.set(owner.decideInitialClients(gen))
        }

        assertTrue(enteredLatch.await(5, TimeUnit.SECONDS))
        publisher.unpair()
        publisher.switchGeneration()
        releaseLatch.countDown()
        thread.join(5000)

        assertIs<InitialClientDecision.StaleGeneration>(decisionRef.get())
        assertFalse(store.value?.stage == PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT)

        val newGen = (publisher.current as PairingGraphSnapshot.Committed).pairing
        assertEquals("inst-2", newGen.instanceId)
        val skipped = assertIs<InitialClientDecision.Skipped>(owner.decideInitialClients(newGen)).record
        assertEquals(PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT, skipped.stage)
        assertEquals("inst-2", skipped.generation.instanceId)
        assertEquals(newGen, skipped.generation)
        assertEquals(2, client.requests.size)
        assertTrue(client.requests.none { it.method == "PUT" })
    }

    @Test
    fun concurrentDecideInitialClientsSharesSingleGet() {
        val publisher = Publisher()
        val store = MemoryStore()
        val enteredLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val secondThreadStarted = CountDownLatch(1)
        val decision2 = AtomicReference<InitialClientDecision>()
        var thread2: Thread? = null
        lateinit var owner: PairingMigrationOwner
        val client = ScriptClient { _, _, _, _ ->
            enteredLatch.countDown()
            thread2 = thread {
                secondThreadStarted.countDown()
                decision2.set(owner.decideInitialClients((publisher.current as PairingGraphSnapshot.Committed).pairing))
            }
            secondThreadStarted.await(5, TimeUnit.SECONDS)
            releaseLatch.await(5, TimeUnit.SECONDS)
            listResponse(emptyList())
        }
        owner = owner(publisher, store, client)
        val gen = (publisher.current as PairingGraphSnapshot.Committed).pairing

        val decision1 = AtomicReference<InitialClientDecision>()
        val thread1 = thread {
            decision1.set(owner.decideInitialClients(gen))
        }
        assertTrue(enteredLatch.await(5, TimeUnit.SECONDS))
        releaseLatch.countDown()

        thread1.join(5000)
        thread2?.join(5000)

        assertIs<InitialClientDecision.Skipped>(decision1.get())
        assertIs<InitialClientDecision.Skipped>(decision2.get())
        assertEquals(1, client.requests.size)

        // Sequential second call
        assertIs<InitialClientDecision.Skipped>(owner.decideInitialClients(gen))
        assertEquals(1, client.requests.size)
    }

    @Test
    fun initialOthersPresentWithSubsequentEmptyListingCannotSelectAndRemainsPickerEmpty() {
        val publisher = Publisher()
        val store = MemoryStore()
        var returnEmptyList = false
        val client = ScriptClient { method, path, _, _ ->
            when (method to path) {
                "GET" to "/app/network/api/clients" -> if (returnEmptyList) listResponse(emptyList()) else listResponse(listOf(clientRow(other, "Device 2")))
                else -> HttpResponse(500, emptyMap(), ByteArray(0))
            }
        }
        val owner = owner(publisher, store, client)
        val gen = (publisher.current as PairingGraphSnapshot.Committed).pairing

        val initialDecision = assertIs<InitialClientDecision.OthersPresent>(owner.decideInitialClients(gen))
        assertEquals(PairingMigrationStage.OFFER_NOT_SHOWN, initialDecision.record.stage)
        assertTrue(owner.claimInitialPresentation(gen))

        returnEmptyList = true
        val listingResult = assertIs<PairingMigrationResult.Listing>(owner.listClients(gen))
        assertTrue(listingResult.value.clients.isEmpty())

        val selectionResult = owner.selectTarget(listingResult.value, other)
        assertIs<PairingMigrationResult.InvalidSelection>(selectionResult)
        assertFalse(store.value?.stage == PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT)
    }

    private enum class Route { DIRECT, RELAY, NONE }

    private class MemoryStore : PairingMigrationStore {
        var value: PairingMigrationRecord? = null
        var failSave = false
        var failStage: PairingMigrationStage? = null
        var unreadable = false
        override fun inspect(): StoreInspectResult<PairingMigrationRecord> = when {
            unreadable -> StoreInspectResult.Unreadable(app.solstone.core.identity.PersistenceIssue.PERSISTENCE_FAILED, "test")
            value == null -> StoreInspectResult.Missing
            else -> StoreInspectResult.Ready(value!!)
        }
        override fun save(record: PairingMigrationRecord) {
            if (failSave || record.stage == failStage) error("write failed")
            value = record
        }
        override fun clear() { value = null }
    }

    private inner class Publisher(
        provenance: PairingProvenance = PairingProvenance.FRESH_LINK,
        route: Route = Route.DIRECT,
    ) : PairingPublisher {
        var current: PairingGraphSnapshot = committed(
            instanceId = "inst-1",
            callerCid = "sha256:${"a".repeat(64)}",
            provenance = provenance,
            route = route,
        )
        private val credential = ClientCredential("private", "certificate", listOf("ca"))
        override fun currentSnapshot(): PairingGraphSnapshot = current
        override fun subscribe(observer: (PairingGraphSnapshot) -> Unit): SubscriptionHandle = SubscriptionHandle {}
        override fun <T> withMutationBoundary(block: () -> T): T = block()
        override fun acquireDirectLease(): PairingLease.Direct? {
            val snapshot = current as? PairingGraphSnapshot.Committed ?: return null
            return if (snapshot.isDirectEligible) PairingLease.Direct(snapshot, credential, DirectEndpoint("127.0.0.1", 7657)) else null
        }
        override fun acquireRelayLease(): PairingLease.Relay? {
            val snapshot = current as? PairingGraphSnapshot.Committed ?: return null
            return if (snapshot.isRelayEligible) PairingLease.Relay(snapshot, credential, snapshot.home.relayOrigin!!, snapshot.home.instanceId, snapshot.home.deviceToken!!) else null
        }
        override fun validateLease(lease: PairingLease): Boolean =
            (current as? PairingGraphSnapshot.Committed)?.pairing == lease.snapshot.pairing
        override fun installOrReplace(home: PairedHome, credential: ClientCredential, directEndpoint: DirectEndpoint?, isDirectAssociated: Boolean, provenance: PairingProvenance, directEndpoints: List<app.solstone.core.model.DirectEndpoint>): GraphMutationResult = error("unused")
        override fun replaceDirectEndpoints(expected: app.solstone.core.identity.PairingGraphSnapshot.Committed, endpoints: List<app.solstone.core.model.DirectEndpoint>) = app.solstone.core.identity.GraphMutationResult.Conflict("unsupported")
        override fun updateRelayAccess(expectedPairing: PairingGeneration, relayOrigin: String, deviceToken: String, expiresAt: String?): GraphMutationResult = error("unused")
        override fun revokeRelayAccess(expectedPairing: PairingGeneration): GraphMutationResult = error("unused")
        override fun forget(): GraphMutationResult {
            val absent = PairingGraphSnapshot.Absent(2)
            current = absent
            return GraphMutationResult.Cleared(absent)
        }
        override fun associateDirectIfProven(expectedPairing: PairingGeneration, endpoint: DirectEndpoint, proof: () -> Boolean): Boolean = false
        fun switchGeneration() { current = committed("inst-2", other, PairingProvenance.FRESH_LINK, Route.DIRECT) }
        fun unpair() { current = PairingGraphSnapshot.Absent(2) }
    }

    private data class Request(val method: String, val path: String, val body: ByteArray?)

    private inner class ScriptClient(
        private val handler: (String, String, Map<String, String>, ByteArray?) -> HttpResponse = { method, path, _, _ ->
            when (method to path) {
                "GET" to "/app/network/api/clients" -> listResponse(emptyList())
                "GET" to "/app/network/api/clients/self/migration" -> state("none", null, null, null)
                else -> HttpResponse(503, emptyMap(), ByteArray(0))
            }
        },
    ) : PlHttpClient {
        val requests = mutableListOf<Request>()
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int): HttpResponse {
            requests += Request(method, path, body?.copyOf())
            return handler(method, path, headers, body)
        }
    }

    private fun owner(
        publisher: Publisher,
        store: MemoryStore,
        client: ScriptClient,
        operationId: String = "123e4567-e89b-42d3-a456-426614174004",
    ) = PairingMigrationOwner(publisher, store, PairingMigrationClientOpener { client }, newOperationId = { operationId })

    private fun committed(instanceId: String, callerCid: String, provenance: PairingProvenance, route: Route): PairingGraphSnapshot.Committed {
        val home = PairedHome(
            instanceId = instanceId,
            homeLabel = "Journal",
            relayOrigin = if (route == Route.RELAY) "https://relay.example.invalid" else null,
            caChainFingerprint = "sha256:${"c".repeat(64)}",
            clientCertFingerprint = callerCid,
            observerHandle = null,
            deviceToken = if (route == Route.RELAY) "token" else null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        return PairingGraphSnapshot.Committed(
            sequenceNumber = 1,
            revisions = GraphRevisions(1, 1, 1),
            home = home,
            hasDirectEndpoint = route == Route.DIRECT,
            directAssociated = route == Route.DIRECT,
            relayLiveEligible = route == Route.RELAY,
            provenance = provenance,
            directEndpoint = if (route == Route.DIRECT) DirectEndpoint("127.0.0.1", 7657) else null,
        )
    }

    private fun listResponse(rows: List<String>) = HttpResponse(
        200,
        emptyMap(),
        rows.joinToString(prefix = "{\"clients\":[", postfix = "]}").toByteArray(),
    )

    private fun clientRow(cid: String, label: String): String {
        val fields = listOf(
            "cid", "cid_short", "device_label", "client_label", "label_ordinal", "display_label", "paired_at",
            "role", "network", "kind", "last_seen_at", "last_accepted_ingest_at", "last_accepted_segment", "state",
            "group", "elapsed_ms", "clock_skew", "label", "reach", "capture_state", "capture_elapsed_ms",
            "unassessed_reason", "failing", "ingest_rejection", "source_delivery", "reported", "owner_label",
            "description_revision", "description_updated_at",
        )
        return fields.joinToString(prefix = "{", postfix = "}") { key ->
            val value = when (key) {
                "cid" -> "\"$cid\""
                "display_label" -> "\"$label\""
                else -> "null"
            }
            "\"$key\":$value"
        }
    }

    private fun putSuccess(
        body: ByteArray,
        cid: String,
        op: String? = null,
        replaced: String? = null,
        stateOverride: String? = null,
    ): HttpResponse {
        val request = parseJson(body.toString(Charsets.UTF_8)) as Map<*, *>
        val operation = op ?: request["operation_id"] as String
        val choice = request["choice"] as String
        val response = linkedMapOf<String, Any?>(
            "protocol_version" to 1,
            "operation_id" to operation,
            "state" to (stateOverride ?: if (choice == "new_device") "new_device" else "replaced_device"),
            "previous_cid" to null,
            "cid" to cid,
            "replaced_cid" to replaced,
            "display_label" to "Current device",
        )
        return HttpResponse(200, emptyMap(), toJson(response).toByteArray())
    }

    private fun state(state: String, operation: String?, previous: String?, replaced: String?) = HttpResponse(
        200,
        emptyMap(),
        toJson(linkedMapOf(
            "protocol_version" to 1,
            "rekey_operation_id" to operation,
            "previous_cid" to previous,
            "state" to state,
            "replaced_cid" to replaced,
        )).toByteArray(),
    )
}
