// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceState
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.SourceCondition
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.capturePermissionGranted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wish store records what the owner expressed, and nothing else.
 *
 * ⚠ Every assertion here is about the **persisted bytes and the expressed set**. Piece 2 changes no
 * state word and no actuation; that is asserted too, because a criterion nobody states is a
 * criterion nobody keeps.
 */
class ExpressedWishStoreTest {
    @Test
    fun oneOwnerActWritesOneEntry() {
        val store = InMemorySourceWishStore()
        val registry = registry(store, allDenied())

        registry.setWish("audio", SourceWish.Off)

        val written = (store.read() as WishStoreState.Loaded).wishes
        assertEquals(mapOf("audio" to SourceWish.Off), written, "one act, one entry: $written")
    }

    @Test
    fun turningOneSourceOnDoesNotExpressTheOthers() {
        val store = InMemorySourceWishStore()
        val registry = registry(store, allDenied())

        registry.setWish("camera", SourceWish.On)

        assertTrue(registry.isWishExpressed("camera"))
        assertFalse(registry.isWishExpressed("audio"), "audio was never chosen")
        assertFalse(registry.isWishExpressed("location"), "location was never chosen")
    }

    @Test
    fun aGrantedPermissionWithNoEntryIsBackfilledBecauseThatSourceHasBeenRunning() {
        val store = InMemorySourceWishStore()
        registry(store, onlyCameraGranted())

        val written = (store.read() as WishStoreState.Loaded).wishes
        assertEquals(mapOf("camera" to SourceWish.On), written, "backfill: $written")
    }

    @Test
    fun anUngrantedSourceWithNoEntryStaysABSENT() {
        val store = InMemorySourceWishStore()
        val registry = registry(store, onlyCameraGranted())

        val written = (store.read() as WishStoreState.Loaded).wishes
        // ⛔ The direction that turns the migration into the event it was written to prevent.
        assertFalse("audio" in written, "audio must not be written: $written")
        assertFalse("location" in written, "location must not be written: $written")
        assertFalse(registry.isWishExpressed("audio"))
    }

    @Test
    fun anExplicitOffIsNeverBackfilledToOn() {
        val store = InMemorySourceWishStore(mapOf("camera" to SourceWish.Off))
        val registry = registry(store, onlyCameraGranted())

        val written = (store.read() as WishStoreState.Loaded).wishes
        assertEquals(SourceWish.Off, written["camera"], "a deliberate Off must survive: $written")
        assertTrue(registry.isWishExpressed("camera"))
    }

    @Test
    fun theBackfillIsIdempotentAcrossRunsAndAfterAnOwnerTurnsASourceOff() {
        val store = InMemorySourceWishStore()
        registry(store, onlyCameraGranted())
        val afterFirst = (store.read() as WishStoreState.Loaded).wishes

        registry(store, onlyCameraGranted())
        assertEquals(afterFirst, (store.read() as WishStoreState.Loaded).wishes, "second run changed it")

        val third = registry(store, onlyCameraGranted())
        third.setWish("camera", SourceWish.Off)
        val afterOff = (store.read() as WishStoreState.Loaded).wishes
        registry(store, onlyCameraGranted())
        assertEquals(afterOff, (store.read() as WishStoreState.Loaded).wishes, "backfilled over an Off")
        assertEquals(SourceWish.Off, afterOff["camera"])
    }

    /**
     * 🔴 The catastrophe direction. A store that exists and will not read tells us nothing about
     * what the owner chose — reading it as *"nothing expressed"* would report the whole installed
     * base as never-set-up and stop capturing, while looking like the feature working.
     */
    @Test
    fun anUnreadableStoreIsNotTreatedAsNothingExpressedAndIsNotBackfilledOver() {
        val store = UnreadableWishStore()
        val registry = registry(store, onlyCameraGranted())

        listOf("audio", "location", "camera").forEach {
            assertTrue(registry.isWishExpressed(it), "$it must not read as unexpressed")
        }
        assertTrue(store.writes.isEmpty(), "⛔ never write over a store we could not read: ${store.writes}")
    }

