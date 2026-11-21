// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.solstone.core.i18n.phoneText
import app.solstone.observer.formfactor.phone.PhoneAgeSignalScreen
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.work.SyncDrainGate
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.ApplicableAgeFeaturesRequest
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import com.google.android.play.agesignals.model.SignificantChangeStatus
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The launch age check and the one gate every door of the app asks.
 *
 * ⛔ **Nothing here runs unless the build turns the check on** ([BuildConfig.AGE_SIGNAL_CHECK]).
 * Off, no hook is installed, no activity is held and the store is never asked.
 *
 * On, each launch surface holds at a plain screen until the store has answered for this process.
 * A closing answer is written as one derived fact (closed, never an age) that the send and pairing
 * permit, the capture service, the source workers and the watch courier all read, so work the system
 * starts later in a fresh process stays stopped as well. The next launch asks again.
 */
internal object AgeSignalGate {
    @Volatile private var marker: AgeGateMarker? = null
    @Volatile private var clearedThisProcess = false

    /** Called once from the application, before any component can run. */
    fun install(context: Context) {
        if (!BuildConfig.AGE_SIGNAL_CHECK) return
        marker = PreferencesAgeGateMarker(context)
        SyncDrainGate.closed = ::closed
        ObserverForegroundService.startClosed = ::closed
    }

    fun closed(): Boolean = ageGateClosed(BuildConfig.AGE_SIGNAL_CHECK, marker)

    /**
     * Whether [activity] must hold at the age screen instead of starting. When it holds, the screen
     * is already set and the check is running; once it clears, [onCleared] restarts the activity.
     */
    fun holdsLaunch(activity: ComponentActivity, onCleared: () -> Unit): Boolean {
        if (!BuildConfig.AGE_SIGNAL_CHECK || clearedThisProcess) return false
        LaunchCheck(activity, onCleared).start()
        return true
    }

    private fun apply(outcome: AgeGateOutcome, app: Context) {
        val current = marker ?: return
        if (outcome == AgeGateOutcome.RUN) clearedThisProcess = true
        applyAgeGateOutcome(
            outcome = outcome,
            marker = current,
            workAlreadyRunning = {
                (app as? PhoneApplication)?.runtime?.containerIfInitialized != null ||
                    ObserverForegroundService.heldCaptureForegroundTypes != null ||
                    SyncDrainGate.inUse()
            },
            stopRunningWork = {
                // Capture or a send started in this process before its first answer. The marker is
                // already durable, so ending the process stops all of it, and nothing it left
                // scheduled can start again until a launch clears the check.
                ObserverForegroundService.stop(app)
                Process.killProcess(Process.myPid())
            },
        )
    }

    private class LaunchCheck(
        private val activity: ComponentActivity,
        private val onCleared: () -> Unit,
    ) {
        private var outcome by mutableStateOf<AgeGateOutcome?>(null)
        private var checking = false

        fun start() {
            activity.setContent {
                val shown = outcome
                PhoneAgeSignalScreen(
                    line = shown?.let(::ageSignalLine),
                    action = if (shown == AgeGateOutcome.VERIFY) phoneText("android.agesignal.openplay") else null,
                    onAction = { openPlayListing(activity) },
                )
            }
            activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                private var started = false

                override fun onStart(owner: LifecycleOwner) {
                    // Coming back to a held screen, from the store or a parent's approval, asks again.
                    if (started && outcome?.closesApp == true) check()
                    started = true
                }
            })
            check()
        }

        private fun check() {
            if (checking) return
            checking = true
            val app = activity.applicationContext
            activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ageGateOutcome(askAgeSignalWithRetry(PlayAgeSignalStore(app, activity), Thread::sleep))
                }
                checking = false
                apply(result, app)
                if (result == AgeGateOutcome.RUN) onCleared() else outcome = result
            }
        }
    }
}

