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
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.observer.formfactor.shared.Camera2QrPreviewView
import app.solstone.observer.formfactor.shared.ObserverHarnessUi
import app.solstone.observer.formfactor.shared.QrBackend
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.testing.validDirectPairLink
import app.solstone.observer.harness.BundleExport
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.BackgroundRunner
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.HarnessEvidenceSegment
import app.solstone.observer.harness.HarnessJournalCacheState
import app.solstone.observer.harness.HarnessExportResult
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.harness.HarnessSyncState
import app.solstone.observer.harness.HeartbeatFreshness
import app.solstone.observer.harness.InMemoryDesiredObservingStore
import app.solstone.observer.harness.MainPoster
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
import app.solstone.platform.power.GuidanceAction
import app.solstone.platform.power.GuidanceLaunchResult
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
                    asyncLoad = AsyncLoad(BackgroundRunner { it() }, MainPoster { it() }),
                    previewHeightPx = 1,
                    qrBackend = QrBackend.Camera2,
                    qrThreadLabel = "phone-test",
                    batteryExemptionGranted = { true },
                    batteryGuidance = GuidanceAction("", null, ""),
                    launchBatteryGuidance = { GuidanceLaunchResult.Launched },
                    journalCacheState = { cacheState },
                    saveJournalCacheLimit = { cacheState },
                )
                activity.setContentView(ui.view())
                ui.showPairLink(validDirectPairLink())

                val texts = collectTexts(activity.findViewById(android.R.id.content))
                assertTrue(texts.contains("No network connection") || texts.any { it.contains("didn't answer") })
                assertFalse(texts.contains("Paired"))
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

    private fun failingController(): HarnessController =
        HarnessController(
            permissionStatusReader = PermissionStatusReader {
                PermissionStatus(
                    microphoneGranted = true,
                    cameraGranted = true,
                    fineLocationGranted = true,
                    coarseLocationGranted = true,
                    backgroundLocationGranted = true,
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
            sourceSnapshot = { SourceRuntimeSnapshot(false, false, true, true) },
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