    /**
     * ⚠ The permission is **denied** here, and that is the whole fixture. An unexpressed source
     * with a *granted* permission is indistinguishable from a pre-upgrade install that has been
     * running for months, and the backfill claims it — correctly, and asserted two tests up. This
     * one is the other half: nothing expressed, nothing granted, so nothing to claim.
     */
    @Test
    fun anUnexpressedSourceIsReadyToSetUpAndIsNotActuated() {
        val store = InMemorySourceWishStore()
        val fakes = listOf("audio", "location", "camera").associateWith { FakeSourceEngine(conditionValue = running()) }
        val types = mapOf(
            "audio" to CaptureForegroundType.MICROPHONE,
            "location" to CaptureForegroundType.LOCATION,
            "camera" to CaptureForegroundType.CAMERA,
        )
        val f = fixture(permissionStatus = allDenied(), snapshot = snapshot())
        f.desiredStore.setDesiredOn(true)
        val registry = SourceRegistry(
            f.controller,
            fakes.map { (id, engine) ->
                SourceRegistration(
                    sourceId = id,
                    engine = engine,
                    requiredPermissionsGranted = { capturePermissionGranted(types.getValue(id), it) },
                    captureForegroundType = types.getValue(id),
                )
            },
            MainPoster { it() },
            store,
        )

        // ⚠ This asserted the opposite while the store change landed on its own — a source with no
        // entry still resolved on and still actuated, which is what made that step
        // behaviour-preserving. Reading meaning into absence is what inverts it.
        registry.snapshot().sources.forEach { row ->
            assertFalse(row.wishExpressed, row.sourceId)
            assertEquals(SourceWish.Off, row.wish, row.sourceId)
            // 🔴 Not NEEDS_ATTENTION, and the reducer order is what decides it: a missing
            // permission on a source the owner never asked for is not a fault. Founder, 2026-09-10:
            // "a source whose permission the owner declined stays ready to set up".
            assertEquals(SourceState.READY_TO_SET_UP, row.state, row.sourceId)
            assertEquals(ReasonCode.NONE, row.reason, row.sourceId)
        }
        registry.engines.forEach { it.start(EmissionSink { }) }
        // ⚠ `registry.engines` are the BOUND wrappers, not the fakes — asking a wrapper for
        // `startCalls` would cast to null and assert 0 against nothing. Hold the fakes.
        fakes.forEach { (id, fake) ->
            assertEquals(0, fake.startCalls, "⛔ never a label over a source that is quietly on: $id")
        }

        // ✅ Positive control, in the test rather than beside it: the same instrument that just read
        // three zeros has to read a one when the owner actually asks. Without this, a fixture that
        // never wires the engines at all passes the block above.
        registry.setWish("audio", SourceWish.On)
        assertEquals(1, fakes.getValue("audio").startCalls, "an expressed wish must actuate")
        assertEquals(0, fakes.getValue("camera").startCalls, "and only that one")
        assertEquals(0, fakes.getValue("location").startCalls, "and only that one")
        val audio = registry.snapshot().sources.single { it.sourceId == "audio" }
        assertTrue(audio.wishExpressed)
        // ⚠ Denied permission on a source the owner DID ask for is the fault it always was.
        assertEquals(SourceState.NEEDS_ATTENTION, audio.state)
        assertEquals(ReasonCode.PERMISSION_REVOKED, audio.reason)
    }

    /**
     * 🔴 **A permission granted OUTSIDE the app, in system Settings.**
     *
     * § 5.1 and the founder's ruling both make an affirmative grant an expression, and this is the
     * grant no in-app callback ever sees. The backfill used to run once, at construction, so this
     * owner's source read `ready to set up` forever with the permission sitting granted — the state
     * word saying *"you haven't asked for this"* to someone who just did.
     *
     * ⚠ It also fixes an instrumented failure with the same root cause and a different trigger: a
     * test process whose permissions are granted by a rule that runs AFTER the application object
     * is built. Same defect, and the device gate found it before an owner did.
     */
    @Test
    fun aPermissionGrantedAfterConstructionIsExpressedAndActuatedOnTheNextRefresh() {
        val store = InMemorySourceWishStore()
        val fakes = listOf("audio", "location", "camera").associateWith { FakeSourceEngine(conditionValue = running()) }
        val types = mapOf(
            "audio" to CaptureForegroundType.MICROPHONE,
            "location" to CaptureForegroundType.LOCATION,
            "camera" to CaptureForegroundType.CAMERA,
        )
        val f = fixture(permissionStatus = allDenied(), snapshot = snapshot())
        f.desiredStore.setDesiredOn(true)
        val registry = SourceRegistry(
            f.controller,
            fakes.map { (id, engine) ->
                SourceRegistration(id, engine, { capturePermissionGranted(types.getValue(id), it) }, types.getValue(id))
            },
            MainPoster { it() },
            store,
        )
        // The pipeline has handed each source its sink; nothing is wished on, so nothing started.
        registry.engines.forEach { it.start(EmissionSink { }) }
        assertFalse(registry.isWishExpressed("camera"))
        assertEquals(0, fakes.getValue("camera").startCalls)

        // The owner goes to Settings and allows the camera, then comes back to the app. No
        // in-app callback fires for the grant itself; the activity's `onResume` is the event.
        f.permissions.status = onlyCameraGranted()
        registry.onPermissionStatus(f.controller.refreshPermissions())

        assertTrue(registry.isWishExpressed("camera"), "an affirmative grant is an expression")
        assertEquals(SourceWish.On, registry.snapshot().sources.single { it.sourceId == "camera" }.wish)
        // ⛔ Marking it on without starting it is the halves-apart failure the whole rule exists to
        // prevent: the label would say on and nothing would be taken in.
        assertEquals(1, fakes.getValue("camera").startCalls, "expressed AND running")
        assertEquals(mapOf("camera" to SourceWish.On), (store.read() as WishStoreState.Loaded).wishes)

        // ⛔ And only that one. The two the owner did not touch are untouched.
        assertFalse(registry.isWishExpressed("audio"))
        assertFalse(registry.isWishExpressed("location"))
        assertEquals(0, fakes.getValue("audio").startCalls)
        assertEquals(0, fakes.getValue("location").startCalls)

        // ✅ Idempotent: a second resume over the same state writes nothing and starts nothing new.
        registry.onPermissionStatus(f.controller.refreshPermissions())
        assertEquals(1, fakes.getValue("camera").startCalls)
        assertEquals(mapOf("camera" to SourceWish.On), (store.read() as WishStoreState.Loaded).wishes)
    }

