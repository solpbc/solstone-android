// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.phone

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.model.QueueState
import app.solstone.observer.formfactor.phone.EXTRA_PHONE_ROUTE
import app.solstone.observer.formfactor.phone.PhonePairingEvent
import app.solstone.observer.formfactor.phone.PhonePairingSession
import app.solstone.observer.formfactor.phone.PhonePairingState
import app.solstone.observer.formfactor.phone.PhonePairingStep
import app.solstone.observer.formfactor.phone.PhoneRoute
import app.solstone.observer.formfactor.phone.encodePhoneRoute
import app.solstone.observer.harness.FileSourceWishStore
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.work.syncStores
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhonePairingTaskRuntimeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val activities = CopyOnWriteArrayList<PhoneShellActivity>()
    private var removeSavedFlag = false
    private val stores get() = syncStores(app)
    private val controller get() = obtainObserverContainer().controller
    private val link = "https://go.solstone.app/p#0M0G86WY18004AGA0012P2G008P0M0025PGV5GYMWQV0E60H48SM8NB6EY4DXBDYXZ5FXENY04HMASW9NF6YY"
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, state: Bundle?) {
            if (a is PhoneShellActivity) activities += a
        }
        override fun onActivityPostSaveInstanceState(a: Activity, state: Bundle) {
            if (removeSavedFlag && a is PhoneShellActivity) state.remove("initial_pairing_task")
        }
        override fun onActivityStarted(a: Activity) = Unit
        override fun onActivityResumed(a: Activity) = Unit
        override fun onActivityPaused(a: Activity) = Unit
        override fun onActivityStopped(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
    }

    @Before fun before() {
        resetObserverRuntime()
        resetPersistence(app)
        seedEvidence(app, "task-held", listOf("legacy-import"), "task-cleanup-test", QueueState.SEALED)
        assertTrue(stores.publisher.forget() is GraphMutationResult.Cleared)
        stores.journalConfirmationStore.settle()
        app.registerActivityLifecycleCallbacks(callbacks)
    }
    @After fun after() {
        instrumentation.runOnMainSync { activities.filterNot { it.isDestroyed }.forEach { it.finish() } }
        waitUntil("pairing task activities destroyed") { activities.all { it.isDestroyed } }
        app.unregisterActivityLifecycleCallbacks(callbacks)
        stores.publisher.forget()
        stores.journalConfirmationStore.settle()
        resetObserverRuntime()
    }

    private fun view() = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, PhoneShellActivity::class.java)
    private fun model(a: PhoneShellActivity) = ViewModelProvider(a)[PhonePairingViewModel::class.java]
    private fun entry() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("pairingLinkField").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithTag("pairingLinkField").assertIsDisplayed()
    }
    private fun back() = instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    private fun replacement(old: PhoneShellActivity): PhoneShellActivity {
        compose.waitUntil(15_000) {
            old.isDestroyed && activities.any { it !== old && !it.isDestroyed && it.intent.action == Intent.ACTION_MAIN && it.intent.data == null }
        }
        val next = activities.last { !it.isDestroyed }
        val task = app.getSystemService(ActivityManager::class.java).appTasks.first { it.taskInfo.taskId == next.taskId }
        assertNotEquals(old.taskId, next.taskId)
        assertEquals(Intent.ACTION_MAIN, task.taskInfo.baseIntent.action)
        assertNull(task.taskInfo.baseIntent.data)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("sourceTile-audio").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        return next
    }
    private fun install(confirmed: Boolean): PairingGraphSnapshot.Committed {
        val fp = "sha256:task-client"
        val result = stores.publisher.installOrReplace(
            PairedHome("task-home", "task-home", null, "sha256:task-ca", fp, "phone", null, null, IdentityState.PAIRED),
            ClientCredential("-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
                "-----BEGIN CERTIFICATE-----\ncert\n-----END CERTIFICATE-----\n",
                listOf("-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----\n")), null, false)
        assertTrue(result is GraphMutationResult.Applied)
        if (confirmed) stores.journalConfirmationStore.confirm(fp)
        else stores.journalConfirmationStore.holdToAskAgain(fp)
        return stores.publisher.currentSnapshot() as PairingGraphSnapshot.Committed
    }
    private fun waitingFacts(): Any {
        val db = openSolstonePersistenceDatabase(app)
        return try { requireNotNull(db.segmentDao().segmentById("task-held")) to db.segmentDao().filesBySegmentId("task-held") }
        finally { db.close() }
    }

    @Test fun freshConfirmedClearTaskViewStagesAndCancellationConsumesOnlyItsTask() {
        val graph = install(true)
        ActivityScenario.launch<PhoneShellActivity>(view().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
            lateinit var original: PhoneShellActivity
            scenario.onActivity { original = it; assertEquals(0, model(it).inFlightPairAttempts) }
            entry()
            assertEquals(graph.pairing, (stores.publisher.currentSnapshot() as PairingGraphSnapshot.Committed).pairing)
            val wishes = FileSourceWishStore(app.filesDir.resolve("source-wishes")).read()
            val waiting = waitingFacts()
            back()
            val next = replacement(original)
            assertFalse(phoneAwaitingMarkConfirmation(app))
            assertEquals(graph.home, (stores.publisher.currentSnapshot() as PairingGraphSnapshot.Committed).home)
            assertEquals(wishes, FileSourceWishStore(app.filesDir.resolve("source-wishes")).read())
            assertEquals(waiting, waitingFacts())
            instrumentation.runOnMainSync { next.recreate() }
            compose.waitUntil(15_000) { next.isDestroyed && activities.any { it !== next && !it.isDestroyed } }
            compose.onNodeWithTag("pairingLinkField").assertDoesNotExist()
        }
    }

    @Test fun rootObligationSurvivesConfigurationRecreation() {
        ActivityScenario.launch<PhoneShellActivity>(view()).use { scenario ->
            entry()
            scenario.recreate()
            lateinit var rotated: PhoneShellActivity
            scenario.onActivity { rotated = it }
            entry()
            back()
            replacement(rotated)
        }
    }

    @Test fun olderSavedStateWithoutObligationDoesNotDiscardItsNavigation() {
        ActivityScenario.launch<PhoneShellActivity>(view()).use { scenario ->
            entry()
            removeSavedFlag = true
            scenario.recreate()
            lateinit var rotated: PhoneShellActivity
            scenario.onActivity { rotated = it }
            entry()
            back()
            compose.waitForIdle()
            assertFalse(rotated.isDestroyed)
            compose.onNodeWithTag("pairingLinkField").assertDoesNotExist()
        }
    }

    @Test fun warmViewCancellationKeepsTheExistingDetailAndActivity() {
        // onNewIntent changes the launch intent ActivityScenario uses to match destruction.
        // The application callback and @After own teardown for this warm-intent case.
        val scenario = ActivityScenario.launch(PhoneShellActivity::class.java)
        run {
            lateinit var original: PhoneShellActivity
            scenario.onActivity {
                original = it
                it.startActivity(Intent(app, PhoneShellActivity::class.java).apply {
                    putExtra(EXTRA_PHONE_ROUTE, encodePhoneRoute(PhoneRoute.SourceDetail("audio")))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("sourceDetailVerdict").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            scenario.onActivity { it.startActivity(view().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            entry()
            back()
            compose.onNodeWithTag("sourceDetailVerdict").assertIsDisplayed()
            scenario.onActivity { assertSame(original, it) }
            assertFalse(original.isDestroyed)
            instrumentation.runOnMainSync { original.finish() }
            waitUntil("warm VIEW activity destroyed") { original.isDestroyed }
        }
    }

    @Test fun scannerReturnKeepsProposalAndOnlyFlowClosureCleansTask() {
        ActivityScenario.launch<PhoneShellActivity>(view()).use { scenario ->
            entry()
            lateinit var original: PhoneShellActivity
            scenario.onActivity { original = it; model(it).dispatch(PhonePairingEvent.OpenScan, controller, stores) }
            scenario.onActivity { model(it).dispatch(PhonePairingEvent.CloseScan, controller, stores) }
            entry()
            assertFalse(original.isDestroyed)
            back()
            replacement(original)
        }
    }

    @Test fun failedConfirmationAndUnansweredMarkDoNotCleanTask() = runBlocking<Unit> {
        val graph = install(false)
        ActivityScenario.launch<PhoneShellActivity>(view()).use { scenario ->
            lateinit var original: PhoneShellActivity
            lateinit var vm: PhonePairingViewModel
            scenario.onActivity { original = it; vm = model(it) }
            assertTrue(phoneAwaitingMarkConfirmation(app))
            val failed = vm.runOwnerAction(PhonePairingViewModel.ActionKind.CONFIRM, graph.pairing, controller, stores) { false }
            assertFalse(failed)
            assertFalse(original.isDestroyed)
            assertTrue(phoneAwaitingMarkConfirmation(app))
            scenario.onActivity { vm.dispatch(PhonePairingEvent.Dismiss, controller, stores) }
            compose.waitForIdle()
            assertFalse(original.isDestroyed)
            assertTrue(phoneAwaitingMarkConfirmation(app))
            scenario.onActivity { vm.dispatch(PhonePairingEvent.OpenMark, controller, stores) }
            val confirmed = vm.runOwnerAction(PhonePairingViewModel.ActionKind.CONFIRM, graph.pairing, controller, stores) {
                withContext(Dispatchers.IO) { stores.journalConfirmationStore.confirm(graph.home.clientCertFingerprint) }
                true
            }
            assertTrue(confirmed)
            replacement(original)
            assertFalse(phoneAwaitingMarkConfirmation(app))
        }
    }

    @Test fun backWaitsForEveryOutstandingDialBeforeCleaningFailedAttempt() = runBlocking<Unit> {
        ActivityScenario.launch<PhoneShellActivity>(view()).use { scenario ->
            entry()
            lateinit var original: PhoneShellActivity
            lateinit var vm: PhonePairingViewModel
            val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
            scenario.onActivity {
                original = it; vm = model(it)
                vm.applyStep(PhonePairingStep(PhonePairingState(PhonePairingSession.Attempting(link, 7), 7)), controller, stores)
                vm.launchPairAttempt { first.await() }
                vm.launchPairAttempt { second.await() }
                assertEquals(2, vm.inFlightPairAttempts)
            }
            back()
            compose.waitForIdle()
            assertFalse(original.isDestroyed)
            first.complete(Unit)
            compose.waitUntil(10_000) { vm.inFlightPairAttempts == 1 }
            assertFalse(original.isDestroyed)
            second.complete(Unit)
            replacement(original)
        }
    }

    @Test fun lateUnconfirmedCommitKeepsItsQuestionAndHeldData() = runBlocking<Unit> {
        ActivityScenario.launch<PhoneShellActivity>(view()).use { scenario ->
            entry()
            lateinit var original: PhoneShellActivity
            lateinit var vm: PhonePairingViewModel
            val release = CompletableDeferred<Unit>()
            scenario.onActivity {
                original = it; vm = model(it)
                vm.applyStep(PhonePairingStep(PhonePairingState(PhonePairingSession.Attempting(link, 7), 7)), controller, stores)
                vm.launchPairAttempt { release.await(); withContext(Dispatchers.IO) { install(false) } }
            }
            val waiting = waitingFacts()
            back()
            assertFalse(original.isDestroyed)
            release.complete(Unit)
            compose.waitUntil(10_000) { vm.inFlightPairAttempts == 0 && phoneAwaitingMarkConfirmation(app) }
            compose.waitForIdle()
            assertFalse(original.isDestroyed)
            assertEquals(waiting, waitingFacts())
            assertTrue(phoneAwaitingMarkConfirmation(app))
        }
    }
}
