// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.wear.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import app.solstone.core.segment.wireKeys
import app.solstone.core.watch.courier.CompletedWatchWindow
import app.solstone.core.watch.courier.CourierBundleHeader
import app.solstone.core.watch.courier.CourierFilePayload
import app.solstone.core.watch.courier.TimestampedAudioFrame
import app.solstone.core.watch.courier.WatchCourierCodec
import app.solstone.core.watch.courier.WatchCourierProtocol
import app.solstone.core.watch.courier.WatchLocationFix
import app.solstone.core.watch.courier.WatchLocationRecord
import app.solstone.core.watch.courier.WatchRecorderSnapshot
import app.solstone.core.watch.courier.WatchWindowPump
import app.solstone.core.watch.courier.WindowPumpStopReason
import app.solstone.observer.wear.spool.WatchSpoolStore
import app.solstone.observer.wear.worker.WatchTransferWorker
import app.solstone.platform.audio.AndroidAacAdtsRemuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.time.ZoneId
import java.util.concurrent.Executors

class ContinuousAudioRecorder(
    private val context: Context,
    private val spoolStore: WatchSpoolStore,
    private val onSessionStatusChanged: () -> Unit = {},
    private val captureDeviceFactory: (Context) -> AudioCaptureDevice = { RealAudioCaptureDevice(it) },
    private val elapsedClock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    @Volatile private var isRecording = false
    @Volatile private var startSucceeded = false
    @Volatile private var lastInputTimestampEpochMs: Long? = null
    @Volatile private var clientSilenced = false
    @Volatile private var terminalReason: WindowPumpStopReason? = null
    @Volatile private var startedAtEpochMs: Long? = null
    @Volatile private var firstInputTimestampEpochMs: Long? = null
    @Volatile private var startedAtElapsedMs: Long? = null
    @Volatile private var lastInputElapsedMs: Long? = null

    private var captureDevice: AudioCaptureDevice? = null
    private var readerThread: Thread? = null
    private val remuxExecutor = Executors.newSingleThreadExecutor()

    private var locationListener: LocationListenerCompat? = null
    @Volatile private var latestLocationFix: WatchLocationFix? = null

    private val sessionLock = Any()
    private var pendingCompletedSessionId: String? = null

    private val windowPump = WatchWindowPump { completedWindow ->
        val sessionId = pendingCompletedSessionId
        pendingCompletedSessionId = null
        if (sessionId != null) {
            handleCompletedWindow(completedWindow, sessionId)
        }
    }

    val receiving: Boolean
        get() = windowPump.receiving

    fun snapshot(): WatchRecorderSnapshot = WatchRecorderSnapshot(
        startSucceeded = startSucceeded,
        lastInputTimestampEpochMs = lastInputTimestampEpochMs,
        clientSilenced = clientSilenced,
        terminalReason = terminalReason,
        startedAtEpochMs = startedAtEpochMs,
        firstInputTimestampEpochMs = firstInputTimestampEpochMs,
        startedAtElapsedMs = startedAtElapsedMs,
        lastInputElapsedMs = lastInputElapsedMs,
    )

    @Synchronized
    fun start() {
        if (isRecording) return

        val currentFreeSpace = context.filesDir.usableSpace
        if (currentFreeSpace < WatchCourierProtocol.STORAGE_FLOOR_BYTES) {
            startSucceeded = false
            terminalReason = WindowPumpStopReason.STORAGE_FULL
            onSessionStatusChanged()
            return
        }

        val device = captureDeviceFactory(context)
        captureDevice = device

        try {
            device.prepare(onError = {
                stop(WindowPumpStopReason.SENSOR_FAILURE)
            })
            // Register before start so the initial native configuration cannot be missed.
            clientSilenced = device.registerSilenceCallback { silenced ->
                clientSilenced = silenced
                onSessionStatusChanged()
            }

            val audioStream = device.start()
            startedAtEpochMs = System.currentTimeMillis()
            startedAtElapsedMs = elapsedClock()
            firstInputTimestampEpochMs = null
            lastInputElapsedMs = null
            isRecording = true
            startSucceeded = true
            terminalReason = null
            lastInputTimestampEpochMs = null

            spoolStore.startOpenSession(ZoneId.systemDefault())
            windowPump.start(System.currentTimeMillis())
            startLocationSession()
            onSessionStatusChanged()

            readerThread = Thread({
                readFramesLoop(audioStream)
            }, "continuous-audio-reader").apply {
                isDaemon = true
                start()
            }
        } catch (_: SecurityException) {
            handlePreStartFailure(WindowPumpStopReason.PERMISSION_FAILURE)
        } catch (_: Exception) {
            handlePreStartFailure(WindowPumpStopReason.SENSOR_FAILURE)
        }
    }

    private fun handlePreStartFailure(reason: WindowPumpStopReason) {
        isRecording = false
        startSucceeded = false
        terminalReason = reason

        try {
            captureDevice?.unregisterSilenceCallback()
        } catch (_: Exception) {}
        try {
            captureDevice?.release()
        } catch (_: Exception) {}
        captureDevice = null

        remuxExecutor.shutdown()

        onSessionStatusChanged()
    }

    private fun startLocationSession() {
        latestLocationFix = null
        try {
            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasCoarse) return

            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            // Balanced power and a 100 m distance filter; permission controls actual precision. Fused, with the network
            // provider as the fallback, at balanced power; never a GPS-only subscription.
            val provider = when {
                Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> return
            }
            val listener = LocationListenerCompat { loc -> recordLocationFix(loc) }
            locationListener = listener
            val request = LocationRequestCompat.Builder(LOCATION_INTERVAL_MS)
                .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
                .setMinUpdateDistanceMeters(LOCATION_MIN_DISTANCE_M)
                .build()
            try {
                LocationManagerCompat.requestLocationUpdates(lm, provider, request, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
            try {
                lm.getLastKnownLocation(provider)?.let { recordLocationFix(it) }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
    }

    private fun recordLocationFix(loc: Location) {
        val fixAge = (System.currentTimeMillis() - loc.time).coerceAtLeast(0L)
        val fix = WatchLocationFix(
            provider = loc.provider ?: "fused",
            timestampEpochMs = loc.time,
            lat = loc.latitude,
            lon = loc.longitude,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy.toDouble() else Double.NaN,
            fixAgeMs = fixAge,
        )
        synchronized(this) {
            val current = latestLocationFix
            if (current == null || loc.time >= current.timestampEpochMs) {
                latestLocationFix = fix
            }
        }
    }

    private fun stopLocationSession() {
        locationListener?.let { listener ->
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                lm?.removeUpdates(listener)
            } catch (_: Exception) {
            }
            locationListener = null
        }
    }

    private fun readFramesLoop(input: InputStream) {
        val sampleRate = 16000
        val frameDurationMs = 1024L * 1000L / sampleRate // 64 ms
        val headerBuf = ByteArray(7)
        var framesSinceSpaceCheck = 0

        try {
            while (isRecording) {
                framesSinceSpaceCheck++
                if (framesSinceSpaceCheck >= 15) { // every ~1 second
                    framesSinceSpaceCheck = 0
                    val freeSpace = context.filesDir.usableSpace
                    if (freeSpace < WatchCourierProtocol.STORAGE_FLOOR_BYTES) {
                        stop(WindowPumpStopReason.STORAGE_FULL)
                        break
                    }
                }

                // Find ADTS sync word 0xFFF
                val byte1 = input.read()
                if (byte1 == -1) {
                    if (isRecording) {
                        stop(WindowPumpStopReason.SENSOR_FAILURE)
                    }
                    break
                }
                if (byte1 != 0xFF) continue

                val byte2 = input.read()
                if (byte2 == -1) {
                    if (isRecording) {
                        stop(WindowPumpStopReason.SENSOR_FAILURE)
                    }
                    break
                }
                if ((byte2 and 0xF0) != 0xF0) continue

                headerBuf[0] = byte1.toByte()
                headerBuf[1] = byte2.toByte()

                // Read remaining 5 bytes of 7-byte header
                var headerRead = 2
                while (headerRead < 7) {
                    val r = input.read(headerBuf, headerRead, 7 - headerRead)
                    if (r == -1) break
                    headerRead += r
                }
                if (headerRead < 7) {
                    if (isRecording) {
                        stop(WindowPumpStopReason.SENSOR_FAILURE)
                    }
                    break
                }

                val frameLength = ((headerBuf[3].toInt() and 0x03) shl 11) or
                    ((headerBuf[4].toInt() and 0xFF) shl 3) or
                    ((headerBuf[5].toInt() and 0xE0) ushr 5)

                if (frameLength < 7 || frameLength > 8192) continue

                val packet = ByteArray(frameLength)
                System.arraycopy(headerBuf, 0, packet, 0, 7)

                var bodyRead = 7
                while (bodyRead < frameLength) {
                    val r = input.read(packet, bodyRead, frameLength - bodyRead)
                    if (r == -1) break
                    bodyRead += r
                }
                if (bodyRead < frameLength) {
                    if (isRecording) {
                        stop(WindowPumpStopReason.SENSOR_FAILURE)
                    }
                    break
                }

                val nowMs = System.currentTimeMillis()
                val inputElapsedMs = elapsedClock()
                val frame = TimestampedAudioFrame(
                    timestampEpochMs = nowMs,
                    data = packet,
                    durationMs = frameDurationMs,
                )

                var accepted = false
                synchronized(sessionLock) {
                    if (!isRecording || !windowPump.receiving) return@synchronized
                    if (windowPump.opensNewWindow(nowMs)) {
                        val prevId = spoolStore.rotateOpenSession()
                        pendingCompletedSessionId = prevId
                    }

                    // Fsync frame into durable open session
                    spoolStore.appendOpenFrame(frame, ZoneId.systemDefault())

                    accepted = windowPump.feed(frame)
                }

                if (accepted) {
                    if (firstInputTimestampEpochMs == null) firstInputTimestampEpochMs = nowMs
                    lastInputElapsedMs = inputElapsedMs
                    lastInputTimestampEpochMs = nowMs
                }
            }
        } catch (_: Exception) {
            if (isRecording) {
                stop(WindowPumpStopReason.SENSOR_FAILURE)
            }
        }
    }

    private fun handleCompletedWindow(window: CompletedWatchWindow, sessionId: String) {
        // As on the Apple Watch, a segment with no fresh fix carries the session's last one. With a
        // 100 m filter a still owner gets few new fixes. The fix keeps its true time and age.
        val locationAtCompletion = carriedLocationFix(latestLocationFix, window.endEpochMs)
        remuxExecutor.execute {
            val stagingDir = File(context.cacheDir, "wear_staging").apply { mkdirs() }
            val tempAdts = File(stagingDir, "window_${window.startEpochMs}.adts")
            val tempM4a = File(stagingDir, "window_${window.startEpochMs}.m4a")

            try {
                FileOutputStream(tempAdts).use { fos ->
                    for (frame in window.frames) {
                        fos.write(frame.data)
                    }
                    fos.fd.sync()
                }

                var remuxSuccess = false
                try {
                    AndroidAacAdtsRemuxer().remux(tempAdts, tempM4a)
                    if (tempM4a.exists() && tempM4a.length() > 0) {
                        remuxSuccess = true
                    }
                } catch (_: Exception) {
                    remuxSuccess = false
                }

                val zone = try {
                    ZoneId.of(window.zoneId)
                } catch (_: Exception) {
                    ZoneId.systemDefault()
                }
                val keys = wireKeys(window.startEpochMs, window.endEpochMs, zone)
                val bundleId = sessionId

                if (remuxSuccess) {
                    val audioBytes = tempM4a.readBytes()
                    val files = mutableListOf<CourierFilePayload>()
                    files += CourierFilePayload(
                        name = WatchCourierProtocol.FILE_NAME_AUDIO,
                        mediaType = WatchCourierProtocol.MEDIA_TYPE_AUDIO,
                        sha256 = WatchCourierCodec.sha256Hex(audioBytes),
                        byteSize = audioBytes.size.toLong(),
                        bytes = audioBytes,
                    )

                    // Optional location fix (only if real fix arrived)
                    val locationFix = locationAtCompletion
                    if (locationFix != null) {
                        val locationJson = WatchLocationRecord.buildLocationRecord(locationFix)
                        if (locationJson.isNotEmpty()) {
                            val locBytes = locationJson.toByteArray(Charsets.UTF_8)
                            files += CourierFilePayload(
                                name = WatchCourierProtocol.FILE_NAME_LOCATION,
                                mediaType = WatchCourierProtocol.MEDIA_TYPE_LOCATION,
                                sha256 = WatchCourierCodec.sha256Hex(locBytes),
                                byteSize = locBytes.size.toLong(),
                                bytes = locBytes,
                            )
                        }
                    }

                    val header = CourierBundleHeader(
                        protocolVersion = WatchCourierProtocol.PROTOCOL_VERSION,
                        bundleId = bundleId,
                        day = keys.day,
                        segment = keys.segment,
                        startEpochMs = window.startEpochMs,
                        endEpochMs = window.endEpochMs,
                        zoneId = keys.zoneId,
                        utcOffsetSeconds = keys.utcOffsetSeconds,
                        sealGeneration = 1L,
                    )

                    val baos = ByteArrayOutputStream()
                    WatchCourierCodec.encodeBundle(header, files, baos)
                    spoolStore.sealSessionBundle(bundleId, baos.toByteArray())

                    WatchTransferWorker.enqueue(context)
                } else {
                    spoolStore.recordFailure(sessionId, "remux_failed")
                    stop(saveFailureReason())
                    WatchTransferWorker.enqueue(context)
                }
            } catch (_: Exception) {
                runCatching { spoolStore.recordFailure(sessionId, "seal_failure") }
                stop(saveFailureReason())
                runCatching { WatchTransferWorker.enqueue(context) }
            } finally {
                tempAdts.delete()
                tempM4a.delete()
                stagingDir.delete()
            }
        }
    }

    private fun saveFailureReason(): WindowPumpStopReason =
        if (context.filesDir.usableSpace < WatchCourierProtocol.STORAGE_FLOOR_BYTES) {
            WindowPumpStopReason.STORAGE_FULL
        } else {
            WindowPumpStopReason.SENSOR_FAILURE
        }

    @Synchronized
    fun stop(reason: WindowPumpStopReason = WindowPumpStopReason.OWNER_STOP) {
        if (!isRecording && !startSucceeded) return
        isRecording = false
        startSucceeded = false
        if (terminalReason == null) {
            terminalReason = reason
        }

        stopLocationSession()

        try {
            captureDevice?.unregisterSilenceCallback()
        } catch (_: Exception) {}
        try {
            captureDevice?.stop()
        } catch (_: Exception) {}
        try {
            captureDevice?.release()
        } catch (_: Exception) {}
        captureDevice = null

        synchronized(sessionLock) {
            val stoppingSessionId = spoolStore.readLiveSessionId()
            spoolStore.closeLiveSession()
            pendingCompletedSessionId = stoppingSessionId

            windowPump.stop(terminalReason ?: reason)

            if (pendingCompletedSessionId != null && stoppingSessionId != null) {
                spoolStore.retireSession(stoppingSessionId)
                pendingCompletedSessionId = null
            }
        }
        remuxExecutor.shutdown() // Finish already queued seals without retaining a thread after this session.
        onSessionStatusChanged()
    }
}

/** The fix a completed segment carries: the latest one at or before the segment's end, unchanged. */
internal fun carriedLocationFix(latest: WatchLocationFix?, windowEndEpochMs: Long): WatchLocationFix? =
    latest?.takeIf { it.timestampEpochMs <= windowEndEpochMs }

internal const val LOCATION_INTERVAL_MS = 10_000L
internal const val LOCATION_MIN_DISTANCE_M = 100f
