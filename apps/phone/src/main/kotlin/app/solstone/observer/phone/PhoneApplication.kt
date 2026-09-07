// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.formfactor.phone.PhoneWidgetStartOutcome
import app.solstone.observer.formfactor.phone.PhoneObserverWidgetModel
import app.solstone.observer.formfactor.phone.phoneStatusSnapshotOf
import app.solstone.observer.formfactor.phone.renderPhoneObserverWidget
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourceToggleResult
import app.solstone.observer.harness.SourcesReadModel
import app.solstone.observer.scaffold.ForegroundSourceActivation
import app.solstone.observer.scaffold.ObserverApplication
import app.solstone.observer.scaffold.ObserverRuntimeContainer
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.ObserverForegroundService.ObserverWidgetStartHandler
import app.solstone.platform.fgs.ObserverNotification
import app.solstone.platform.fgs.ObserverNotificationDecorator
import app.solstone.platform.fgs.shouldOfferStartAction

import app.solstone.observer.formfactor.phone.EXTRA_PHONE_ROUTE
import app.solstone.observer.formfactor.phone.PhoneIntakeNotificationModel
import app.solstone.observer.formfactor.phone.PhoneRoute
import app.solstone.observer.formfactor.phone.derivePhoneIntakeNotification
import app.solstone.observer.formfactor.phone.derivePhoneIntakeNotificationCatching
import app.solstone.observer.formfactor.phone.encodePhoneRoute

class PhoneApplication : ObserverApplication(phoneSpec) {
    private lateinit var widgetCoordinator: PhoneWidgetCoordinator
    private lateinit var widgetStartOutcomes: PhoneWidgetStartOutcomeStore
    @Volatile private var cachedWidgetModel = emptyWidgetModel()
    @Volatile private var cachedIntakeModel = derivePhoneIntakeNotification(
        snapshot = null,
        fgsLive = false,
        desiredOn = false,
    )
    @Volatile private var inactiveNotificationRequested = false
    @Volatile internal var sourceReadOverride: ((ObserverRuntimeContainer?) -> SourcesReadModel?)? = null

    override fun onCreate() {
        PhoneDiagLog.install(applicationContext.filesDir)
        ObserverForegroundService.lifecycleDiag = { PhoneDiagLog.appendRaw(it) }
        super.onCreate()
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
            inactiveNotificationRequested = !live
            refreshWidgetAndUpdate()
        }
        ObserverForegroundService.intakeStartHandler = {
            runtime.containerIfInitialized?.controller?.startWhenAlreadyForeground()
        }
        ObserverForegroundService.widgetStartHandler = object : ObserverWidgetStartHandler {
            override fun onForegroundServiceStarted(sourceId: String) {
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
        runtime.containerIfInitialized?.sources?.setWish(PHONE_WIDGET_AUDIO_SOURCE_ID, SourceWish.Off)
        refreshWidgetAndUpdate()
    }

    internal fun stopObserverFromNotification() {
        runtime.containerIfInitialized?.controller?.stop()
    }

    private fun onContainerInitialized(container: ObserverRuntimeContainer) {
        inactiveNotificationRequested = false
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
                phoneStatusSnapshotOf(
                    backlog = PhoneStatusSupplier.forContainer(initialized).invoke(),
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
