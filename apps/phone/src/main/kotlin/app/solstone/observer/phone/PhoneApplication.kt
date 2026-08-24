// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.Notification
import android.content.Context
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.formfactor.phone.PhoneWidgetStartOutcome
import app.solstone.observer.formfactor.phone.PhoneObserverWidgetModel
import app.solstone.observer.formfactor.phone.phoneStatusSnapshotOf
import app.solstone.observer.formfactor.phone.renderPhoneObserverWidget
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.scaffold.ObserverApplication
import app.solstone.observer.scaffold.ObserverRuntimeContainer
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.ObserverForegroundService.ObserverWidgetStartHandler
import app.solstone.platform.fgs.ObserverNotification
import app.solstone.platform.fgs.ObserverNotificationDecorator

class PhoneApplication : ObserverApplication(phoneSpec) {
    private lateinit var widgetCoordinator: PhoneWidgetCoordinator
    private lateinit var widgetStartOutcomes: PhoneWidgetStartOutcomeStore
    @Volatile private var cachedWidgetModel = emptyWidgetModel()

    override fun onCreate() {
        super.onCreate()
        widgetStartOutcomes = PhoneWidgetStartOutcomeStore(applicationContext)
        widgetCoordinator = PhoneWidgetCoordinator(applicationContext)
        runtime.onContainerInitialized(::onContainerInitialized)
        ObserverNotification.decorator = ObserverNotificationDecorator(::decorateObserverNotification)
        ObserverForegroundService.widgetStartHandler = object : ObserverWidgetStartHandler {
            override fun onForegroundServiceStarted(sourceId: String) {
                val refusal = runtime.container().activateSourceWhenAlreadyForeground(sourceId)
                if (refusal == null) {
                    widgetStartOutcomes.clear()
                } else {
                    runtime.containerIfInitialized?.controller?.recordStartRefusal()
                    widgetStartOutcomes.recordRefusal(refusal)
                }
                refreshWidgetAndUpdate()
            }

            override fun onForegroundServiceStartRefused(sourceId: String, reason: ReasonCode) {
                runtime.containerIfInitialized?.controller?.recordStartRefusal()
                widgetStartOutcomes.recordRefusal(reason)
                refreshWidgetAndUpdate()
            }
        }
    }

    internal fun widgetModel(): PhoneObserverWidgetModel =
        if (runtime.containerIfInitialized == null) emptyWidgetModel() else cachedWidgetModel

    internal fun turnAudioOffFromWidget() {
        runtime.containerIfInitialized?.sources?.setWish(PHONE_WIDGET_AUDIO_SOURCE_ID, SourceWish.Off)
        refreshWidgetAndUpdate()
    }

    private fun onContainerInitialized(container: ObserverRuntimeContainer) {
        if (widgetStartOutcomes.read() is PhoneWidgetStartOutcome.Refused) {
            container.controller.recordStartRefusal()
        }
        container.setBackgroundStatusRefreshListener {
            refreshWidgetModel(container)
            widgetCoordinator.updateAll()
        }
        container.sources.subscribe {
            val audio = container.sources.snapshot().sources.singleOrNull {
                it.sourceId == PHONE_WIDGET_AUDIO_SOURCE_ID
            }
            if (audio?.state == SourceState.ON) {
                widgetStartOutcomes.clear()
            }
            refreshWidgetAndUpdate()
        }
        refreshWidgetAndUpdate()
    }

    private fun refreshWidgetAndUpdate() {
        widgetCoordinator.refreshAndUpdateAll(::refreshWidgetModel)
    }

    private fun refreshWidgetModel(container: ObserverRuntimeContainer? = runtime.containerIfInitialized) {
        val readModel = container?.sources?.snapshot()
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

    private fun decorateObserverNotification(context: Context, builder: Notification.Builder) {
        // Notification decoration performs no Room or filesystem I/O; it renders only the snapshot
        // refreshed from background work.
        val model = widgetModel()
        builder
            .setContentTitle(model.stateWord)
            .setContentText(model.syncText)
    }
}
