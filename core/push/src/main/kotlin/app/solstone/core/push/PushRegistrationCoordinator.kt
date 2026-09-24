// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import app.solstone.core.identity.ObtainResult
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PushKeyAccess
import app.solstone.core.pl.CoalescingBoundedJob
import app.solstone.core.pl.PlHttpClient
import java.io.Closeable
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed interface PushDeliveryState {
    data object Ready : PushDeliveryState
    data object JournalHasNoPush : PushDeliveryState
    data object NoDeliveryApp : PushDeliveryState
    data object ChooseDeliveryApp : PushDeliveryState
    data class WaitingForDelivery(val distributorPackage: String, val unanswered: Boolean) : PushDeliveryState
    data object InsecureAddress : PushDeliveryState
    data object NotLinked : PushDeliveryState
    data class Failed(val reason: String) : PushDeliveryState
    data object Off : PushDeliveryState
}

private data class PushPassSnapshot(
    val generation: PairingGeneration,
    val openClient: () -> PlHttpClient,
    val pairingNow: () -> PairingGeneration?,
)

class PushRegistrationCoordinator(
    private val directory: File,
    private val port: DistributorPort?,
    val enabled: Boolean,
    private val pushKeys: PushKeyAccess,
    private val pairingNow: () -> PairingGeneration?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit,
    private val enqueue: () -> Unit,
    executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "push-registration-coordinator").apply { isDaemon = true }
    },
    boundMillis: Long = 15_000L,
    private val afterPass: () -> Unit = {},
) : Closeable {
    private val lock = Any()
    private val stateFile = File(directory, PushRegistrationFile.FILE_NAME)
    private var memoryState: PushRegistrationState = PushRegistrationFile.read(stateFile, log)

    @Volatile
    var deliveryState: PushDeliveryState = if (enabled) memoryState.delivery else PushDeliveryState.Off
        private set

    private val listeners = CopyOnWriteArrayList<(PushDeliveryState) -> Unit>()

    private val job = CoalescingBoundedJob<PushPassSnapshot>(
        name = "push-registration-coordinator",
        boundMillis = boundMillis,
        executor = executor,
    )

    companion object {
        const val T = 60_000L
    }

    init {
        if (!enabled) {
            deliveryState = PushDeliveryState.Off
        }
    }

    internal fun fenceJobGeneration(): Long = job.fenceGeneration()

    fun addDeliveryStateListener(listener: (PushDeliveryState) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(deliveryState)
        return {
            listeners.remove(listener)
        }
    }

    private fun updateDeliveryStateLocked(newState: PushDeliveryState) {
        deliveryState = newState
        memoryState = memoryState.copy(delivery = newState)
    }

    private fun persistLocked() {
        PushRegistrationFile.write(stateFile, memoryState, log)
    }

    private fun notifyListeners(state: PushDeliveryState) {
        for (listener in listeners) {
            runCatching { listener(state) }
        }
    }

    fun onUsableConnection(
        generation: PairingGeneration,
        openClient: () -> PlHttpClient,
        pairingNow: () -> PairingGeneration? = this.pairingNow,
    ) {
        val snapshot = PushPassSnapshot(generation, openClient, pairingNow)
        job.submit(snapshot) { snap, jobGen ->
            executePass(snap, jobGen)
        }
    }

    private fun executePass(snap: PushPassSnapshot, jobGen: Long) {
        try {
            val G = snap.pairingNow()
            fun stopped(): Boolean = snap.pairingNow() != G || job.currentGeneration() != jobGen

            if (!enabled || port == null || G == null) {
                var stateToNotify: PushDeliveryState? = null
                synchronized(lock) {
                    updateDeliveryStateLocked(PushDeliveryState.Off)
                    persistLocked()
                    stateToNotify = deliveryState
                }
                stateToNotify?.let(::notifyListeners)
                return
            }

            var client: PlHttpClient? = null
            try {
                if (stopped()) return
                client = snap.openClient()
                if (stopped()) return

                val vapidResult = fetchVapidKey(client)
                if (stopped()) return

                when (vapidResult) {
                    VapidKeyResult.NoPush -> {
                        var stateToNotify: PushDeliveryState? = null
                        synchronized(lock) {
                            updateDeliveryStateLocked(PushDeliveryState.JournalHasNoPush)
                            persistLocked()
                            stateToNotify = deliveryState
                        }
                        stateToNotify?.let(::notifyListeners)
                        runDeletePhase(client, G, snap, jobGen)
                        return
                    }
                    VapidKeyResult.Invalid -> {
                        var stateToNotify: PushDeliveryState? = null
                        synchronized(lock) {
                            updateDeliveryStateLocked(PushDeliveryState.Failed("bad_vapid_key"))
                            persistLocked()
                            stateToNotify = deliveryState
                        }
                        stateToNotify?.let(::notifyListeners)
                        runDeletePhase(client, G, snap, jobGen)
                        return
                    }
                    is VapidKeyResult.Failed -> {
                        var stateToNotify: PushDeliveryState? = null
                        synchronized(lock) {
                            updateDeliveryStateLocked(PushDeliveryState.Failed("vapid_unavailable"))
                            persistLocked()
                            stateToNotify = deliveryState
                        }
                        stateToNotify?.let(::notifyListeners)
                        runDeletePhase(client, G, snap, jobGen)
                        return
                    }
                    is VapidKeyResult.Key -> {
                        val K = vapidResult.publicKey
                        if (stopped()) return
                        val initialEndpoint = synchronized(lock) { memoryState.endpoint }
                        val available = port.available()
                        if (stopped()) return

                        var D: String? = null
                        var ownerPickStale: String? = null
                        synchronized(lock) {
                            val pick = memoryState.ownerPick
                            if (pick != null && pick in available) {
                                D = pick
                            } else if (pick != null) {
                                ownerPickStale = pick
                                if (memoryState.ownerPick == pick) {
                                    memoryState = memoryState.copy(ownerPick = null)
                                    persistLocked()
                                }
                            }
                        }

                        if (D == null) {
                            if (stopped()) return
                            // When two or more legacy distributors are installed and none is the default, resolution returns NoneAvailable, including on a phone with Play services.
                            val resolution = port.resolveDefault()
                            if (stopped()) return

                            when (resolution) {
                                DistributorResolution.NoneAvailable -> {
                                    var stateToNotify: PushDeliveryState? = null
                                    synchronized(lock) {
                                        clearStaleEndpointIfNecessaryLocked(available, G)
                                        updateDeliveryStateLocked(PushDeliveryState.NoDeliveryApp)
                                        persistLocked()
                                        stateToNotify = deliveryState
                                    }
                                    stateToNotify?.let(::notifyListeners)
                                    runDeletePhase(client, G, snap, jobGen)
                                    return
                                }
                                DistributorResolution.ToSelect -> {
                                    var stateToNotify: PushDeliveryState? = null
                                    synchronized(lock) {
                                        clearStaleEndpointIfNecessaryLocked(available, G)
                                        updateDeliveryStateLocked(PushDeliveryState.ChooseDeliveryApp)
                                        persistLocked()
                                        stateToNotify = deliveryState
                                    }
                                    stateToNotify?.let(::notifyListeners)
                                    runDeletePhase(client, G, snap, jobGen)
                                    return
                                }
                                is DistributorResolution.Found -> {
                                    D = resolution.packageName
                                }
                            }
                        }

                        val chosenDistributor = D ?: return
                        val X = PushRegistrationIdentity(G, K, chosenDistributor)

                        var isUsable = false
                        var proceedToRegister = false
                        var proceedToPost = false
                        var isWaitCase = false
                        var waitDeliveryState: PushDeliveryState? = null
                        var casMismatch = false

                        synchronized(lock) {
                            if (memoryState.endpoint != initialEndpoint) {
                                casMismatch = true
                                return@synchronized
                            }
                            isUsable = initialEndpoint != null &&
                                initialEndpoint.identity == X &&
                                chosenDistributor in available &&
                                initialEndpoint.p256dh.isNotEmpty() &&
                                initialEndpoint.auth.isNotEmpty()

                            if (!isUsable) {
                                if (initialEndpoint != null) {
                                    val pending = if (initialEndpoint.posted) {
                                        memoryState.pendingDeletes + PendingPushDelete(
                                            initialEndpoint.url,
                                            initialEndpoint.identity?.generation ?: G,
                                            0,
                                        )
                                    } else {
                                        memoryState.pendingDeletes
                                    }
                                    memoryState = memoryState.copy(endpoint = null, pendingDeletes = pending)
                                    persistLocked()
                                }

                                val now = clock()
                                val att = memoryState.attempt
                                val unregAt = memoryState.unregisteredAt
                                val hasYoungAttempt = att != null && att.identity == X && (0L <= now - att.startMillis && now - att.startMillis < T)
                                val hasYoungUnreg = unregAt != null && (0L <= now - unregAt && now - unregAt < T)

                                if (hasYoungAttempt || hasYoungUnreg) {
                                    isWaitCase = true
                                    // An installed external distributor that never answers is registered again once per T, and the state is WaitingForDelivery(that package, unanswered = true). The phone does not switch to the embedded distributor on its own.
                                    val nextDel = if (hasYoungAttempt && att?.failure != null) {
                                        PushDeliveryState.Failed(att.failure)
                                    } else {
                                        PushDeliveryState.WaitingForDelivery(chosenDistributor, hasYoungAttempt && att?.unanswered == true && att.answered == false)
                                    }
                                    waitDeliveryState = nextDel
                                    updateDeliveryStateLocked(nextDel)
                                    persistLocked()
                                } else {
                                    proceedToRegister = true
                                }
                            } else {
                                proceedToPost = true
                            }
                        }

                        if (casMismatch) {
                            if (snap.pairingNow() == G && job.currentGeneration() == jobGen) {
                                runDeletePhase(client, G, snap, jobGen)
                            }
                            return
                        }

                        if (isWaitCase) {
                            waitDeliveryState?.let(::notifyListeners)
                            if (snap.pairingNow() == G && job.currentGeneration() == jobGen) {
                                runDeletePhase(client, G, snap, jobGen)
                            }
                            return
                        }

                        if (proceedToRegister) {
                            val lastReg = synchronized(lock) { memoryState.lastRegistered }
                            if (lastReg != X) {
                                if (stopped()) return
                                port.unregister()
                            }
                            if (stopped()) return
                            // A distributor-requested migration (UNREGISTERED carrying a new distributor) is undone on the next pass. The pass saves the distributor it resolved.
                            port.save(chosenDistributor)

                            var stateToNotify: PushDeliveryState? = null
                            synchronized(lock) {
                                if (stopped()) {
                                    return
                                }
                                val now = clock()
                                val prevAtt = memoryState.attempt
                                val isUnanswered = prevAtt != null &&
                                    prevAtt.identity == X &&
                                    !prevAtt.answered &&
                                    prevAtt.failure == null &&
                                    now - prevAtt.startMillis >= T
                                val newAtt = PushRegistrationAttempt(X, now, answered = false, unanswered = isUnanswered, failure = null)
                                val newDelivery = PushDeliveryState.WaitingForDelivery(chosenDistributor, isUnanswered)
                                memoryState = memoryState.copy(attempt = newAtt, lastRegistered = X)
                                updateDeliveryStateLocked(newDelivery)
                                persistLocked()
                                stateToNotify = deliveryState
                            }
                            stateToNotify?.let(::notifyListeners)

                            val vapidBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(K)
                            // An event the connector already accepted before unregister can still arrive afterward. If it is stamped with the new attempt, it is POSTed under the new identity. The window is the delivery already in flight.
                            port.register(vapidBase64Url)
                            if (snap.pairingNow() == G && job.currentGeneration() == jobGen) {
                                runDeletePhase(client, G, snap, jobGen)
                            }
                            return
                        }

                        if (proceedToPost) {
                            val endpointToPost = initialEndpoint ?: return
                            if (!isAcceptableEndpoint(endpointToPost.url)) {
                                var stateToNotify: PushDeliveryState? = null
                                synchronized(lock) {
                                    updateDeliveryStateLocked(PushDeliveryState.InsecureAddress)
                                    persistLocked()
                                    stateToNotify = deliveryState
                                }
                                stateToNotify?.let(::notifyListeners)
                                if (snap.pairingNow() == G && job.currentGeneration() == jobGen) {
                                    runDeletePhase(client, G, snap, jobGen)
                                }
                                return
                            }

                            val obtainResult = pushKeys.obtainPushKey(G)
                            val pushKey = when (obtainResult) {
                                ObtainResult.Refused -> {
                                    if (snap.pairingNow() == G && job.currentGeneration() == jobGen) {
                                        runDeletePhase(client, G, snap, jobGen)
                                    }
                                    return
                                }
                                is ObtainResult.Failed -> {
                                    var stateToNotify: PushDeliveryState? = null
                                    synchronized(lock) {
                                        updateDeliveryStateLocked(PushDeliveryState.Failed("key_unavailable"))
                                        persistLocked()
                                        stateToNotify = deliveryState
                                    }
                                    stateToNotify?.let(::notifyListeners)
                                    if (snap.pairingNow() == G && job.currentGeneration() == jobGen) {
                                        runDeletePhase(client, G, snap, jobGen)
                                    }
                                    return
                                }
                                is ObtainResult.Obtained -> obtainResult.bytes
                            }

                            var casPostedOk = false
                            synchronized(lock) {
                                if (memoryState.endpoint == endpointToPost) {
                                    memoryState = memoryState.copy(endpoint = endpointToPost.copy(posted = true))
                                    persistLocked()
                                    casPostedOk = true
                                }
                            }
                            if (!casPostedOk) {
                                if (snap.pairingNow() == G && job.currentGeneration() == jobGen) {
                                    runDeletePhase(client, G, snap, jobGen)
                                }
                                return
                            }

                            if (snap.pairingNow() != G || job.currentGeneration() != jobGen) {
                                return
                            }

                            val regResult = registerPush(
                                client = client,
                                endpoint = endpointToPost.url,
                                p256dh = endpointToPost.p256dh,
                                auth = endpointToPost.auth,
                                pushKey = pushKey,
                            )

                            if (snap.pairingNow() != G) {
                                return
                            }

                            val nextDelivery = when (regResult) {
                                RegisterPushResult.Registered -> PushDeliveryState.Ready
                                RegisterPushResult.NoPush -> PushDeliveryState.JournalHasNoPush
                                RegisterPushResult.Refused -> PushDeliveryState.Failed("journal_refused")
                                RegisterPushResult.NotLinked -> {
                                    log("kind=push reason=not_linked")
                                    PushDeliveryState.NotLinked
                                }
                                is RegisterPushResult.Failed -> PushDeliveryState.Failed("journal_unreachable")
                            }

                            var stateToNotify: PushDeliveryState? = null
                            synchronized(lock) {
                                if (snap.pairingNow() != G) return
                                val curEp = memoryState.endpoint
                                if (curEp != null && curEp.url == endpointToPost.url && curEp.p256dh == endpointToPost.p256dh && curEp.auth == endpointToPost.auth) {
                                    updateDeliveryStateLocked(nextDelivery)
                                    persistLocked()
                                    stateToNotify = deliveryState
                                }
                            }
                            stateToNotify?.let(::notifyListeners)

                            if (snap.pairingNow() == G && job.currentGeneration() == jobGen) {
                                runDeletePhase(client, G, snap, jobGen)
                            }
                        }
                    }
                }
            } finally {
                try {
                    (client as? Closeable)?.close()
                } catch (_: Throwable) {
                }
            }
        } finally {
            afterPass()
        }
    }

    private fun clearStaleEndpointIfNecessaryLocked(available: List<String>, G: PairingGeneration) {
        val cur = memoryState.endpoint ?: return
        if (cur.identity == null || cur.identity.distributorPackage !in available) {
            val pending = if (cur.posted) {
                memoryState.pendingDeletes + PendingPushDelete(cur.url, cur.identity?.generation ?: G, 0)
            } else {
                memoryState.pendingDeletes
            }
            memoryState = memoryState.copy(endpoint = null, pendingDeletes = pending)
            persistLocked()
        }
    }

    private fun runDeletePhase(
        client: PlHttpClient,
        G: PairingGeneration,
        snap: PushPassSnapshot,
        jobGen: Long,
    ) {
        if (snap.pairingNow() != G || job.currentGeneration() != jobGen) {
            return
        }
        // A journal row left under an older generation, or a pending DELETE dropped after 3 failures, stays on the journal. It stops being a recipient 30 days after its last refresh, or when that device identity is unpaired there. The journal does not remove it when a send fails.
        val toDelete = synchronized(lock) {
            if (snap.pairingNow() != G || job.currentGeneration() != jobGen) {
                return
            }
            val matching = memoryState.pendingDeletes.filter { it.generation == G }
            if (matching.size != memoryState.pendingDeletes.size) {
                memoryState = memoryState.copy(pendingDeletes = matching)
                persistLocked()
            }
            matching.take(4)
        }

        for (del in toDelete) {
            if (snap.pairingNow() != G || job.currentGeneration() != jobGen) {
                break
            }
            val res = deletePushRegistration(client, del.url)
            synchronized(lock) {
                if (snap.pairingNow() != G || job.currentGeneration() != jobGen) {
                    return
                }
                val currentPending = memoryState.pendingDeletes
                val updated = when (res) {
                    DeletePushResult.Deleted,
                    DeletePushResult.NoPush,
                    DeletePushResult.NotLinked -> currentPending.filterNot { it.url == del.url }
                    is DeletePushResult.Failed -> {
                        currentPending.mapNotNull { item ->
                            if (item.url == del.url) {
                                val nextFails = item.failures + 1
                                if (nextFails >= 3) null else item.copy(failures = nextFails)
                            } else {
                                item
                            }
                        }
                    }
                }
                memoryState = memoryState.copy(pendingDeletes = updated)
                persistLocked()
            }
        }
    }

    fun onNewEndpoint(url: String, p256dh: String, auth: String) {
        var stateToNotify: PushDeliveryState? = null
        var shouldEnqueue = false
        synchronized(lock) {
            if (!enabled) return
            val G = pairingNow() ?: return
            shouldEnqueue = true

            val cur = memoryState.endpoint
            if (cur != null && cur.url == url && cur.p256dh == p256dh && cur.auth == auth) {
                val att = memoryState.attempt?.copy(answered = true)
                memoryState = memoryState.copy(attempt = att)
            } else if (memoryState.attempt != null && !memoryState.attempt!!.answered) {
                val att = memoryState.attempt!!.copy(answered = true)
                val id = att.identity
                val newEp = PushRegistrationEndpoint(url, p256dh, auth, id, posted = false)
                var pending = memoryState.pendingDeletes
                if (cur != null && cur.posted) {
                    pending = pending + PendingPushDelete(cur.url, cur.identity?.generation ?: G, 0)
                }
                pending = pending.filterNot { it.url == url }
                val newDel = PushDeliveryState.WaitingForDelivery(id.distributorPackage, false)
                updateDeliveryStateLocked(newDel)
                memoryState = memoryState.copy(endpoint = newEp, attempt = att, pendingDeletes = pending)
                stateToNotify = deliveryState
            } else {
                // A distributor temporary fallback is not tracked. An endpoint that arrives with no unanswered attempt is stored under no identity and is not used. One that arrives during an attempt answers it, and the primary's later endpoint is unusable until a register at least T later. Delivery can stop for that long, then recover.
                val newEp = PushRegistrationEndpoint(url, p256dh, auth, null, posted = false)
                var pending = memoryState.pendingDeletes
                if (cur != null && cur.posted) {
                    pending = pending + PendingPushDelete(cur.url, cur.identity?.generation ?: G, 0)
                }
                pending = pending.filterNot { it.url == url }
                memoryState = memoryState.copy(endpoint = newEp, pendingDeletes = pending)
            }
            persistLocked()
        }
        stateToNotify?.let(::notifyListeners)
        if (shouldEnqueue) {
            // enqueueNow's KEEP policy drops a request while a sync is already running. The first registration after an endpoint arrives can wait until the next sync. The callback has already recorded the endpoint.
            enqueue()
        }
    }

    fun onUnregistered() {
        var stateToNotify: PushDeliveryState? = null
        var shouldEnqueue = false
        synchronized(lock) {
            if (!enabled) return
            val G = pairingNow() ?: return
            shouldEnqueue = true

            val cur = memoryState.endpoint
            var pending = memoryState.pendingDeletes
            if (cur != null && cur.posted) {
                pending = pending + PendingPushDelete(cur.url, cur.identity?.generation ?: G, 0)
            }
            val now = clock()
            val lastDist = memoryState.lastRegistered?.distributorPackage
            if (lastDist != null) {
                val nextDel = PushDeliveryState.WaitingForDelivery(lastDist, false)
                updateDeliveryStateLocked(nextDel)
                stateToNotify = deliveryState
            }
            memoryState = memoryState.copy(endpoint = null, unregisteredAt = now, pendingDeletes = pending)
            persistLocked()
        }
        stateToNotify?.let(::notifyListeners)
        if (shouldEnqueue) {
            enqueue()
        }
    }

    fun onRegistrationFailed(reason: String) {
        var stateToNotify: PushDeliveryState? = null
        synchronized(lock) {
            if (!enabled) return
            if (pairingNow() == null) return

            val att = memoryState.attempt?.copy(answered = true, failure = reason)
            val nextDel = PushDeliveryState.Failed(reason)
            updateDeliveryStateLocked(nextDel)
            memoryState = memoryState.copy(attempt = att)
            persistLocked()
            stateToNotify = deliveryState
        }
        stateToNotify?.let(::notifyListeners)
    }

    fun reregister() {
        if (!enabled) return
        try {
            port?.unregister()
        } catch (_: Throwable) {
        }
        var stateToNotify: PushDeliveryState? = null
        synchronized(lock) {
            val cur = memoryState.endpoint
            val G = pairingNow()
            var pending = memoryState.pendingDeletes
            if (cur != null && cur.posted && G != null) {
                pending = pending + PendingPushDelete(cur.url, cur.identity?.generation ?: G, 0)
            }
            val lastDist = memoryState.lastRegistered?.distributorPackage
            if (lastDist != null) {
                val nextDel = PushDeliveryState.WaitingForDelivery(lastDist, false)
                updateDeliveryStateLocked(nextDel)
                stateToNotify = deliveryState
            }
            memoryState = memoryState.copy(endpoint = null, attempt = null, lastRegistered = null, pendingDeletes = pending)
            persistLocked()
        }
        stateToNotify?.let(::notifyListeners)
        enqueue()
    }

    fun onUserPickedDistributor(pkg: String) {
        if (!enabled) return
        var stateToNotify: PushDeliveryState? = null
        synchronized(lock) {
            val nextDel = PushDeliveryState.WaitingForDelivery(pkg, false)
            updateDeliveryStateLocked(nextDel)
            memoryState = memoryState.copy(ownerPick = pkg)
            persistLocked()
            stateToNotify = deliveryState
        }
        stateToNotify?.let(::notifyListeners)
        enqueue()
    }

    fun reset() {
        if (enabled) {
            try {
                port?.unregister()
            } catch (_: Throwable) {
                log("kind=push reason=internal")
            }
        }
        var stateToNotify: PushDeliveryState? = null
        synchronized(lock) {
            memoryState = PushRegistrationState.Empty
            updateDeliveryStateLocked(PushDeliveryState.Off)
            persistLocked()
            stateToNotify = deliveryState
        }
        stateToNotify?.let(::notifyListeners)
    }

    override fun close() {
        job.close()
        listeners.clear()
    }
}