    @Test
    fun aRefreshNeverWritesOverAnExplicitOffOrAnUnreadableStore() {
        val chose = InMemorySourceWishStore(mapOf("camera" to SourceWish.Off))
        val f = fixture(permissionStatus = allDenied(), snapshot = snapshot())
        f.desiredStore.setDesiredOn(true)
        val registry = registryOn(f, chose)
        f.permissions.status = onlyCameraGranted()
        registry.onPermissionStatus(f.controller.refreshPermissions())
        assertEquals(SourceWish.Off, (chose.read() as WishStoreState.Loaded).wishes["camera"], "a deliberate Off survives")
        assertEquals(SourceWish.Off, registry.snapshot().sources.single { it.sourceId == "camera" }.wish)

        val unreadable = UnreadableWishStore()
        val g = fixture(permissionStatus = allDenied(), snapshot = snapshot())
        g.desiredStore.setDesiredOn(true)
        val unreadableRegistry = registryOn(g, unreadable)
        g.permissions.status = onlyCameraGranted()
        unreadableRegistry.onPermissionStatus(g.controller.refreshPermissions())
        assertTrue(unreadable.writes.isEmpty(), "⛔ never write over a store we could not read: ${unreadable.writes}")
    }

    private fun registryOn(f: Fixture, store: SourceWishStore) = SourceRegistry(
        f.controller,
        listOf(
            reg("audio", CaptureForegroundType.MICROPHONE),
            reg("location", CaptureForegroundType.LOCATION),
            reg("camera", CaptureForegroundType.CAMERA),
        ),
        MainPoster { it() },
        store,
    )

    private class UnreadableWishStore : SourceWishStore {
        val writes = mutableListOf<Map<String, SourceWish>>()
        override fun read(): WishStoreState = WishStoreState.Unreadable
        override fun saveAll(wishes: Map<String, SourceWish>) {
            writes += wishes
        }
    }

    private fun registry(store: SourceWishStore, status: app.solstone.platform.fgs.PermissionStatus): SourceRegistry {
        val f = fixture(permissionStatus = status, snapshot = snapshot())
        f.desiredStore.setDesiredOn(true)
        return SourceRegistry(
            f.controller,
            listOf(
                reg("audio", CaptureForegroundType.MICROPHONE),
                reg("location", CaptureForegroundType.LOCATION),
                reg("camera", CaptureForegroundType.CAMERA),
            ),
            MainPoster { it() },
            store,
        )
    }

    private fun reg(id: String, type: CaptureForegroundType) = SourceRegistration(
        sourceId = id,
        engine = FakeSourceEngine(conditionValue = running()),
        requiredPermissionsGranted = { capturePermissionGranted(type, it) },
        captureForegroundType = type,
    )

    private fun allDenied() = grantedPermissions().copy(
        microphoneGranted = false,
        cameraGranted = false,
        locationGranted = false,
    )

    private fun onlyCameraGranted() = grantedPermissions().copy(
        microphoneGranted = false,
        cameraGranted = true,
        locationGranted = false,
    )

    private fun snapshot() = SourceRuntimeSnapshot(
        engineRunning = true,
        providerEmitting = true,
        storageOk = true,
        silenced = SilencedFact.NOT_SILENCED,
        engineStartIssued = true,
    )

    private fun running() = SourceCondition(
        desiredOn = true,
        running = true,
        available = true,
        needsAttention = false,
        paused = false,
        silenced = SilencedFact.NOT_SILENCED,
    )
}
