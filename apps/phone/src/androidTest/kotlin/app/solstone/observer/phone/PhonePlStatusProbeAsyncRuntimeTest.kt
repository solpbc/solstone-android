// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import app.solstone.observer.formfactor.shared.ObserverHarnessUi
import app.solstone.observer.formfactor.shared.QrBackend
import app.solstone.observer.scaffold.ObserverActivity
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
import app.solstone.observer.harness.LoadState
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhonePlStatusProbeAsyncRuntimeTest {
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

    @Test
    fun plStatusProbeRunsOffMainDedupsAndSurfacesFailure() {
        val gate = CountDownLatch(1)
        val detachedGate = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val ranOffMain = AtomicBoolean(false)
        val cacheState = HarnessJournalCacheState(0, null, emptyList(), null, null)
        val controller = HarnessController(
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
            plStatusProbe = PlStatusProbe {
                val call = calls.incrementAndGet()
                ranOffMain.set(Looper.myLooper() != Looper.getMainLooper())
                when (call) {
                    1 -> {
                        gate.await()
                        HarnessPlStatus.Reachable(200)
                    }
                    2 -> error("injected probe failure")
                    3 -> {
                        detachedGate.await()
                        HarnessPlStatus.Reachable(204)
                    }
                    else -> error("unexpected probe call $call")
                }
            },
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
            endpointStore = ProbeMemoryEndpointStore(),
            credentialStore = ProbeMemoryCredentialStore(),
            identityStore = ProbeMemoryIdentityStore(),
            sourceSnapshot = { SourceRuntimeSnapshot(false, false, true, true) },
            deviceLabel = "phone-test",
            visibleCaptureAuthority = VisibleCaptureOwnerRegistry(),
            isUsableNetworkPresent = { true },
        )

        ActivityScenario.launch(ObserverActivity::class.java).use { scenario ->
            val container = waitForObserverContainer()
            assertTrue(waitForRecovery(container))
            waitUntil("initial local cache pass") { container.journalCacheState().latestPass != null }

            try {
                scenario.onActivity { activity ->
                    val ui = ObserverHarnessUi(
                        context = activity,
                        controller = controller,
                        permissionRequester = {},
                        asyncLoad = container.asyncLoad,
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
                    val root = activity.findViewById<View>(android.R.id.content)
                    clickButton(root, "PL status probe")
                    // Probe awaits a latch. If probe() ran on the main looper, this
                    // onActivity would never return and the test would time out.
                    assertInProgress(collectTexts(root))
                }

                waitUntil("probe entered") { calls.get() >= 1 }
                assertTrue("probe must not run on the main looper", ranOffMain.get())

                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(android.R.id.content)
                    clickButton(root, "Probe")
                    assertInProgress(collectTexts(root))
                }
                assertEquals(1, calls.get())

                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(android.R.id.content)
                    clickButton(root, "Back")
                    clickButton(root, "PL status probe")
                    assertInProgress(collectTexts(root))
                }
                assertEquals(1, calls.get())
            } finally {
                gate.countDown()
            }

            waitUntil("reachable rendered") {
                var rendered = false
                scenario.onActivity { activity ->
                    rendered = collectTexts(activity.findViewById(android.R.id.content))
                        .contains("Reachable (HTTP 200)")
                }
                rendered
            }

            scenario.onActivity { activity ->
                clickButton(activity.findViewById(android.R.id.content), "Probe")
            }

            waitUntil("unable-to-check rendered") {
                var rendered = false
                scenario.onActivity { activity ->
                    rendered = collectTexts(activity.findViewById(android.R.id.content))
                        .contains("couldn't check your journal")
                }
                rendered
            }
            scenario.onActivity { activity ->
                val texts = collectTexts(activity.findViewById(android.R.id.content))
                assertTrue(texts.contains("couldn't check your journal"))
                assertFalse(texts.any { it.contains("Reachable") })
            }

            lateinit var detachedStatus: TextView
            try {
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(android.R.id.content)
                    clickButton(root, "Probe")
                    detachedStatus = requireNotNull(findTextView(root, "checking your journal…"))
                }
                waitUntil("detached probe entered") { calls.get() >= 3 }
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(android.R.id.content)
                    clickButton(root, "Back")
                    clickButton(root, "Permissions")
                    assertTrue(collectTexts(root).contains("Fix battery settings"))
                }
            } finally {
                detachedGate.countDown()
            }

            val backgroundBarrier = CountDownLatch(1)
            container.asyncLoad.load({ Unit }) { state ->
                if (state is LoadState.Loaded) backgroundBarrier.countDown()
            }
            assertTrue(
                "detached probe completion must leave the background executor",
                backgroundBarrier.await(5, TimeUnit.SECONDS),
            )
            scenario.onActivity { activity ->
                val texts = collectTexts(activity.findViewById(android.R.id.content))
                assertTrue(texts.contains("Fix battery settings"))
                assertFalse(texts.contains("Reachable (HTTP 204)"))
                assertFalse(texts.contains("couldn't check your journal"))
                assertFalse(texts.contains("checking your journal…"))
                assertEquals("checking your journal…", detachedStatus.text.toString())
            }
        }
    }

    private fun assertInProgress(texts: List<String>) {
        assertTrue(texts.contains("checking your journal…"))
        assertFalse(texts.any { it.contains("Reachable") })
        assertFalse(texts.contains("couldn't check your journal"))
        assertFalse(texts.contains("Not paired"))
        assertFalse(texts.any { it.startsWith("Paired but unreachable") })
    }

    private fun findTextView(root: View, value: String): TextView? {
        if (root is TextView && root.text.toString() == value) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findTextView(root.getChildAt(i), value)?.let { return it }
            }
        }
        return null
    }

    private fun collectTexts(root: View): List<String> = buildList {
        fun visit(view: View) {
            if (view is TextView) add(view.text.toString())
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
    }

    private fun clickButton(root: View, label: String) {
        fun visit(view: View): Boolean {
            if (view is Button && view.text.toString() == label) return view.performClick().let { true }
            if (view is ViewGroup) for (i in 0 until view.childCount) if (visit(view.getChildAt(i))) return true
            return false
        }
        check(visit(root)) { "button not found: $label" }
    }
}

private class ProbeMemoryEndpointStore : EndpointStore {
    override fun save(endpoint: DirectEndpoint) = Unit
    override fun load(): DirectEndpoint? = null
    override fun clear() = Unit
}

private class ProbeMemoryCredentialStore : ClientCredentialStore {
    override fun save(credential: ClientCredential) = Unit
    override fun load(): ClientCredential? = null
    override fun clear() = Unit
}

private class ProbeMemoryIdentityStore : IdentityStore {
    override fun save(home: PairedHome) = Unit
    override fun load(): PairedHome? = null
    override fun clear() = Unit
}
