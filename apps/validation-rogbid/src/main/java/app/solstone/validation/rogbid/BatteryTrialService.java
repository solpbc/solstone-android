// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.validation.rogbid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BatteryTrialService extends Service {
    public static final String ACTION_START = "app.solstone.validation.rogbid.BATTERY_TRIAL_START";
    public static final String ACTION_STOP = "app.solstone.validation.rogbid.BATTERY_TRIAL_STOP";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_DURATION_SECONDS = "duration_seconds";
    public static final String EXTRA_SAMPLE_INTERVAL_SECONDS = "sample_interval_seconds";
    public static final String EXTRA_CAMERA_INTERVAL_SECONDS = "camera_interval_seconds";
    public static final String EXTRA_WAIT_FOR_UNPLUG = "wait_for_unplug";
    public static final String EVIDENCE_FILE = "battery-trial-evidence.txt";

    private static final String TAG = "RogbidBatteryTrial";
    private static final String CHANNEL_ID = "rogbid_battery_trial";
    private static final int NOTIFICATION_ID = 41;
    private static final int SAMPLE_RATE = 16000;
    private static final String SAMPLE_FILE = "battery-trial-samples.csv";
    private static final String CAMERA_DIR = "battery-camera";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread trialThread;
    private MediaRecorder mediaRecorder;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTrial("stop requested");
            return START_NOT_STICKY;
        }

        String mode = intent == null ? "idle" : intent.getStringExtra(EXTRA_MODE);
        if (mode == null || mode.trim().isEmpty()) {
            mode = "idle";
        }
        int durationSeconds = intent == null ? 600 : intent.getIntExtra(EXTRA_DURATION_SECONDS, 600);
        durationSeconds = Math.max(30, durationSeconds);
        int sampleIntervalSeconds = intent == null
                ? 60
                : intent.getIntExtra(EXTRA_SAMPLE_INTERVAL_SECONDS, 60);
        sampleIntervalSeconds = Math.max(5, sampleIntervalSeconds);
        int cameraIntervalSeconds = intent == null
                ? 5
                : intent.getIntExtra(EXTRA_CAMERA_INTERVAL_SECONDS, 5);
        cameraIntervalSeconds = Math.max(1, cameraIntervalSeconds);
        boolean waitForUnplug = intent != null
                && intent.getBooleanExtra(EXTRA_WAIT_FOR_UNPLUG, false);
        startForeground(NOTIFICATION_ID, notification("Battery trial armed: " + mode));
        startTrial(
                mode.trim().toLowerCase(Locale.US),
                durationSeconds,
                sampleIntervalSeconds,
                cameraIntervalSeconds,
                waitForUnplug);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopTrial("service destroyed");
        super.onDestroy();
    }

    private void startTrial(
            String mode,
            int durationSeconds,
            int sampleIntervalSeconds,
            int cameraIntervalSeconds,
            boolean waitForUnplug) {
        stopTrial("replaced");
        running.set(true);
        UploadSupport.writeEvidence(this, EVIDENCE_FILE,
                "armed=" + timestamp()
                        + "\nmode=" + mode
                        + "\nduration_seconds=" + durationSeconds
                        + "\nsample_interval_seconds=" + sampleIntervalSeconds
                        + "\ncamera_interval_seconds=" + cameraIntervalSeconds
                        + "\nwait_for_unplug=" + waitForUnplug
                        + "\n" + batterySnapshot("armed"));
        writeSampleHeader();
        acquireWakeLock();
        trialThread = new Thread(
                () -> runTrial(
                        mode,
                        durationSeconds,
                        sampleIntervalSeconds,
                        cameraIntervalSeconds,
                        waitForUnplug),
                "rogbid-battery-" + mode);
        trialThread.start();
    }

    private void runTrial(
            String mode,
            int durationSeconds,
            int sampleIntervalSeconds,
            int cameraIntervalSeconds,
            boolean waitForUnplug) {
        try {
            if (waitForUnplug) {
                waitUntilUnplugged();
            }
            if (!running.get()) {
                return;
            }

            long start = System.currentTimeMillis();
            long end = start + durationSeconds * 1000L;
            UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                    "trial_started=" + timestamp()
                            + "\n" + batterySnapshot("trial_start"));

            BatterySampler sampler = new BatterySampler(start, end, sampleIntervalSeconds);
            sampler.start();
            try {
                if ("dual_pcm".equals(mode)) {
                    runDualPcmTrial(end);
                } else if ("dual_camera".equals(mode)) {
                    runDualCameraTrial(end, cameraIntervalSeconds);
                } else if ("aac".equals(mode)) {
                    runAacTrial(end);
                } else {
                    runIdleTrial(end);
                }
            } finally {
                sampler.finish();
            }
            UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                    "completed=" + timestamp()
                            + "\n" + batterySnapshot("end"));
        } catch (Exception error) {
            UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                    "ERROR=" + error.getClass().getSimpleName()
                            + ": " + error.getMessage()
                            + "\n" + batterySnapshot("error"));
            Log.e(TAG, "battery trial failed", error);
        } finally {
            running.set(false);
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
        }
    }

    private void runIdleTrial(long endMs) throws InterruptedException {
        while (running.get() && System.currentTimeMillis() < endMs) {
            Thread.sleep(1000);
        }
    }

    private void runAacTrial(long endMs) throws Exception {
        File output = new File(getFilesDir(), "battery-mic-aac.m4a");
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioSamplingRate(SAMPLE_RATE);
        mediaRecorder.setAudioEncodingBitRate(64000);
        mediaRecorder.setOutputFile(output.getAbsolutePath());
        mediaRecorder.prepare();
        mediaRecorder.start();
        UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                "aac_file=" + output.getName() + "\naac_started=" + timestamp());
        try {
            int maxAmplitude = 0;
            while (running.get() && System.currentTimeMillis() < endMs) {
                Thread.sleep(1000);
                maxAmplitude = Math.max(maxAmplitude, mediaRecorder.getMaxAmplitude());
            }
            mediaRecorder.stop();
            UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                    "aac_bytes=" + output.length()
                            + "\naac_max_amplitude=" + maxAmplitude);
        } finally {
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    private void runDualPcmTrial(long endMs) throws Exception {
        AudioDeviceInfo[] inputs = audioInputs();
        UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                "audio_input_count=" + inputs.length);
        PcmRecorder[] recorders = new PcmRecorder[Math.min(2, inputs.length)];
        try {
            for (int i = 0; i < recorders.length; i++) {
                recorders[i] = new PcmRecorder(this, i, inputs[i], endMs, running);
                recorders[i].start();
            }
            for (PcmRecorder recorder : recorders) {
                if (recorder != null) {
                    recorder.join();
                }
            }
        } finally {
            running.set(false);
        }
        for (PcmRecorder recorder : recorders) {
            if (recorder != null) {
                UploadSupport.appendEvidence(this, EVIDENCE_FILE, recorder.summary());
            }
        }
    }

    private void runDualCameraTrial(long endMs, int cameraIntervalSeconds) throws Exception {
        int cameraCount = Camera.getNumberOfCameras();
        int captureCount = Math.min(2, cameraCount);
        File cameraDir = new File(getFilesDir(), CAMERA_DIR);
        clearDirectory(cameraDir);
        if (!cameraDir.mkdirs() && !cameraDir.isDirectory()) {
            throw new IOException("failed to create " + cameraDir.getAbsolutePath());
        }

        UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                "camera_count=" + cameraCount
                        + "\ncamera_capture_count=" + captureCount
                        + "\ncamera_interval_seconds=" + cameraIntervalSeconds
                        + "\ncamera_dir=" + CAMERA_DIR);

        int[] frames = new int[captureCount];
        int[] failures = new int[captureCount];
        long[] bytes = new long[captureCount];
        String firstError = "";
        int cycle = 0;
        long nextCycle = System.currentTimeMillis();
        long intervalMs = cameraIntervalSeconds * 1000L;

        while (running.get() && System.currentTimeMillis() < endMs) {
            for (int cameraId = 0; cameraId < captureCount
                    && running.get()
                    && System.currentTimeMillis() < endMs; cameraId++) {
                File output = new File(cameraDir, String.format(
                        Locale.US,
                        "camera-%d-frame-%04d.jpg",
                        cameraId,
                        cycle));
                try {
                    captureStill(cameraId, output);
                    frames[cameraId]++;
                    bytes[cameraId] += output.length();
                } catch (Exception error) {
                    failures[cameraId]++;
                    if (firstError.isEmpty()) {
                        firstError = "camera_" + cameraId + "_frame_" + cycle + ": "
                                + error.getClass().getSimpleName() + ": " + error.getMessage();
                    }
                    Log.e(TAG, "camera capture failed", error);
                }
            }
            cycle++;
            nextCycle += intervalMs;
            long sleepMs = nextCycle - System.currentTimeMillis();
            while (running.get() && sleepMs > 0 && System.currentTimeMillis() < endMs) {
                Thread.sleep(Math.min(500, sleepMs));
                sleepMs = nextCycle - System.currentTimeMillis();
            }
            if (sleepMs < -intervalMs) {
                nextCycle = System.currentTimeMillis();
            }
        }

        StringBuilder summary = new StringBuilder();
        summary.append("camera_cycles=").append(cycle);
        long totalBytes = 0;
        int totalFrames = 0;
        int totalFailures = 0;
        for (int i = 0; i < captureCount; i++) {
            totalBytes += bytes[i];
            totalFrames += frames[i];
            totalFailures += failures[i];
            summary.append("\ncamera_").append(i).append("_frames=").append(frames[i])
                    .append("\ncamera_").append(i).append("_bytes=").append(bytes[i])
                    .append("\ncamera_").append(i).append("_failures=").append(failures[i]);
        }
        summary.append("\ncamera_total_frames=").append(totalFrames)
                .append("\ncamera_total_bytes=").append(totalBytes)
                .append("\ncamera_total_failures=").append(totalFailures);
        if (!firstError.isEmpty()) {
            summary.append("\ncamera_first_error=").append(firstError);
        }
        UploadSupport.appendEvidence(this, EVIDENCE_FILE, summary.toString());
    }

    private void captureStill(int cameraId, File output) throws Exception {
        Camera camera = null;
        SurfaceTexture texture = null;
        try {
            camera = Camera.open(cameraId);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = chooseSmallest(parameters.getSupportedPictureSizes());
            if (size != null) {
                parameters.setPictureSize(size.width, size.height);
            }
            parameters.setJpegQuality(80);
            camera.setParameters(parameters);

            texture = new SurfaceTexture(110 + cameraId);
            camera.setPreviewTexture(texture);
            camera.startPreview();
            Thread.sleep(800);

            CountDownLatch latch = new CountDownLatch(1);
            final Exception[] failure = new Exception[1];
            camera.takePicture(null, null, (data, ignored) -> {
                try (FileOutputStream stream = new FileOutputStream(output, false)) {
                    if (data == null || data.length == 0) {
                        throw new IOException("camera " + cameraId + " returned no JPEG data");
                    }
                    stream.write(data);
                } catch (Exception error) {
                    failure[0] = error;
                } finally {
                    latch.countDown();
                }
            });
            if (!latch.await(8, TimeUnit.SECONDS)) {
                throw new IOException("camera " + cameraId + " timed out");
            }
            if (failure[0] != null) {
                throw failure[0];
            }
        } finally {
            if (camera != null) {
                try {
                    camera.stopPreview();
                } catch (RuntimeException ignored) {
                    // Some Android 9 camera HALs throw if preview already stopped.
                }
                camera.release();
            }
            if (texture != null) {
                texture.release();
            }
        }
    }

    private Camera.Size chooseSmallest(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        Camera.Size best = sizes.get(0);
        for (Camera.Size size : sizes) {
            if ((long) size.width * size.height < (long) best.width * best.height) {
                best = size;
            }
        }
        return best;
    }

    private void clearDirectory(File directory) {
        if (!directory.exists()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    clearDirectory(file);
                }
                if (!file.delete()) {
                    Log.w(TAG, "failed to delete " + file.getAbsolutePath());
                }
            }
        }
        if (!directory.delete()) {
            Log.w(TAG, "failed to delete " + directory.getAbsolutePath());
        }
    }

    private AudioDeviceInfo[] audioInputs() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null || Build.VERSION.SDK_INT < 23) {
            return new AudioDeviceInfo[0];
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
    }

    private void waitUntilUnplugged() throws InterruptedException {
        while (running.get()) {
            BatteryState state = batteryState();
            if (state == null || state.plugged == 0) {
                UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                        "unplugged=" + timestamp()
                                + "\n" + batterySnapshot("unplug"));
                return;
            }
            Thread.sleep(1000);
        }
    }

    private void stopTrial(String reason) {
        running.set(false);
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (RuntimeException ignored) {
                // Recorder may already be stopped or may not have enough data.
            }
            mediaRecorder.release();
            mediaRecorder = null;
        }
        if (trialThread != null && trialThread != Thread.currentThread()) {
            try {
                trialThread.join(1500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        trialThread = null;
        releaseWakeLock();
        UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                "stopped=" + timestamp() + "\nstop_reason=" + reason);
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager == null) {
                UploadSupport.appendEvidence(this, EVIDENCE_FILE, "wake_lock=unavailable");
                return;
            }
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "RogbidValidation:BatteryTrial");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                    "wake_lock_acquired=" + timestamp());
        } catch (RuntimeException error) {
            UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                    "wake_lock_error=" + error.getClass().getSimpleName()
                            + ": " + error.getMessage());
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) {
            return;
        }
        try {
            if (wakeLock.isHeld()) {
                wakeLock.release();
                UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                        "wake_lock_released=" + timestamp());
            }
        } catch (RuntimeException error) {
            UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                    "wake_lock_release_error=" + error.getClass().getSimpleName()
                            + ": " + error.getMessage());
        } finally {
            wakeLock = null;
        }
    }

    private String batterySnapshot(String prefix) {
        BatteryState battery = batteryState();
        if (battery == null) {
            return prefix + "_battery=unavailable";
        }
        return prefix + "_battery_level=" + battery.level
                + "\n" + prefix + "_battery_scale=" + battery.scale
                + "\n" + prefix + "_battery_voltage_mv=" + battery.voltage
                + "\n" + prefix + "_battery_temp_tenths_c=" + battery.temperature
                + "\n" + prefix + "_battery_plugged=" + battery.plugged
                + "\n" + prefix + "_battery_status=" + battery.status;
    }

    private BatteryState batteryState() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) {
            return null;
        }
        return new BatteryState(
                battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1),
                battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1),
                battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1));
    }

    private void writeSampleHeader() {
        File output = new File(getFilesDir(), SAMPLE_FILE);
        String header = "timestamp,elapsed_seconds,level,scale,voltage_mv,temp_tenths_c,plugged,status,"
                + "network_connected,active_network_type,has_wifi_transport\n";
        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            stream.write(header.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                    "sample_header_error=" + error.getMessage());
        }
    }

    private void appendSample(long startMs) {
        BatteryState battery = batteryState();
        if (battery == null) {
            return;
        }
        NetworkState network = networkState();
        long elapsed = Math.max(0, (System.currentTimeMillis() - startMs) / 1000L);
        String line = timestamp()
                + "," + elapsed
                + "," + battery.level
                + "," + battery.scale
                + "," + battery.voltage
                + "," + battery.temperature
                + "," + battery.plugged
                + "," + battery.status
                + "," + network.connected
                + "," + network.activeType
                + "," + network.hasWifiTransport
                + "\n";
        try (FileOutputStream stream = new FileOutputStream(new File(getFilesDir(), SAMPLE_FILE), true)) {
            stream.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            UploadSupport.appendEvidence(this, EVIDENCE_FILE,
                    "sample_write_error=" + error.getMessage());
        }
    }

    private NetworkState networkState() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) {
            return NetworkState.unavailable();
        }
        boolean connected = false;
        int activeType = -1;
        boolean hasWifiTransport = false;

        NetworkInfo active = manager.getActiveNetworkInfo();
        if (active != null) {
            connected = active.isConnected();
            activeType = active.getType();
            hasWifiTransport = active.getType() == ConnectivityManager.TYPE_WIFI;
        }

        if (Build.VERSION.SDK_INT >= 21) {
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    hasWifiTransport = true;
                    break;
                }
            }
        }
        return new NetworkState(connected, activeType, hasWifiTransport);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Rogbid battery trial",
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Rogbid battery trial")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private final class BatterySampler extends Thread {
        private final long startMs;
        private final long endMs;
        private final long intervalMs;
        private final AtomicBoolean sampling = new AtomicBoolean(true);

        BatterySampler(long startMs, long endMs, int intervalSeconds) {
            super("rogbid-battery-sampler");
            this.startMs = startMs;
            this.endMs = endMs;
            this.intervalMs = intervalSeconds * 1000L;
        }

        @Override
        public void run() {
            long next = System.currentTimeMillis();
            while (running.get() && sampling.get() && System.currentTimeMillis() <= endMs) {
                long now = System.currentTimeMillis();
                if (now >= next) {
                    appendSample(startMs);
                    next = now + intervalMs;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            appendSample(startMs);
        }

        void finish() throws InterruptedException {
            sampling.set(false);
            join(2000);
        }
    }

    private static final class BatteryState {
        final int level;
        final int scale;
        final int voltage;
        final int temperature;
        final int plugged;
        final int status;

        BatteryState(int level, int scale, int voltage, int temperature, int plugged, int status) {
            this.level = level;
            this.scale = scale;
            this.voltage = voltage;
            this.temperature = temperature;
            this.plugged = plugged;
            this.status = status;
        }
    }

    private static final class NetworkState {
        final boolean connected;
        final int activeType;
        final boolean hasWifiTransport;

        NetworkState(boolean connected, int activeType, boolean hasWifiTransport) {
            this.connected = connected;
            this.activeType = activeType;
            this.hasWifiTransport = hasWifiTransport;
        }

        static NetworkState unavailable() {
            return new NetworkState(false, -1, false);
        }
    }

    private static final class PcmRecorder extends Thread {
        private final Context context;
        private final int index;
        private final AudioDeviceInfo device;
        private final long endMs;
        private final AtomicBoolean running;
        private long bytes;
        private int maxAmplitude;
        private int zeroReads;
        private int negativeReads;
        private int lastNegativeRead;
        private boolean preferred;
        private String error = "";

        PcmRecorder(
                Context context,
                int index,
                AudioDeviceInfo device,
                long endMs,
                AtomicBoolean running) {
            super("rogbid-pcm-" + index);
            this.context = context;
            this.index = index;
            this.device = device;
            this.endMs = endMs;
            this.running = running;
        }

        @Override
        public void run() {
            File output = new File(context.getFilesDir(), "battery-mic-input-" + index + ".pcm");
            int minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) {
                error = "bad AudioRecord buffer size " + minBuffer;
                return;
            }

            int bufferSize = Math.max(minBuffer, 4096);
            AudioRecord recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                error = "AudioRecord did not initialize";
                return;
            }

            try (FileOutputStream stream = new FileOutputStream(output, false)) {
                if (Build.VERSION.SDK_INT >= 23) {
                    preferred = recorder.setPreferredDevice(device);
                }
                byte[] buffer = new byte[bufferSize];
                recorder.startRecording();
                while (running.get() && System.currentTimeMillis() < endMs) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        stream.write(buffer, 0, read);
                        bytes += read;
                        maxAmplitude = Math.max(maxAmplitude, maxPcm16(buffer, read));
                    } else if (read == 0) {
                        zeroReads++;
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            error = "interrupted";
                            break;
                        }
                    } else {
                        negativeReads++;
                        lastNegativeRead = read;
                        error = "AudioRecord.read returned " + read;
                        break;
                    }
                }
                recorder.stop();
            } catch (Exception captureError) {
                error = captureError.getClass().getSimpleName() + ": " + captureError.getMessage();
            } finally {
                recorder.release();
            }
        }

        String summary() {
            StringBuilder builder = new StringBuilder();
            builder.append("pcm_input_").append(index)
                    .append("_id=").append(device.getId())
                    .append("\npcm_input_").append(index)
                    .append("_type=").append(device.getType())
                    .append("\npcm_input_").append(index)
                    .append("_name=").append(device.getProductName())
                    .append("\npcm_input_").append(index)
                    .append("_preferred=").append(preferred)
                    .append("\npcm_input_").append(index)
                    .append("_bytes=").append(bytes)
                    .append("\npcm_input_").append(index)
                    .append("_zero_reads=").append(zeroReads)
                    .append("\npcm_input_").append(index)
                    .append("_negative_reads=").append(negativeReads)
                    .append("\npcm_input_").append(index)
                    .append("_last_negative_read=").append(lastNegativeRead)
                    .append("\npcm_input_").append(index)
                    .append("_max_amplitude=").append(maxAmplitude);
            if (!error.isEmpty()) {
                builder.append("\npcm_input_").append(index).append("_error=").append(error);
            }
            return builder.toString();
        }

        private int maxPcm16(byte[] buffer, int byteCount) {
            int max = 0;
            for (int i = 0; i + 1 < byteCount; i += 2) {
                int sample = (short) (((buffer[i + 1] & 0xff) << 8) | (buffer[i] & 0xff));
                max = Math.max(max, Math.abs(sample));
            }
            return max;
        }
    }
}
