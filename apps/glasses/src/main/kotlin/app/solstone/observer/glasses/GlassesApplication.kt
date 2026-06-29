// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import app.solstone.core.sources.GLASSES_STREAM
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.ObserverForegroundService.ObserverServiceRehydrator
import app.solstone.platform.fgs.ObserverNotification
import app.solstone.platform.fgs.ObserverNotificationDecorator
import app.solstone.platform.power.AndroidDeviceFingerprintProvider
import app.solstone.platform.power.OemGuidanceCatalog
import app.solstone.platform.work.SyncScheduler

class GlassesApplication : Application() {
    lateinit var runtime: GlassesObserverRuntime
        private set
    private var rokidReceiver: GlassesCommandReceiver? = null

    override fun onCreate() {
        super.onCreate()
        SyncScheduler.enqueuePeriodic(applicationContext, GLASSES_STREAM)
        runtime = GlassesObserverRuntime(applicationContext)
        GlassesHarnessRuntime.runtime = runtime
        ObserverForegroundService.rehydrator = ObserverServiceRehydrator {
            runtime.rehydrateFromForegroundServiceStart()
        }
        ObserverNotification.decorator = ObserverNotificationDecorator(::decorateObserverNotification)
        registerRokidButtonReceiverIfNeeded()
    }

    private fun decorateObserverNotification(context: Context, builder: Notification.Builder) {
        val flags = glassesPendingIntentFlags(Build.VERSION.SDK_INT)
        val contentIntent = PendingIntent.getActivity(
            context,
            CONTENT_INTENT_REQUEST_CODE,
            Intent(context, MainActivity::class.java),
            flags,
        )
        builder.setContentIntent(contentIntent)
        GlassesNotificationCommand.entries.forEach { command ->
            val actionIntent = Intent(context, GlassesCommandReceiver::class.java)
                .putExtra(EXTRA_COMMAND_ACTION, command.actionToken)
            val actionPendingIntent = PendingIntent.getBroadcast(
                context,
                command.requestCode,
                actionIntent,
                flags,
            )
            @Suppress("DEPRECATION")
            builder.addAction(
                Notification.Action.Builder(0, command.label, actionPendingIntent).build(),
            )
        }
    }

    private fun registerRokidButtonReceiverIfNeeded() {
        val guidanceId = OemGuidanceCatalog.select(AndroidDeviceFingerprintProvider().fingerprint()).id
        if (!rokidButtonHandlingEnabled(guidanceId)) return
        val receiver = GlassesCommandReceiver()
        val filter = IntentFilter().also { intentFilter ->
            RokidButtonActions.all.forEach(intentFilter::addAction)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        rokidReceiver = receiver
    }
}
