// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.PairedHome
import app.solstone.core.model.SilencedFact
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.observer.formfactor.shared.ObserverHarnessUi
import app.solstone.observer.formfactor.shared.QrBackend
import app.solstone.observer.formfactor.shared.ScanPairReticleView
import app.solstone.observer.harness.BundleExport
import app.solstone.observer.harness.EvidenceReader
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.HarnessEvidenceSegment
import app.solstone.observer.harness.HarnessExportResult
import app.solstone.observer.harness.HarnessJournalCacheState
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
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.platform.camera.still.SingleHolderCameraLock
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.PermissionStatusReader
import java.net.ConnectException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanPairReticleHierarchyTest {

    @Before
    fun setUp() {
        resetObserverRuntime()
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    @Test
    fun operatorModePlacesReticleBetweenPreviewAndCaptionWithBackOnTop() {
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
                val outer = ui.view() as ViewGroup
                activity.setContentView(outer)
                ui.showScanPairQr()

                assertEquals("outer container should have one full-bleed child", 1, outer.childCount)
                val inner = outer.getChildAt(0) as ViewGroup

                assertEquals("operator scanner should contain 4 children", 4, inner.childCount)

                val preview = inner.getChildAt(0)
                assertTrue("child 0 should be preview", preview.javaClass.simpleName.contains("QrPreviewView"))

                val reticle = inner.getChildAt(1)
                assertTrue("child 1 should be ScanPairReticleView", reticle is ScanPairReticleView)

                val caption = inner.getChildAt(2)
                assertTrue("child 2 should be caption TextView", caption is TextView)
                assertEquals("point your phone at the code", (caption as TextView).text.toString())

                val back = inner.getChildAt(3)
                assertTrue("child 3 should be Back Button", back is Button)
                assertEquals("Back", (back as Button).text.toString())

                assertReticleNonInteractive(reticle)
            }
        }
    }

    @Test
    fun ownerTaskModePlacesReticleBetweenPreviewAndCaptionWithoutBackButton() {
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
                ui.dismissTo { }
                val outer = ui.view() as ViewGroup
                activity.setContentView(outer)
                ui.showScanPairQr()

                assertEquals("outer container should have one full-bleed child", 1, outer.childCount)
                val inner = outer.getChildAt(0) as ViewGroup

                assertEquals("owner-task scanner should contain 3 children (no Back)", 3, inner.childCount)

                val preview = inner.getChildAt(0)
                assertTrue("child 0 should be preview", preview.javaClass.simpleName.contains("QrPreviewView"))

                val reticle = inner.getChildAt(1)
                assertTrue("child 1 should be ScanPairReticleView", reticle is ScanPairReticleView)

                val caption = inner.getChildAt(2)
                assertTrue("child 2 should be caption TextView", caption is TextView)
                assertEquals("point your phone at the code", (caption as TextView).text.toString())

                assertReticleNonInteractive(reticle)
            }
        }
    }

    private fun assertReticleNonInteractive(reticle: View) {
        assertFalse("reticle should not be clickable", reticle.isClickable)
        assertFalse("reticle should not be focusable", reticle.isFocusable)
        assertEquals(
            "reticle should be marked IMPORTANT_FOR_ACCESSIBILITY_NO",
            View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            reticle.importantForAccessibility,
        )
        assertNull("reticle should have no contentDescription", reticle.contentDescription)
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
            evidenceReader = object : EvidenceReader {
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
}
