// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.wear

import android.content.ContextWrapper
import app.solstone.core.watch.courier.WindowPumpStopReason
import app.solstone.observer.wear.capture.AudioCaptureDevice
import app.solstone.observer.wear.capture.ContinuousAudioRecorder
import app.solstone.observer.wear.spool.WatchSpoolStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContinuousAudioRecorderTest {

    private class FakeAudioCaptureDevice(
        var throwOnStart: Throwable? = null,
        var initialSilenced: Boolean = false,
        var customInputStream: InputStream? = null,
    ) : AudioCaptureDevice {
        var prepared = false
        var started = false
        var stopped = false
        var released = false
        var silenceCallbackUnregistered = false
        var errorCallback: (() -> Unit)? = null
        var silenceCallback: ((Boolean) -> Unit)? = null

        override fun prepare(onError: () -> Unit) {
            this.prepared = true
            this.errorCallback = onError
        }

        override fun start(): InputStream {
            throwOnStart?.let { throw it }
            this.started = true
            return customInputStream ?: ByteArrayInputStream(ByteArray(0))
        }

        override fun registerSilenceCallback(onSilencedChanged: (Boolean) -> Unit): Boolean {
            this.silenceCallback = onSilencedChanged
            return initialSilenced
        }

        override fun unregisterSilenceCallback() {
            this.silenceCallbackUnregistered = true
            this.silenceCallback = null
        }

        override fun stop() {
            this.stopped = true
        }

        override fun release() {
            this.released = true
        }

        fun triggerError() {
            errorCallback?.invoke()
        }

        fun triggerSilenceChanged(silenced: Boolean) {
            silenceCallback?.invoke(silenced)
        }
    }

    private fun createTestContext(tempDir: File): ContextWrapper {
        return object : ContextWrapper(null) {
            override fun getFilesDir(): File = File(tempDir, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(tempDir, "cache").apply { mkdirs() }
        }
    }

    private fun buildAdtsFrame(payload: ByteArray = byteArrayOf(1, 2, 3)): ByteArray {
        val frameLength = 7 + payload.size
        val header = ByteArray(frameLength)
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte()
        header[2] = 0x50.toByte()
        header[3] = (0x80 or ((frameLength shr 11) and 0x03)).toByte()
        header[4] = ((frameLength shr 3) and 0xFF).toByte()
        header[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte()
        System.arraycopy(payload, 0, header, 7, payload.size)
        return header
    }

    @Test
    fun silencedCallbackSetsSnapshotAndLeavesSessionStarted() {
        val tempDir = Files.createTempDirectory("recorder-test").toFile()
        val spoolStore = WatchSpoolStore(tempDir)
        val context = createTestContext(tempDir)

        val fakeDevice = FakeAudioCaptureDevice(
            initialSilenced = false,
            customInputStream = PipedInputStream(PipedOutputStream()),
        )

        val recorder = ContinuousAudioRecorder(
            context = context,
            spoolStore = spoolStore,
            captureDeviceFactory = { fakeDevice },
            elapsedClock = { System.nanoTime() / 1_000_000L },
        )

        recorder.start()
        val initialSnapshot = recorder.snapshot()
        assertTrue(initialSnapshot.startSucceeded)
        assertFalse(initialSnapshot.clientSilenced)

        // Trigger silence callback
        fakeDevice.triggerSilenceChanged(true)
        val silencedSnapshot = recorder.snapshot()
        assertTrue(silencedSnapshot.startSucceeded)
        assertTrue(silencedSnapshot.clientSilenced)

        recorder.stop()
        assertTrue(fakeDevice.stopped)
        assertTrue(fakeDevice.released)
        assertTrue(fakeDevice.silenceCallbackUnregistered)
    }

    @Test
    fun advancingFramesWithSilencedFalseStayNotSilenced() {
        val tempDir = Files.createTempDirectory("recorder-test").toFile()
        val spoolStore = WatchSpoolStore(tempDir)
        val context = createTestContext(tempDir)

        val pos = PipedOutputStream()
        val pis = PipedInputStream(pos)
        val fakeDevice = FakeAudioCaptureDevice(
            initialSilenced = false,
            customInputStream = pis,
        )

        val recorder = ContinuousAudioRecorder(
            context = context,
            spoolStore = spoolStore,
            captureDeviceFactory = { fakeDevice },
            elapsedClock = { System.nanoTime() / 1_000_000L },
        )

        recorder.start()
        assertTrue(recorder.snapshot().startSucceeded)

        // Feed ADTS frames
        val frame = buildAdtsFrame()
        pos.write(frame)
        pos.flush()

        // Wait for frame to be processed
        var received = false
        for (i in 0 until 50) {
            Thread.sleep(20)
            if (recorder.snapshot().lastInputTimestampEpochMs != null) {
                received = true
                break
            }
        }
        assertTrue(received, "Expected frame input timestamp to update")
        val snap = recorder.snapshot()
        assertNotNull(snap.lastInputTimestampEpochMs)
        assertFalse(snap.clientSilenced, "Client silenced should stay false as bytes advance")

        recorder.stop()
    }

    @Test
    fun startThrowingSecurityExceptionYieldsPermissionFailureAndReleasesFake() {
        val tempDir = Files.createTempDirectory("recorder-test").toFile()
        val spoolStore = WatchSpoolStore(tempDir)
        val context = createTestContext(tempDir)

        val fakeDevice = FakeAudioCaptureDevice(
            throwOnStart = SecurityException("RECORD_AUDIO permission denied"),
        )

        val recorder = ContinuousAudioRecorder(
            context = context,
            spoolStore = spoolStore,
            captureDeviceFactory = { fakeDevice },
            elapsedClock = { System.nanoTime() / 1_000_000L },
        )

        recorder.start()
        val snap = recorder.snapshot()
        assertFalse(snap.startSucceeded)
        assertEquals(WindowPumpStopReason.PERMISSION_FAILURE, snap.terminalReason)
        assertTrue(fakeDevice.released)
        assertTrue(fakeDevice.silenceCallbackUnregistered)
    }

    @Test
    fun anyOtherStartThrowYieldsSensorFailure() {
        val tempDir = Files.createTempDirectory("recorder-test").toFile()
        val spoolStore = WatchSpoolStore(tempDir)
        val context = createTestContext(tempDir)

        val fakeDevice = FakeAudioCaptureDevice(
            throwOnStart = RuntimeException("hardware mic busy"),
        )

        val recorder = ContinuousAudioRecorder(
            context = context,
            spoolStore = spoolStore,
            captureDeviceFactory = { fakeDevice },
            elapsedClock = { System.nanoTime() / 1_000_000L },
        )

        recorder.start()
        val snap = recorder.snapshot()
        assertFalse(snap.startSucceeded)
        assertEquals(WindowPumpStopReason.SENSOR_FAILURE, snap.terminalReason)
        assertTrue(fakeDevice.released)
        assertTrue(fakeDevice.silenceCallbackUnregistered)
    }

    @Test
    fun eofWhileRecordingYieldsSensorFailure() {
        val tempDir = Files.createTempDirectory("recorder-test").toFile()
        val spoolStore = WatchSpoolStore(tempDir)
        val context = createTestContext(tempDir)

        val pos = PipedOutputStream()
        val pis = PipedInputStream(pos)
        val fakeDevice = FakeAudioCaptureDevice(
            initialSilenced = false,
            customInputStream = pis,
        )

        val recorder = ContinuousAudioRecorder(
            context = context,
            spoolStore = spoolStore,
            captureDeviceFactory = { fakeDevice },
            elapsedClock = { System.nanoTime() / 1_000_000L },
        )

        recorder.start()
        assertTrue(recorder.snapshot().startSucceeded)

        // Close stream to trigger EOF while recording
        pos.close()

        // Wait for EOF detection
        var stoppedAsSensorFailure = false
        for (i in 0 until 50) {
            Thread.sleep(20)
            if (recorder.snapshot().terminalReason == WindowPumpStopReason.SENSOR_FAILURE) {
                stoppedAsSensorFailure = true
                break
            }
        }
        assertTrue(stoppedAsSensorFailure, "Expected SENSOR_FAILURE on EOF while recording")
    }

    @Test
    fun eofAfterOwnerStopDoesNotReplaceOwnerStopReason() {
        val tempDir = Files.createTempDirectory("recorder-test").toFile()
        val spoolStore = WatchSpoolStore(tempDir)
        val context = createTestContext(tempDir)

        val pos = PipedOutputStream()
        val pis = PipedInputStream(pos)
        val fakeDevice = FakeAudioCaptureDevice(
            initialSilenced = false,
            customInputStream = pis,
        )

        val recorder = ContinuousAudioRecorder(
            context = context,
            spoolStore = spoolStore,
            captureDeviceFactory = { fakeDevice },
            elapsedClock = { System.nanoTime() / 1_000_000L },
        )

        recorder.start()
        assertTrue(recorder.snapshot().startSucceeded)

        // Owner stops first
        recorder.stop(WindowPumpStopReason.OWNER_STOP)
        assertEquals(WindowPumpStopReason.OWNER_STOP, recorder.snapshot().terminalReason)

        // Then stream hits EOF
        pos.close()
        Thread.sleep(50)

        // Must still be OWNER_STOP
        assertEquals(WindowPumpStopReason.OWNER_STOP, recorder.snapshot().terminalReason)
    }

    @Test
    fun errorCallbackYieldsSensorFailure() {
        val tempDir = Files.createTempDirectory("recorder-test").toFile()
        val spoolStore = WatchSpoolStore(tempDir)
        val context = createTestContext(tempDir)

        val pos = PipedOutputStream()
        val pis = PipedInputStream(pos)
        val fakeDevice = FakeAudioCaptureDevice(
            initialSilenced = false,
            customInputStream = pis,
        )

        val recorder = ContinuousAudioRecorder(
            context = context,
            spoolStore = spoolStore,
            captureDeviceFactory = { fakeDevice },
            elapsedClock = { System.nanoTime() / 1_000_000L },
        )

        recorder.start()
        assertTrue(recorder.snapshot().startSucceeded)

        fakeDevice.triggerError()
        val snap = recorder.snapshot()
        assertFalse(snap.startSucceeded)
        assertEquals(WindowPumpStopReason.SENSOR_FAILURE, snap.terminalReason)

        pos.close()
    }
}
