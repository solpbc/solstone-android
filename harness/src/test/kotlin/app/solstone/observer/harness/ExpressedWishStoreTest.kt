// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.SilencedFact
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

    @Test
    fun pieceTwoChangesNoStateWordAndNoActuation() {
        val engine = FakeSourceEngine(conditionValue = running())
        val store = InMemorySourceWishStore()
        val f = fixture(permissionStatus = grantedPermissions(), snapshot = snapshot())
        f.desiredStore.setDesiredOn(true)
        val registry = SourceRegistry(
            f.controller,
            listOf(
                SourceRegistration("audio", engine, { it.microphoneGranted }, CaptureForegroundType.MICROPHONE),
            ),
            MainPoster { it() },
            store,
        )

        val row = registry.snapshot().sources.single()
        assertEquals(SourceWish.On, row.wish, "a source with no entry still resolves on")
        registry.engines.single().start(app.solstone.core.sources.EmissionSink { })
        assertEquals(1, engine.startCalls, "and still actuates")
    }

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
