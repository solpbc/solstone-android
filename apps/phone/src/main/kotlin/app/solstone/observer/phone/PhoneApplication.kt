// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.concurrent.CopyOnWriteArraySet
import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.formfactor.phone.PhoneWidgetStartOutcome
import app.solstone.observer.formfactor.phone.PhoneObserverWidgetModel
import app.solstone.observer.formfactor.phone.phoneStatusSnapshotOf
import app.solstone.observer.formfactor.phone.renderPhoneObserverWidget
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourceToggleResult
import app.solstone.observer.harness.SourcesReadModel
import app.solstone.observer.scaffold.ForegroundSourceActivation
import app.solstone.observer.scaffold.ObserverAppContainer
import app.solstone.observer.scaffold.ObserverApplication
import app.solstone.observer.scaffold.ObserverRuntimeContainer
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.captureForegroundTypeForSourceId
import app.solstone.platform.fgs.effectiveCapturePlan
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.ObserverForegroundService.ObserverWidgetStartHandler
import app.solstone.platform.fgs.ObserverNotification
import app.solstone.platform.fgs.ObserverNotificationDecorator
import app.solstone.platform.fgs.shouldOfferStartAction
import app.solstone.platform.work.DialDiagnostics
import app.solstone.platform.work.SyncWorker

import app.solstone.observer.formfactor.phone.EXTRA_PHONE_ROUTE
import app.solstone.observer.formfactor.phone.PhoneIntakeNotificationModel
import app.solstone.observer.formfactor.phone.PhoneRoute
import app.solstone.observer.formfactor.phone.derivePhoneIntakeNotification
import app.solstone.observer.formfactor.phone.derivePhoneIntakeNotificationCatching
import app.solstone.observer.formfactor.phone.encodePhoneRoute

import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.installPushRegistration

import android.content.pm.PackageManager
import app.solstone.platform.fgs.AndroidPermissionStatusReader
import app.solstone.observer.harness.FileSourceWishStore
import app.solstone.observer.harness.WishStoreState
import app.solstone.observer.harness.SharedPreferencesDesiredObservingStore

