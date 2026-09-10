// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.sources.SourceCondition
import app.solstone.core.model.SilencedFact
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.capturePermission
import app.solstone.platform.fgs.capturePermissionGranted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequiredPermissionsTest {
    @Test
    fun aSourceIsAskedForItsOwnPermissionAndNothingElse() {
        val registry = sourceRegistry(
            registrations = listOf(
                registration("audio", CaptureForegroundType.MICROPHONE),
                registration("location", CaptureForegroundType.LOCATION),
                registration("camera", CaptureForegroundType.CAMERA),
            ),
        )

        assertEquals(listOf("android.permission.CAMERA"), registry.requiredPermissions("camera"))
        assertEquals(listOf("android.permission.RECORD_AUDIO"), registry.requiredPermissions("audio"))
        assertEquals(
            listOf("android.permission.ACCESS_COARSE_LOCATION"),
            registry.requiredPermissions("location"),
        )
    }

    /**
     * The regression this exists for.
     *
     * ⛔ Tapping `grant permissions` on one source's screen used to request **every** declared type,
     * so the owner answered four dialogs from one tap and the result was unattributable. Asserting
     * the *absence* of the siblings is the half that catches a re-bundling.
     */
    @Test
    fun oneSourcesRequestNeverCarriesAnotherSourcesPermission() {
        val registry = sourceRegistry(
            registrations = listOf(
                registration("audio", CaptureForegroundType.MICROPHONE),
                registration("location", CaptureForegroundType.LOCATION),
                registration("camera", CaptureForegroundType.CAMERA),
            ),
        )

        val camera = registry.requiredPermissions("camera")
        assertEquals(1, camera.size, "a request must name exactly one permission: $camera")
        assertFalse(camera.any { it.contains("RECORD_AUDIO") }, camera.toString())
        assertFalse(camera.any { it.contains("LOCATION") }, camera.toString())
        // ⛔ Notifications is not a source permission and must never ride a source's request.
        assertFalse(camera.any { it.contains("POST_NOTIFICATIONS") }, camera.toString())
    }

    @Test
    fun anUnknownOrTypelessSourceAsksForNothingRatherThanEverything() {
        val registry = sourceRegistry(
            registrations = listOf(
                registration("audio", CaptureForegroundType.MICROPHONE),
                SourceRegistration("screen", FakeSourceEngine(conditionValue = running())),
            ),
        )

        assertEquals(emptyList(), registry.requiredPermissions("screen"))
        assertEquals(emptyList(), registry.requiredPermissions("nope"))
        // Positive control: the same registry does return a permission for a source that has one,
        // so the two empties above are measurements rather than a broken lookup.
        assertTrue(registry.requiredPermissions("audio").isNotEmpty())
    }

    /**
     * The forward map and the granted-predicate are two directions of one relation, and a source's
     * request would silently target the wrong permission if they disagreed.
     */
    @Test
    fun theForwardMapAndTheGrantedPredicateAgreeForEveryCaptureType() {
        CaptureForegroundType.entries.forEach { type ->
            val onlyThisOne = grantedPermissions().copy(
                microphoneGranted = type == CaptureForegroundType.MICROPHONE,
                cameraGranted = type == CaptureForegroundType.CAMERA,
                locationGranted = type == CaptureForegroundType.LOCATION,
            )
            assertTrue(capturePermissionGranted(type, onlyThisOne), type.name)
            CaptureForegroundType.entries.filter { it != type }.forEach { other ->
                assertFalse(capturePermissionGranted(other, onlyThisOne), "$type vs $other")
            }
            assertTrue(capturePermission(type).startsWith("android.permission."), type.name)
        }
        // Distinctness: three types, three different permission strings.
        val all = CaptureForegroundType.entries.map(::capturePermission)
        assertEquals(all.size, all.distinct().size, all.toString())
    }

    private fun registration(id: String, type: CaptureForegroundType) = SourceRegistration(
        sourceId = id,
        engine = FakeSourceEngine(conditionValue = running()),
        requiredPermissionsGranted = { capturePermissionGranted(type, it) },
        captureForegroundType = type,
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
