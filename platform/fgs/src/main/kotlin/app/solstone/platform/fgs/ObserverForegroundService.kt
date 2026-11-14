// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import app.solstone.core.model.ReasonCode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class ScreenConsentToken(val resultCode: Int, val data: Intent, val generation: Long)

class ObserverForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            refreshHeartbeat()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        dispatchLifecycle("fgs phase=create")
        refreshHeartbeat()
        handler.post(heartbeat)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hook = rehydrator
        val widgetSourceId = widgetSourceId(intent)
        val isIntakeStart = intent?.getBooleanExtra(EXTRA_INTAKE_START, false) == true
        val permissions = AndroidPermissionStatusReader(this).read()
        val declared = declaredCaptureForegroundTypes ?: emptySet()
        val granted = linkedSetOf<CaptureForegroundType>().apply {
            if (permissions.microphoneGranted) add(CaptureForegroundType.MICROPHONE)
            if (permissions.locationGranted) add(CaptureForegroundType.LOCATION)
            if (permissions.cameraGranted) add(CaptureForegroundType.CAMERA)
        }

        val consentGen = if (intent != null && intent.hasExtra(EXTRA_SCREEN_CONSENT)) {
            intent.getLongExtra(EXTRA_SCREEN_CONSENT, -1L)
        } else null
        val heldToken = screenConsentToken
        val isConsentHandoff = consentGen != null && heldToken != null && consentGen == heldToken.generation

        var screenPromotedThisStart = false
        if (isConsentHandoff) {
            val storageOk = screenStorageOkCheck?.invoke() ?: true
            if (!storageOk) {
                clearScreenConsent()
                sessionPromotable.set(false)
                onScreenStorageBlocked?.invoke()
            } else {
                screenPromotedThisStart = true
                sessionPromotable.set(true)
            }
        }
        val promotable = isSessionPromotable()

        val wishSupplier = captureWishTypes
        val rawWished = wishSupplier?.invoke()
        val baseWished = if (widgetSourceId != null && rawWished != null) {
            captureForegroundTypeForSourceId(widgetSourceId)?.let { rawWished + it } ?: rawWished
        } else {
            rawWished
        }
        val wished = if (promotable) {
            if (baseWished != null) baseWished + CaptureForegroundType.MEDIA_PROJECTION
            else setOf(CaptureForegroundType.MEDIA_PROJECTION)
        } else {
            baseWished
        }

        try {
            if (wishSupplier != null || widgetSourceId != null || promotable) {
                val effectivePlan = effectiveCapturePlan(
                    microphoneGranted = permissions.microphoneGranted,
                    cameraGranted = permissions.cameraGranted,
                    locationGranted = permissions.locationGranted,
                    declared = declared,
                    wishedOn = wished,
                    liveHeld = heldCaptureForegroundTypes,
                    mediaProjectionPromotable = promotable,
                )

                if (effectivePlan.types.isEmpty()) {
                    if (heldCaptureForegroundTypes != null) {
                        removeForegroundNotification()
                    }
                    stopSelf()
                    return START_STICKY
                }

                val liveHeldTypes = heldCaptureForegroundTypes
                if (liveHeldTypes != null) {
                    if (effectivePlan.types != liveHeldTypes) {
                        val narrow = narrowRunningMask(effectivePlan.types)
                        if (narrow !is MaskNarrowResult.Applied) {
                            removeForegroundNotification()
                            stopSelf()
                            return START_STICKY
                        }
                    }
                    if (screenPromotedThisStart && heldToken != null) {
                        var started = false
                        try {
                            started = screenSessionStarter?.startSession(this, heldToken) ?: false
                        } catch (_: Throwable) {
                            started = false
                        }
                        if (!started) {
                            screenSessionStopper?.invoke()
                            sessionPromotable.set(false)
                            if (effectivePlan.types.none { it != CaptureForegroundType.MEDIA_PROJECTION }) {
                                removeForegroundNotification()
                                stopSelf()
                                return START_STICKY
                            } else {
                                val remaining = effectivePlan.types.filter { it != CaptureForegroundType.MEDIA_PROJECTION }.toSet()
                                narrowRunningMask(remaining)
                            }
                        } else {
                            onScreenSessionStarted?.invoke()
                        }
                    }
                    val plan = onStartCommandPlan(hasIntent = intent != null, hasRehydrator = hook != null, subsetEmpty = false)
                    refreshOngoingNotification(this, needsAttention = plan.initialNeedsAttention)
                    if (!plan.stopSelf) {
                        cancelAttentionNotification(this)
                    }
                    dispatchLifecycle("fgs phase=start startId=$startId flags=$flags intent=${intent != null}")
                    refreshHeartbeat()
                    if (plan.dispatchRehydrate) {
                        dispatchRehydrate(hook)
                    }
                    widgetSourceId?.let(::dispatchWidgetStartAccepted)
                    if (isIntakeStart && widgetSourceId == null) {
                        intakeStartHandler?.invoke()
                    }
                    if (plan.postAttentionOn102) {
                        postAttentionNotification(this)
                    }
                    if (plan.stopSelf) {
                        removeForegroundNotification()
                        stopSelf()
                    }
                    return START_STICKY
                }

                val subset = effectivePlan.types
                dispatchLifecycle(typesDiagLine(granted = granted, declared = declared, subset = subset))
                val plan = onStartCommandPlan(
                    hasIntent = intent != null,
                    hasRehydrator = hook != null,
                    subsetEmpty = false,
                )
                try {
                    ServiceCompat.startForeground(
                        this,
                        ObserverNotification.SERVICE_NOTIFICATION_ID,
                        ObserverNotification.ongoing(
                            this,
                            needsAttention = plan.initialNeedsAttention,
                            decorate = true,
                            requestPromotion = true,
                            initialForegroundEntry = true,
                        ),
                        captureForegroundTypeMask(subset),
                    )
                    heldCaptureForegroundTypes = subset
                    dispatchForegroundChanged(true)
                } catch (e: SecurityException) {
                    handleStartFailure(this, e.javaClass.simpleName)
                    widgetSourceId?.let { dispatchWidgetStartRefused(it, ReasonCode.PERMISSION_REVOKED) }
                    stopSelf()
                    return START_STICKY
                } catch (e: IllegalArgumentException) {
                    handleStartFailure(this, e.javaClass.simpleName)
                    widgetSourceId?.let { dispatchWidgetStartRefused(it, ReasonCode.PERMISSION_REVOKED) }
                    stopSelf()
                    return START_STICKY
                } catch (e: RuntimeException) {
                    handleStartFailure(this, e.javaClass.simpleName)
                    val reason = widgetRefusalReasonForStartException(e.javaClass.simpleName)
                    widgetSourceId?.let { dispatchWidgetStartRefused(it, reason) }
                    stopSelf()
                    return START_STICKY
                }

                if (screenPromotedThisStart && heldToken != null) {
                    var started = false
                    try {
                        started = screenSessionStarter?.startSession(this, heldToken) ?: false
                    } catch (_: Throwable) {
                        started = false
                    }
                    if (!started) {
                        screenSessionStopper?.invoke()
                        sessionPromotable.set(false)
                        if (subset.none { it != CaptureForegroundType.MEDIA_PROJECTION }) {
                            removeForegroundNotification()
                            stopSelf()
                            return START_STICKY
                        } else {
                            val remaining = subset.filter { it != CaptureForegroundType.MEDIA_PROJECTION }.toSet()
                            narrowRunningMask(remaining)
                        }
                    } else {
                        onScreenSessionStarted?.invoke()
                    }
                }
                if (!plan.stopSelf) {
                    cancelAttentionNotification(this)
                }
                dispatchLifecycle("fgs phase=start startId=$startId flags=$flags intent=${intent != null}")
                refreshHeartbeat()
                if (plan.dispatchRehydrate) {
                    dispatchRehydrate(hook)
                }
                widgetSourceId?.let(::dispatchWidgetStartAccepted)
                if (isIntakeStart && widgetSourceId == null) {
                    intakeStartHandler?.invoke()
                }
                if (plan.postAttentionOn102) {
                    postAttentionNotification(this)
                }
                if (plan.stopSelf) {
                    removeForegroundNotification()
                    stopSelf()
                }
                return START_STICKY
            }
        } finally {
            if (isConsentHandoff) {
                clearScreenConsent()
            }
        }

        val liveHeldTypes = heldCaptureForegroundTypes
        if (liveHeldTypes != null) {
            val plan = onStartCommandPlan(hasIntent = intent != null, hasRehydrator = hook != null, subsetEmpty = false)
            refreshOngoingNotification(this, needsAttention = plan.initialNeedsAttention)
            if (!plan.stopSelf) {
                cancelAttentionNotification(this)
            }
            dispatchLifecycle("fgs phase=start startId=$startId flags=$flags intent=${intent != null}")
            refreshHeartbeat()
            if (plan.dispatchRehydrate) {
                dispatchRehydrate(hook)
            }
            widgetSourceId?.let(::dispatchWidgetStartAccepted)
            if (isIntakeStart && widgetSourceId == null) {
                intakeStartHandler?.invoke()
            }
            if (plan.postAttentionOn102) {
                postAttentionNotification(this)
            }
            if (plan.stopSelf) {
                removeForegroundNotification()
                stopSelf()
            }
            return START_STICKY
        }

        val subset = satisfiableCaptureForegroundTypes(
            microphoneGranted = permissions.microphoneGranted,
            cameraGranted = permissions.cameraGranted,
            locationGranted = permissions.locationGranted,
            declared = declared,
            mediaProjectionPromotable = promotable,
        )
        dispatchLifecycle(typesDiagLine(granted = granted, declared = declared, subset = subset))
        val plan = onStartCommandPlan(
            hasIntent = intent != null,
            hasRehydrator = hook != null,
            subsetEmpty = subset.isEmpty(),
        )
        if (!plan.enterForeground) {
            handleStartFailure(this, "EmptyCaptureForegroundSubset")
            widgetSourceId?.let { dispatchWidgetStartRefused(it, ReasonCode.PERMISSION_REVOKED) }
            stopSelf()
            return START_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                ObserverNotification.SERVICE_NOTIFICATION_ID,
                ObserverNotification.ongoing(
                    this,
                    needsAttention = plan.initialNeedsAttention,
                    decorate = true,
                    requestPromotion = true,
                    initialForegroundEntry = true,
                ),
                captureForegroundTypeMask(subset),
            )
            heldCaptureForegroundTypes = subset
            dispatchForegroundChanged(true)
        } catch (e: SecurityException) {
            handleStartFailure(this, e.javaClass.simpleName)
            widgetSourceId?.let { dispatchWidgetStartRefused(it, ReasonCode.PERMISSION_REVOKED) }
            stopSelf()
            return START_STICKY
        } catch (e: IllegalArgumentException) {
            handleStartFailure(this, e.javaClass.simpleName)
            widgetSourceId?.let { dispatchWidgetStartRefused(it, ReasonCode.PERMISSION_REVOKED) }
            stopSelf()
            return START_STICKY
        } catch (e: RuntimeException) {
            handleStartFailure(this, e.javaClass.simpleName)
            val reason = widgetRefusalReasonForStartException(e.javaClass.simpleName)
            widgetSourceId?.let { dispatchWidgetStartRefused(it, reason) }
            stopSelf()
            return START_STICKY
        }
        if (!plan.stopSelf) {
            cancelAttentionNotification(this)
        }
        dispatchLifecycle("fgs phase=start startId=$startId flags=$flags intent=${intent != null}")
        refreshHeartbeat()
        if (plan.dispatchRehydrate) {
            dispatchRehydrate(hook)
        }
        widgetSourceId?.let(::dispatchWidgetStartAccepted)
        if (isIntakeStart && widgetSourceId == null) {
            intakeStartHandler?.invoke()
        }
        if (plan.postAttentionOn102) {
            postAttentionNotification(this)
        }
        if (plan.stopSelf) {
            removeForegroundNotification()
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        dispatchLifecycle("fgs phase=destroy")
        runningInstance = null
        heldCaptureForegroundTypes = null
        sessionPromotable.set(false)
        screenSessionStopper?.invoke()
        clearScreenConsent()
        // ⚠ stopService is asynchronous. A refresh that ran on this thread between the stop and
        // now passed its check and posted 101; if that post reaches the system after it has
        // cancelled the foreground notification, 101 comes back unowned and says the observer is
        // on after it stopped. This cancel is ordered after any such post, and nothing can post
        // 101 once the field above is null.
        getSystemService(NotificationManager::class.java)?.cancel(ObserverNotification.SERVICE_NOTIFICATION_ID)
        handler.removeCallbacks(heartbeat)
        invalidateHeartbeat()
        dispatchForegroundChanged(false)
        onDestroyCallback?.invoke()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        dispatchLifecycle("fgs phase=task-removed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refreshHeartbeat() {
        lastBeatNanos.set(System.nanoTime())
    }

    companion object {

        fun interface ScreenSessionStarter {
            fun startSession(service: ObserverForegroundService, token: ScreenConsentToken): Boolean
        }

        @Volatile var screenConsentToken: ScreenConsentToken? = null
        val sessionPromotable = AtomicBoolean(false)
        fun isSessionPromotable(): Boolean = sessionPromotable.get()
        @Volatile var isScreenSessionLive: () -> Boolean = { false }
        @Volatile var screenStorageOkCheck: (() -> Boolean)? = null
        @Volatile var onScreenStorageBlocked: (() -> Unit)? = null
        @Volatile var onScreenSessionStarted: (() -> Unit)? = null
        @Volatile var onScreenSessionEnded: (() -> Unit)? = null
        @Volatile var screenSessionStarter: ScreenSessionStarter? = null
        @Volatile var screenSessionStopper: (() -> Unit)? = null

        fun offerScreenConsent(token: ScreenConsentToken) {
            screenConsentToken = token
        }

        fun clearScreenConsent() {
            screenConsentToken = null
        }

        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val START_GRACE_NANOS = 5_000_000_000L
        private const val ATTENTION_CONTENT_REQUEST_CODE = 102
        private val lastBeatNanos = AtomicLong(0L)
        private val lastStartRequestedNanos = AtomicLong(0L)

        @Volatile internal var runningInstance: ObserverForegroundService? = null
        @Volatile var declaredCaptureForegroundTypes: Set<CaptureForegroundType>? = null
        // Every assignment follows a foreground entry that posted the notification itself, or the
        // end of one that cancelled it, so the next refresh posts whatever it has.
        @Volatile var heldCaptureForegroundTypes: Set<CaptureForegroundType>? = null
            set(value) {
                field = value
                shownNotifications.forget(ObserverNotification.SERVICE_NOTIFICATION_ID)
            }
        private val shownNotifications = NotificationPostGate()
        @Volatile var captureWishTypes: (() -> Set<CaptureForegroundType>?)? = null
        @Volatile var rehydrator: ObserverServiceRehydrator? = null
        @Volatile var widgetStartHandler: ObserverWidgetStartHandler? = null
        @Volatile var lifecycleDiag: ((String) -> Unit)? = null
        @Volatile var onDestroyCallback: (() -> Unit)? = null
        @Volatile var onForegroundChanged: ((Boolean) -> Unit)? = null

        fun narrowRunningMask(types: Set<CaptureForegroundType>): MaskNarrowResult {
            val instance = runningInstance
            val result = classifyMaskNarrow(serviceReachable = instance != null) {
                val target = requireNotNull(instance)
                ServiceCompat.startForeground(
                    target,
                    ObserverNotification.SERVICE_NOTIFICATION_ID,
                    ObserverNotification.ongoing(
                        target,
                        needsAttention = false,
                        decorate = true,
                        requestPromotion = true,
                        initialForegroundEntry = false,
                    ),
                    captureForegroundTypeMask(types),
                )
            }
            if (result is MaskNarrowResult.Applied) {
                heldCaptureForegroundTypes = types
                dispatchForegroundChanged(true)
            }
            return result
        }

        const val EXTRA_SCREEN_CONSENT = "app.solstone.platform.fgs.extra.SCREEN_CONSENT"
        const val EXTRA_REFRESH_RUNNING_CAPTURE_TYPES = "app.solstone.platform.fgs.extra.REFRESH_RUNNING_CAPTURE_TYPES"

        fun refreshRunningCaptureTypes(context: Context) {
            if (heldCaptureForegroundTypes == null) return
            val intent = Intent(context, ObserverForegroundService::class.java).apply {
                putExtra(EXTRA_REFRESH_RUNNING_CAPTURE_TYPES, true)
            }
            context.startService(intent)
        }

        fun dispatchRehydrate(hook: ObserverServiceRehydrator?) {
            hook?.onForegroundServiceStarted()
        }

        fun dispatchWidgetStartAccepted(sourceId: String) {
            widgetStartHandler?.onForegroundServiceStarted(sourceId)
        }

        fun dispatchWidgetStartRefused(sourceId: String, reason: ReasonCode) {
            widgetStartHandler?.onForegroundServiceStartRefused(sourceId, reason)
        }

        fun dispatchLifecycle(line: String) {
            lifecycleDiag?.invoke(line)
        }

        fun startFromVisibleContext(context: Context) {
            markStartRequested()
            val intent = Intent(context, ObserverForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: SecurityException) {
                handleStartFailure(context, e.javaClass.simpleName)
            } catch (e: IllegalArgumentException) {
                handleStartFailure(context, e.javaClass.simpleName)
            } catch (e: RuntimeException) {
                handleStartFailure(context, e.javaClass.simpleName)
            }
        }

        fun widgetStartIntent(context: Context, sourceId: String): Intent =
            Intent(context, ObserverForegroundService::class.java)
                .putExtra(EXTRA_WIDGET_SOURCE_ID, sourceId)

        fun stop(context: Context) {
            invalidateHeartbeat()
            context.stopService(Intent(context, ObserverForegroundService::class.java))
        }

        fun lastHeartbeatNanos(): Long? =
            lastBeatNanos.get().takeIf { it > 0L }

        /**
         * Whether intake has ever been started in this process — a start was requested, or a beat
         * was recorded.
         *
         * ⚠ [isHeartbeatFresh] is false both for a service that beat and went silent and for one
         * that was never asked to start, and only the first is a fault. Reading the two atomics
         * for presence rather than age is what tells them apart.
         */
        fun isStartObserved(): Boolean =
            lastBeatNanos.get() > 0L || lastStartRequestedNanos.get() > 0L

        fun isHeartbeatFresh(
            nowNanos: Long = System.nanoTime(),
            staleAfterNanos: Long = 15_000_000_000L,
            startGraceNanos: Long = START_GRACE_NANOS,
        ): Boolean =
            HeartbeatMonitor.isFresh(
                nowNanos,
                lastHeartbeatNanos(),
                lastStartRequestedNanos.get().takeIf { it > 0L },
                staleAfterNanos,
                startGraceNanos,
            )

        internal fun markStartRequested(nowNanos: Long = System.nanoTime()) {
            lastStartRequestedNanos.set(nowNanos)
        }

        private fun invalidateHeartbeat() {
            lastBeatNanos.set(0L)
        }

        private fun captureForegroundTypeMask(types: Set<CaptureForegroundType>): Int {
            var mask = 0
            if (CaptureForegroundType.MICROPHONE in types) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (CaptureForegroundType.LOCATION in types) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (CaptureForegroundType.CAMERA in types) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (CaptureForegroundType.MEDIA_PROJECTION in types) {
                if (Build.VERSION.SDK_INT >= 29) {
                    mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
            }
            return mask
        }

        const val EXTRA_INTAKE_START = "app.solstone.platform.fgs.extra.INTAKE_START"

        fun intakeStartIntent(context: Context): Intent =
            Intent(context, ObserverForegroundService::class.java).putExtra(EXTRA_INTAKE_START, true)

        @Volatile
        var intakeStartHandler: (() -> Unit)? = null

        private fun dispatchForegroundChanged(live: Boolean) {
            runCatching { onForegroundChanged?.invoke(live) }.onFailure {
                dispatchLifecycle("fgs notification refresh failed=${it.javaClass.simpleName}")
            }
        }

        private fun onNotificationThread(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action()
            else Handler(Looper.getMainLooper()).post { action() }
        }

        fun refreshOngoingNotification(context: Context, needsAttention: Boolean) = onNotificationThread {
            // The check and notify share the service lifecycle's main thread. A queued
            // refresh cannot pass the check, race onDestroy, then resurrect notification 101.
            if (heldCaptureForegroundTypes == null || !ObserverNotification.notificationsPermitted(context)) {
                return@onNotificationThread
            }
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@onNotificationThread
            val notification = ObserverNotification.ongoing(context, needsAttention = needsAttention, decorate = true, requestPromotion = true)
            val shown = ObserverNotification.shownContent(notification)
            val now = SystemClock.elapsedRealtime()
            if (!shownNotifications.shouldPost(ObserverNotification.SERVICE_NOTIFICATION_ID, shown, now)) return@onNotificationThread
            manager.notify(ObserverNotification.SERVICE_NOTIFICATION_ID, notification)
            shownNotifications.posted(ObserverNotification.SERVICE_NOTIFICATION_ID, shown, now)
            manager.cancel(ObserverNotification.BOOT_NOTIFICATION_ID)
            shownNotifications.forget(ObserverNotification.BOOT_NOTIFICATION_ID)
        }

        fun postAttentionNotification(context: Context) = postInactiveNotification(context, stopped = false)

        fun postStoppedNotification(context: Context) = postInactiveNotification(context, stopped = true)

        fun refreshInactiveNotification(context: Context, stopped: Boolean) =
            postInactiveNotification(context, stopped, onlyIfPresent = true, unlessShown = true)

        /** The attention or stopped notification as a status refresh: posts only what it doesn't already show. */
        fun refreshAttentionNotification(context: Context, stopped: Boolean) =
            postInactiveNotification(context, stopped, unlessShown = true)

        private fun postInactiveNotification(
            context: Context,
            stopped: Boolean,
            onlyIfPresent: Boolean = false,
            unlessShown: Boolean = false,
        ) =
            onNotificationThread {
                if (heldCaptureForegroundTypes != null || !ObserverNotification.notificationsPermitted(context)) {
                    return@onNotificationThread
                }
                val manager = context.getSystemService(NotificationManager::class.java) ?: return@onNotificationThread
                if (onlyIfPresent && manager.activeNotifications.none { it.id == ObserverNotification.BOOT_NOTIFICATION_ID }) {
                    return@onNotificationThread
                }
                val notification = ObserverNotification.ongoing(
                    context,
                    needsAttention = !stopped,
                    stopped = stopped,
                    decorate = true,
                    includeStopAction = false,
                    contentIntent = launchPendingIntent(context),
                )
                val shown = ObserverNotification.shownContent(notification)
                val now = SystemClock.elapsedRealtime()
                if (unlessShown && !shownNotifications.shouldPost(ObserverNotification.BOOT_NOTIFICATION_ID, shown, now)) {
                    return@onNotificationThread
                }
                manager.notify(ObserverNotification.BOOT_NOTIFICATION_ID, notification)
                shownNotifications.posted(ObserverNotification.BOOT_NOTIFICATION_ID, shown, now)
            }

        fun cancelAttentionNotification(context: Context) = onNotificationThread {
            context.getSystemService(NotificationManager::class.java)?.cancel(ObserverNotification.BOOT_NOTIFICATION_ID)
            shownNotifications.forget(ObserverNotification.BOOT_NOTIFICATION_ID)
        }

        private fun handleStartFailure(context: Context, exceptionClassName: String) {
            dispatchLifecycle(startFailureDiagLine(exceptionClassName))
            postAttentionNotification(context)
        }

        private fun launchPendingIntent(context: Context): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            return PendingIntent.getActivity(context, ATTENTION_CONTENT_REQUEST_CODE, launch, pendingIntentFlags())
        }

        private fun pendingIntentFlags(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

        private fun widgetSourceId(intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_WIDGET_SOURCE_ID)?.takeIf(String::isNotBlank)

        const val EXTRA_WIDGET_SOURCE_ID = "app.solstone.platform.fgs.extra.WIDGET_SOURCE_ID"
    }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    fun interface ObserverServiceRehydrator {
        fun onForegroundServiceStarted()
    }

    interface ObserverWidgetStartHandler {
        fun onForegroundServiceStarted(sourceId: String)
        fun onForegroundServiceStartRefused(sourceId: String, reason: ReasonCode)
    }
}
