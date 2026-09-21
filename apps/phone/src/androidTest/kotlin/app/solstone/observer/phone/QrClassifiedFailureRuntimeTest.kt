// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.PairedHome
import app.solstone.core.model.SilencedFact
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.observer.formfactor.shared.Camera2QrPreviewView
import app.solstone.observer.formfactor.shared.ObserverHarnessUi
import app.solstone.observer.formfactor.shared.QrBackend
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.testing.validDirectPairLink
import app.solstone.observer.harness.BundleExport
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.HarnessEvidenceSegment
import app.solstone.observer.harness.HarnessJournalCacheState
import app.solstone.observer.harness.HarnessExportResult
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.harness.HarnessSyncState
import app.solstone.observer.harness.HeartbeatFreshness
import app.solstone.observer.harness.InMemoryDesiredObservingStore
import app.solstone.observer.harness.ObserverLifecycle
import app.solstone.observer.harness.PairProbe
import app.solstone.observer.harness.PlStatusProbe
import app.solstone.observer.harness.RelayPairProbe
import app.solstone.observer.harness.SourceRuntimeSnapshot
import app.solstone.observer.harness.SyncEnqueue
import app.solstone.observer.harness.VisibleCaptureOwnerRegistry
import app.solstone.platform.camera.still.SingleHolderCameraLock
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.PermissionStatusReader
import java.net.ConnectException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrClassifiedFailureRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    /**
     * AC4 red proof: QR views used to let classified scan failures escape or render an optimistic "Paired" state.
     */
    @Test
    fun failingClassifiedScanRendersNonPairedStatusWithoutCrashing() {
        val latch = CountDownLatch(1)
        var lastStatus = ""
        ActivityScenario.launch(ObserverActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = Camera2QrPreviewView(activity, failingController(), "phone-test") { message ->
                    lastStatus = message
                    latch.countDown()
                }
                activity.setContentView(view)
                view.submitDecodedTextForTest(validDirectPairLink())
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertFalse(lastStatus.contains("Paired"))
        }
    }

    @Test
    fun failingPairLinkHandoffRendersClassifiedStatusWithoutCrashing() {
        ActivityScenario.launch(ObserverActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cacheState = HarnessJournalCacheState(0, null, emptyList(), null, null)
                val ui = ObserverHarnessUi(
                    context = activity,
                    controller = failingController(),
                    permissionRequester = {},
                    asyncLoad = waitForObserverContainer().asyncLoad,
                    previewHeightPx = 1,
                    qrBackend = QrBackend.Camera2,
                    qrThreadLabel = "phone-test",
                    journalCacheState = { cacheState },
                    saveJournalCacheLimit = { cacheState },
                )
                activity.setContentView(ui.view())
                ui.showPairLink(validDirectPairLink())
            }

            waitUntil("classified pair-link failure") {
                var rendered = false
                scenario.onActivity { activity ->
                    val texts = collectTexts(activity.findViewById(android.R.id.content))
                    rendered = texts.any { it.startsWith("this device isn't on a network") } ||
                        texts.any { it.contains("couldn't reach your journal") }
                }
                rendered
            }
            scenario.onActivity { activity ->
                val texts = collectTexts(activity.findViewById(android.R.id.content))
                assertTrue(
                    texts.any { it.startsWith("this device isn't on a network") } ||
                        texts.any { it.contains("couldn't reach your journal") },
                )
                // ⛔ Exact, on the success word. The retired capitalised `Paired` no longer exists,
                // so asserting THAT would pass against a screen reading `paired`; and a substring
                // match would trip over `not paired`.
                assertFalse(texts.contains("paired"))
                assertFalse(texts.contains("Paired"))
                assertTrue(texts.contains("try again"))
            }
        }
    }

    private fun collectTexts(root: View): List<String> {
        val texts = mutableListOf<String>()
        fun visit(view: View) {
            if (view is TextView) texts += view.text.toString()
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        return texts
    }

    /**
     * 🔴 **A screen change must DETACH the camera preview, because detaching is what releases the
     * camera.** `Camera2QrPreviewView` closes the device from `onSurfaceTextureDestroyed`, which
     * fires only when the `TextureView` leaves the hierarchy.
     *
     * ⚠ **The defect this guards was invisible.** `showPaired()` used to clear a nested column
     * while the preview sat beside that column as its sibling, so a successful pair left the owner
     * on a confirmation screen with a live camera behind it and the system camera indicator lit.
     * ⛔ It looked correct in a screenshot and would have passed any assertion about what is
     * *visible* — the observable has to be the view being gone, not the view being hidden.
     *
     * ⚠ **Scope, stated honestly:** this pins that `setScreen` detaches the preview, which is the
     * property `showPaired()` now relies on by going through it. It does not drive a successful
     * pair end to end — no fixture mints one — so a future change that routes the paired state
     * around `setScreen` again would not be caught here. The 🔴 on `showPaired()` is the other half.
     */
    @Test
    fun leavingTheScannerScreenDetachesThePreviewSoTheCameraCanBeReleased() {
        ActivityScenario.launch(ObserverActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cacheState = HarnessJournalCacheState(0, null, emptyList(), null, null)
                val ui = ObserverHarnessUi(
                    context = activity,
                    controller = failingController(),
                    permissionRequester = {},
                    asyncLoad = waitForObserverContainer().asyncLoad,
                    previewHeightPx = 1,
                    qrBackend = QrBackend.Camera2,
                    qrThreadLabel = "phone-test",
                    journalCacheState = { cacheState },
                    saveJournalCacheLimit = { cacheState },
                )
                activity.setContentView(ui.view())
                ui.showScanPairQr()
                assertTrue(
                    "the scanner screen should hold a camera preview",
                    hasPreview(activity.findViewById(android.R.id.content)),
                )
                // Any screen replacement; `showPaired()` takes this same path now.
                ui.showCameraOff()
                assertFalse(
                    "the preview survived a screen change, so the camera is never released",
                    hasPreview(activity.findViewById(android.R.id.content)),
                )
            }
        }
    }

    private fun hasPreview(view: View): Boolean = when {
        view is Camera2QrPreviewView -> true
        view is ViewGroup -> (0 until view.childCount).any { hasPreview(view.getChildAt(it)) }
        else -> false
    }

    private fun failingController(): HarnessController =
        HarnessController(
            permissionStatusReader = PermissionStatusReader {
                PermissionStatus(
                    microphoneGranted = true,
                    cameraGranted = true,
                    locationGranted = true,
                    notificationsGranted = true,
                )
            },
            desiredObservingStore = InMemoryDesiredObservingStore(),
            cameraLock = SingleHolderCameraLock(),
            observerLifecycle = object : ObserverLifecycle {
                override fun start() = Unit
                override fun stop() = Unit
            },
            heartbeatFreshness = HeartbeatFreshness { true },
            pairProbe = PairProbe { _, _ -> throw ConnectException("refused") },
            relayPairProbe = RelayPairProbe { _, _ -> error("unexpected relay probe") },
            plStatusProbe = PlStatusProbe { HarnessPlStatus.NotPaired },
            syncEnqueue = object : SyncEnqueue {
                override fun enqueuePeriodic() = Unit
                override fun enqueueNow() = Unit
            },
            evidenceReader = object : app.solstone.observer.harness.EvidenceReader {
                override fun listEvidence(): List<HarnessEvidenceSegment> = emptyList()
                override fun pendingCount(): Int = 0
                override fun syncState(): HarnessSyncState = HarnessSyncState(0, null, null)
            },
            bundleExport = BundleExport { HarnessExportResult("", "", 0) },
            endpointStore = MemoryEndpointStore(),
            credentialStore = MemoryCredentialStore(),
            identityStore = MemoryIdentityStore(),
            sourceSnapshot = { SourceRuntimeSnapshot(false, false, true, SilencedFact.UNKNOWN) },
            deviceLabel = "phone-test",
            visibleCaptureAuthority = VisibleCaptureOwnerRegistry(),
            isUsableNetworkPresent = { true },
        )
}

private class MemoryEndpointStore : EndpointStore {
    override fun save(endpoint: DirectEndpoint) = Unit
    override fun load(): DirectEndpoint? = null
    override fun clear() = Unit
}

private class MemoryCredentialStore : ClientCredentialStore {
    override fun save(credential: ClientCredential) = Unit
    override fun load(): ClientCredential? = null
    override fun clear() = Unit
}

private class MemoryIdentityStore : IdentityStore {
    override fun save(home: PairedHome) = Unit
    override fun load(): PairedHome? = null
    override fun clear() = Unit
}