class PhoneApplication : ObserverApplication(
    phoneSpec,
    syncFailureReporter = { message, throwable ->
        PhoneDiagLog.emit(
            DiagEvent.CaughtException(
                site = "opportunistic-sync-${message.replace(' ', '-')}",
                type = throwable.javaClass.simpleName,
            ),
        )
    },
) {
    private lateinit var widgetCoordinator: PhoneWidgetCoordinator
    private lateinit var widgetStartOutcomes: PhoneWidgetStartOutcomeStore
    @Volatile private var cachedWidgetModel = emptyWidgetModel()
    @Volatile private var cachedIntakeModel = derivePhoneIntakeNotification(
        snapshot = null,
        fgsLive = false,
        desiredOn = false,
    )
    @Volatile private var inactiveNotificationRequested = false
    @Volatile private var suppressInactiveReplacement = false
    @Volatile internal var sourceReadOverride: ((ObserverRuntimeContainer?) -> SourcesReadModel?)? = null

    // The in-app status reads through the same background poll the widget does, so an open screen
    // follows the backlog as it drains instead of holding the count it read when it last resumed.
    private val statusListeners = CopyOnWriteArraySet<(HarnessBacklogStatus) -> Unit>()

    internal fun addStatusListener(listener: (HarnessBacklogStatus) -> Unit) {
        statusListeners += listener
    }

    internal fun removeStatusListener(listener: (HarnessBacklogStatus) -> Unit) {
        statusListeners -= listener
    }

    override fun onCreate() {
        PhoneDiagLog.install(applicationContext.filesDir)
        ObserverForegroundService.lifecycleDiag = { PhoneDiagLog.appendRaw(it) }
        SyncWorker.syncDiag = { PhoneDiagLog.appendRaw(it) }
        DialDiagnostics.sink = { PhoneDiagLog.appendRaw(it) }
        installPushRegistration(
            port = UnifiedPushDistributorPort(this),
            enabled = BuildConfig.PUSH_REGISTRATION,
            log = { line -> PhoneDiagLog.appendRaw(line) },
            enqueue = { SyncScheduler.enqueueNow(this, phoneSpec.stream) },
        )
        super.onCreate()
        ObserverForegroundService.captureWishTypes = {
            val store = FileSourceWishStore(filesDir.resolve("source-wishes"))
            when (val read = store.read()) {
                is WishStoreState.Unreadable -> null
                WishStoreState.Absent -> emptySet()
                is WishStoreState.Loaded -> {
                    val wishes = read.wishes
                    val set = linkedSetOf<CaptureForegroundType>()
                    if (wishes["audio"] == SourceWish.On) set.add(CaptureForegroundType.MICROPHONE)
                    if (wishes["location"] == SourceWish.On) set.add(CaptureForegroundType.LOCATION)
                    if (wishes["camera"] == SourceWish.On) set.add(CaptureForegroundType.CAMERA)
                    set
                }
            }
        }
        widgetStartOutcomes = PhoneWidgetStartOutcomeStore(applicationContext)
        widgetCoordinator = PhoneWidgetCoordinator(applicationContext)
        runtime.onContainerInitialized(::onContainerInitialized)
        ObserverNotification.decorator = ObserverNotificationDecorator(::decorateObserverNotification)
        ObserverNotification.startAction = startCaptureAction(applicationContext, isRunning = false, hasEnabledSources = false)
        val stopPendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            STOP_CAPTURE_REQUEST_CODE,
            Intent(applicationContext, PhoneObserverStopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        ObserverNotification.stopAction = Notification.Action.Builder(0, ObserverNotification.TEXT_STOP, stopPendingIntent).build()
        ObserverForegroundService.onForegroundChanged = { live ->
            if (!live && suppressInactiveReplacement) {
                suppressInactiveReplacement = false
                inactiveNotificationRequested = false
            } else {
                inactiveNotificationRequested = !live
            }
            refreshWidgetAndUpdate()
        }
        ObserverForegroundService.intakeStartHandler = {
            runtime.containerIfInitialized?.controller?.startWhenAlreadyForeground()
        }
        ObserverForegroundService.widgetStartHandler = object : ObserverWidgetStartHandler {
            override fun onForegroundServiceStarted(sourceId: String) {
                // ⚠ Turning a source on from the home-screen widget is the owner asking, exactly
                // like the in-app toggle — so it clears the stop, or resume would refuse to bring
                // the service back and the widget would look broken. ⛔ And it is the same event
                // for the event log: a toggle that only the shell records leaves the widget's own
                // switches invisible to an owner reading back what happened.
                PhoneDiagLog.appendRaw("kind=source id=$sourceId wish=on from=widget")
                OwnerStoppedStore(this@PhoneApplication).clear()
                when (val activation = runtime.container().activateSourceWhenAlreadyForeground(sourceId)) {
                    is ForegroundSourceActivation.Actuated -> if (activation.result == SourceToggleResult.Applied) {
                        widgetStartOutcomes.clear()
                    }
                    is ForegroundSourceActivation.StartRefused -> {
                        runtime.containerIfInitialized?.controller?.recordStartRefusal()
                        widgetStartOutcomes.recordRefusal(activation.reason)
                    }
                }
                refreshWidgetAndUpdate()
            }

            override fun onForegroundServiceStartRefused(sourceId: String, reason: ReasonCode) {
                // A PendingIntent denial before service entry cannot reach this handler to retain its reason.
                runtime.containerIfInitialized?.controller?.recordStartRefusal()
                widgetStartOutcomes.recordRefusal(reason)
                refreshWidgetAndUpdate()
            }
        }
    }

    internal fun widgetModel(): PhoneObserverWidgetModel =
        if (runtime.containerIfInitialized == null) emptyWidgetModel() else cachedWidgetModel

    internal fun intakeModel(): PhoneIntakeNotificationModel = cachedIntakeModel

    internal fun turnAudioOffFromWidget() {
        PhoneDiagLog.appendRaw("kind=source id=$PHONE_WIDGET_AUDIO_SOURCE_ID wish=off from=widget")
        val container = runtime.containerIfInitialized
        if (container != null) {
            container.sources.setWish(PHONE_WIDGET_AUDIO_SOURCE_ID, SourceWish.Off)
            applyEffectiveCapture(ownerTurnedSourceOff = true)
            refreshWidgetAndUpdate()
            return
        }

        val store = FileSourceWishStore(filesDir.resolve("source-wishes"))
        val permissions = AndroidPermissionStatusReader(this).read()
        val declared = ObserverForegroundService.declaredCaptureForegroundTypes ?: emptySet()
        val serviceHeld = ObserverForegroundService.heldCaptureForegroundTypes != null

        val plan = planColdAudioOff(
            store = store.read(),
            microphoneGranted = permissions.microphoneGranted,
            cameraGranted = permissions.cameraGranted,
            locationGranted = permissions.locationGranted,
            declared = declared,
            serviceHeld = serviceHeld,
        )

        if (plan.wishesToWrite != null) {
            store.saveAll(plan.wishesToWrite)
        }
        if (plan.recordOwnerStopped) {
            OwnerStoppedStore(this).recordStopped()
        }
        if (plan.commitDesiredOff) {
            SharedPreferencesDesiredObservingStore(this).commitDesiredOff()
        }
        if (plan.stopService) {
            if (serviceHeld) {
                suppressInactiveReplacement = true
            }
            inactiveNotificationRequested = false
            ObserverForegroundService.stop(this)
        } else if (plan.refreshRunningMask) {
            ObserverForegroundService.refreshRunningCaptureTypes(this)
        }
        refreshWidgetAndUpdate()
    }

    internal fun applyEffectiveCapture(ownerTurnedSourceOff: Boolean) {
        val container = runtime.containerIfInitialized ?: return
        val permissions = container.controller.permissionStatus
        val declared = ObserverForegroundService.declaredCaptureForegroundTypes ?: emptySet()
        val wishedTypes = container.sources.snapshot().sources
            .filter { it.wish == SourceWish.On }
            .mapNotNull { captureForegroundTypeForSourceId(it.sourceId) }
            .toSet()
        val plan = effectiveCapturePlan(
            microphoneGranted = permissions.microphoneGranted,
            cameraGranted = permissions.cameraGranted,
            locationGranted = permissions.locationGranted,
            declared = declared,
            wishedOn = wishedTypes,
            liveHeld = ObserverForegroundService.heldCaptureForegroundTypes,
        )

        if (plan.endSession) {
            if (ownerTurnedSourceOff) {
                OwnerStoppedStore(this).recordStopped()
            }
            SharedPreferencesDesiredObservingStore(this).commitDesiredOff()
            if (ObserverForegroundService.heldCaptureForegroundTypes != null) {
                suppressInactiveReplacement = true
            }
            inactiveNotificationRequested = false
            container.controller.stop()
        } else {
            val liveHeld = ObserverForegroundService.heldCaptureForegroundTypes
            if (liveHeld != null && liveHeld != plan.types) {
                ObserverForegroundService.refreshRunningCaptureTypes(this)
            }
            container.sources.stopEnginesOutside(plan.types)
        }
    }

    private fun handlePhoneResume(container: ObserverAppContainer) {
        val permissions = container.controller.refreshPermissions()
        val requestStore = CapturePermissionRequestStore(this)
        val awaiting = requestStore.readAwaiting()
        var grantCalledEnsureObserving = false
        if (awaiting != null) {
            val required = container.sources.requiredPermissions(awaiting.sourceId)
            val granted = required.isNotEmpty() && required.all {
                checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) {
                container.sources.setWish(awaiting.sourceId, SourceWish.On)
                OwnerStoppedStore(this).clear()
                container.controller.ensureObserving()
                if (ObserverForegroundService.heldCaptureForegroundTypes != null) {
                    ObserverForegroundService.refreshRunningCaptureTypes(this)
                }
                grantCalledEnsureObserving = true
                requestStore.clearAwaiting()
            } else {
                when (denialWishWrite(awaiting.priorWish)) {
                    DenialWishWrite.RemoveEntry -> container.sources.clearExpressedWish(awaiting.sourceId)
                    DenialWishWrite.KeepOff -> container.sources.setWish(awaiting.sourceId, SourceWish.Off)
                    DenialWishWrite.KeepOn -> Unit
                }
                requestStore.clearAwaiting()
            }
        }
        if (!grantCalledEnsureObserving) {
            applyEffectiveCapture(ownerTurnedSourceOff = false)
        }
    }

    internal fun stopObserverFromNotification() {
        // ⚠ Recorded BEFORE the stop, so a process death between the two cannot lose the owner's
        // decision and leave resume free to restart intake.
        OwnerStoppedStore(this).recordStopped()
        runtime.containerIfInitialized?.controller?.stop()
    }

    private fun onContainerInitialized(container: ObserverRuntimeContainer) {
        inactiveNotificationRequested = false
        // ⚠ Installed here rather than passed in, because the container is shared with surfaces that
        // have no stop control. Without it a stopped source reads `setting up / getting ready…`
        // while nothing is getting ready.
        (container as? ObserverAppContainer)?.let { app ->
            val stopped = OwnerStoppedStore(this)
            app.ownerStoppedProvider = { stopped.ownerStopped() }
            app.phoneResumeHandler = { handlePhoneResume(app) }
        }
        container.controller.onEffectiveCaptureEmpty = {
            applyEffectiveCapture(ownerTurnedSourceOff = false)
        }
        if (widgetStartOutcomes.read() is PhoneWidgetStartOutcome.Refused) {
            container.controller.recordStartRefusal()
        }
        container.setBackgroundStatusRefreshListener {
            refreshWidgetModel(container)
            widgetCoordinator.updateAll()
        }
        container.sources.subscribe {
            refreshWidgetAndUpdate()
        }
        refreshWidgetAndUpdate()
    }

    private fun refreshWidgetAndUpdate() {
        widgetCoordinator.refreshAndUpdateAll { refreshWidgetModel() }
    }

    @Synchronized
    internal fun refreshWidgetModel(
        container: ObserverRuntimeContainer? = runtime.containerIfInitialized,
        readSources: () -> SourcesReadModel? = {
            val override = sourceReadOverride
            if (override != null) override(container) else container?.sources?.snapshot()
        },
    ) {
        val desiredOn = container?.controller?.desiredOn ?: false
        val fgsLive = ObserverForegroundService.heldCaptureForegroundTypes != null
        var readModel: SourcesReadModel? = null
        cachedIntakeModel = derivePhoneIntakeNotificationCatching(
            supplier = {
                readSources().also { readModel = it }
            },
            fgsLive = fgsLive,
            desiredOn = desiredOn,
        )
        val hasEnabledSources = readModel?.sources?.any { it.wish == SourceWish.On } == true
        if (readModel?.sources?.any { it.sourceId == PHONE_WIDGET_AUDIO_SOURCE_ID && it.state == SourceState.ON } == true) {
            widgetStartOutcomes.clear()
        }
        ObserverNotification.startAction = startCaptureAction(applicationContext, fgsLive, hasEnabledSources)
        // Publish the prepared result even when the source read failed. Neither a
        // second source read nor the journal status read may suppress this update.
        if (fgsLive) {
            ObserverForegroundService.refreshOngoingNotification(applicationContext, cachedIntakeModel.stateWord == "needs attention")
        } else if (inactiveNotificationRequested) {
            if (desiredOn) ObserverForegroundService.postAttentionNotification(applicationContext)
            else ObserverForegroundService.postStoppedNotification(applicationContext)
        } else {
            ObserverForegroundService.refreshInactiveNotification(applicationContext, stopped = !desiredOn)
        }

        val statusModel = container?.let { initialized ->
            runCatching {
                val backlog = PhoneStatusSupplier.forContainer(initialized).invoke()
                statusListeners.forEach { listener -> runCatching { listener(backlog) } }
                phoneStatusSnapshotOf(
                    backlog = backlog,
                    registered = readModel?.sources.orEmpty(),
                ).status
            }.getOrElse { emptyPhoneStatus() }
        } ?: emptyPhoneStatus()
        cachedWidgetModel = renderPhoneObserverWidget(
            readModel = readModel,
            statusModel = statusModel,
            startOutcome = widgetStartOutcomes.read(),
        )
    }

    private fun startCaptureAction(
        context: Context,
        isRunning: Boolean,
        hasEnabledSources: Boolean,
    ): Notification.Action? {
        if (!shouldOfferStartAction(isRunning = isRunning, hasEnabledSources = hasEnabledSources)) return null
        val pendingIntent = PendingIntent.getForegroundService(
            context,
            START_CAPTURE_REQUEST_CODE,
            ObserverForegroundService.intakeStartIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        return Notification.Action.Builder(0, ObserverNotification.TEXT_START_INTAKE, pendingIntent).build()
    }

    private fun decorateObserverNotification(context: Context, builder: Notification.Builder) {
        // Notification decoration performs no Room or filesystem I/O; it renders only the snapshot
        // refreshed from background work.
        val model = cachedIntakeModel
        builder.setContentText(model.stateWord)
        val routeIntent = pendingIntentForRoute(context, model.route)
        if (routeIntent != null) {
            builder.setContentIntent(routeIntent)
        }
    }

    private fun pendingIntentForRoute(context: Context, route: PhoneRoute?): PendingIntent? {
        val launchIntent = if (route != null) {
            Intent(context, PhoneShellActivity::class.java).apply {
                putExtra(EXTRA_PHONE_ROUTE, encodePhoneRoute(route))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            } ?: return null
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(
            context,
            PHONE_ROUTE_REQUEST_CODE,
            launchIntent,
            flags,
        )
    }

    private companion object {
        const val START_CAPTURE_REQUEST_CODE = 201
        const val STOP_CAPTURE_REQUEST_CODE = 202
        const val PHONE_ROUTE_REQUEST_CODE = 203
    }
}