private fun ageSignalLine(outcome: AgeGateOutcome): String? = when (outcome) {
    AgeGateOutcome.RUN -> null
    AgeGateOutcome.STOP -> phoneText("android.agesignal.under13")
    AgeGateOutcome.HOLD -> phoneText("android.agesignal.parentapproval")
    AgeGateOutcome.VERIFY -> phoneText("android.agesignal.playverification")
}

/** The app's own Play listing: the store app first, the web listing when there is none. */
private fun openPlayListing(activity: Activity) {
    val id = activity.packageName
    val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).setPackage(PLAY_STORE_PACKAGE)
    try {
        activity.startActivity(store)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")))
        }
    }
}

private const val PLAY_STORE_PACKAGE = "com.android.vending"

/** ⚠ Holds whether the app is closed and nothing else: never an age, a range or an answer. */
private class PreferencesAgeGateMarker(context: Context) : AgeGateMarker {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun closed(): Boolean = preferences.getBoolean(KEY_CLOSED, false)

    override fun write(closed: Boolean): Boolean = preferences.edit().putBoolean(KEY_CLOSED, closed).commit()

    private companion object {
        const val PREFERENCES_NAME = "phone_age_gate"
        const val KEY_CLOSED = "closed"
    }
}

/** Play Age Signals behind [AgeSignalStore]. The band is reduced before this returns. */
private class PlayAgeSignalStore(private val context: Context, private val activity: Activity) : AgeSignalStore {
    private val manager: AgeSignalsManager get() = ageSignalsManagerFor(context)

    override fun mandatorySharingApplies(): Boolean =
        await(manager.getApplicableAgeFeatures(ApplicableAgeFeaturesRequest.builder().build()))
            .features().contains(ApplicableAgeFeature.MANDATORY_SHARING)

    override fun access(): AgeSignalAccess =
        when (await(manager.requestAgeSignalsAccess(AgeSignalsAccessRequest.builder().setActivity(activity).build())).ageSignalsStatus()) {
            AgeSignalsStatus.SHARED -> AgeSignalAccess.SHARED
            AgeSignalsStatus.NOT_SHARED -> AgeSignalAccess.NOT_SHARED
            AgeSignalsStatus.VERIFICATION_REQUIRED -> AgeSignalAccess.VERIFICATION_REQUIRED
            else -> AgeSignalAccess.UNSPECIFIED
        }

    override fun sharedSignal(): AgeSignalAnswer.Shared {
        val signal = await(manager.checkAgeSignals(AgeSignalsRequest.builder().build()))
        val change = signal.significantChangeStatus()
        return AgeSignalAnswer.Shared(
            category = ageCategoryOf(signal.ageLower(), signal.ageUpper()),
            parentApprovalOutstanding = change == SignificantChangeStatus.PENDING || change == SignificantChangeStatus.DECLINED,
        )
    }

    private fun <T> await(task: Task<T>): T = try {
        Tasks.await(task, AGE_SIGNAL_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (failure: ExecutionException) {
        val code = (failure.cause as? AgeSignalsException)?.errorCode
        throw AgeSignalFailure(transient = code == null || ageSignalErrorIsTransient(code))
    } catch (_: TimeoutException) {
        throw AgeSignalFailure(transient = true)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw AgeSignalFailure(transient = false)
    }
}

private const val AGE_SIGNAL_CALL_TIMEOUT_SECONDS = 5L

/** A failure worth asking again. No store, an app the store doesn't own, or an old store isn't. */
internal fun ageSignalErrorIsTransient(code: Int): Boolean = when (code) {
    AgeSignalsErrorCode.API_NOT_AVAILABLE,
    AgeSignalsErrorCode.PLAY_STORE_NOT_FOUND,
    AgeSignalsErrorCode.PLAY_SERVICES_NOT_FOUND,
    AgeSignalsErrorCode.PLAY_STORE_VERSION_OUTDATED,
    AgeSignalsErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
    AgeSignalsErrorCode.APP_NOT_OWNED,
    AgeSignalsErrorCode.SDK_VERSION_OUTDATED,
    AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED,
    -> false
    else -> true
}
