// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package org.solpbc.rogbidhello;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
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

public final class MainActivity extends Activity {
    private static final String TAG = "RogbidHello";
    private static final int PERMISSION_REQUEST = 7;

    private int tapCount = 0;
    private TextView status;
    private Button mediaButton;
    private Button uploadButton;
    private Button deferredUploadButton;
    private String uploadUrl = "https://192.168.4.27:8443/upload";
    private String uploadCertSha256 = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uploadUrl = stringExtra("upload_url", uploadUrl);
        uploadCertSha256 = stringExtra("upload_cert_sha256", uploadCertSha256);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(20, 28, 20, 20);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("Rogbid media");
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setId(R.id.hello_status);
        status.setContentDescription("hello_status");
        status.setText("Ready on Rogbid Model X");
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, 16, 0, 16);
        root.addView(status, statusParams);

        mediaButton = new Button(this);
        mediaButton.setId(R.id.media_button);
        mediaButton.setContentDescription("media_button");
        mediaButton.setText("Run media test");
        mediaButton.setTextSize(16);
        mediaButton.setOnClickListener(view -> runMediaTest());
        root.addView(mediaButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        uploadButton = new Button(this);
        uploadButton.setId(R.id.upload_button);
        uploadButton.setContentDescription("upload_button");
        uploadButton.setText("Upload test");
        uploadButton.setTextSize(16);
        uploadButton.setOnClickListener(view -> runUploadTest());
        root.addView(uploadButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        deferredUploadButton = new Button(this);
        deferredUploadButton.setId(R.id.deferred_upload_button);
        deferredUploadButton.setContentDescription("deferred_upload_button");
        deferredUploadButton.setText("Deferred upload");
        deferredUploadButton.setTextSize(16);
        deferredUploadButton.setOnClickListener(view -> enqueueDeferredUpload());
        root.addView(deferredUploadButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button qrButton = new Button(this);
        qrButton.setId(R.id.qr_button);
        qrButton.setContentDescription("qr_button");
        qrButton.setText("QR scan");
        qrButton.setTextSize(16);
        qrButton.setOnClickListener(view -> startActivity(new Intent(this, QrProbeActivity.class)));
        root.addView(qrButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button helloButton = new Button(this);
        helloButton.setId(R.id.hello_button);
        helloButton.setContentDescription("hello_button");
        helloButton.setText("Tap hello");
        helloButton.setTextSize(16);
        helloButton.setOnClickListener(view -> recordTap());
        root.addView(helloButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        requestMediaPermissionsIfNeeded();
        writeEvidence("tap-evidence.txt", "launched");
        writeEvidence("media-evidence.txt", "launched");
        writeEvidence("upload-evidence.txt", "launched");
        writeEvidence(UploadWorker.EVIDENCE_FILE, "launched");
    }

    private String stringExtra(String name, String fallback) {
        String value = getIntent().getStringExtra(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private void requestMediaPermissionsIfNeeded() {
        if (hasMediaPermissions()) {
            return;
        }
        requestPermissions(
                new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                PERMISSION_REQUEST);
    }

    private boolean hasMediaPermissions() {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void runMediaTest() {
        if (!hasMediaPermissions()) {
            status.setText("Media permission needed");
            writeEvidence("media-evidence.txt", "ERROR=missing media permission");
            requestMediaPermissionsIfNeeded();
            return;
        }
        if (mediaButton != null) {
            mediaButton.setEnabled(false);
        }
        status.setText("Media test running");
        writeEvidence("media-evidence.txt", "media test started");

        new Thread(() -> {
            String result = runMediaTestBlocking();
            runOnUiThread(() -> {
                status.setText(result.contains("ERROR") ? "Media test failed" : "Media test passed");
                if (mediaButton != null) {
                    mediaButton.setEnabled(true);
                }
            });
        }, "rogbid-media-test").start();
    }

    private void runUploadTest() {
        if (uploadCertSha256.trim().isEmpty()) {
            status.setText("Upload pin needed");
            writeEvidence("upload-evidence.txt", "ERROR=missing upload_cert_sha256");
            return;
        }
        if (uploadButton != null) {
            uploadButton.setEnabled(false);
        }
        status.setText("Upload test running");
        writeEvidence("upload-evidence.txt", "upload test started");

        new Thread(() -> {
            String result = runUploadTestBlocking();
            runOnUiThread(() -> {
                status.setText(result.contains("ERROR") ? "Upload test failed" : "Upload test passed");
                if (uploadButton != null) {
                    uploadButton.setEnabled(true);
                }
            });
        }, "rogbid-upload-test").start();
    }

    private void enqueueDeferredUpload() {
        if (uploadCertSha256.trim().isEmpty()) {
            status.setText("Deferred pin needed");
            writeEvidence(UploadWorker.EVIDENCE_FILE, "ERROR=missing upload_cert_sha256");
            return;
        }

        Data input = new Data.Builder()
                .putString(UploadWorker.KEY_UPLOAD_URL, uploadUrl)
                .putString(UploadWorker.KEY_CERT_SHA256, uploadCertSha256)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UploadWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build();

        writeEvidence(UploadWorker.EVIDENCE_FILE,
                "enqueued=" + timestamp()
                        + "\nupload_url=" + uploadUrl
                        + "\ncert_pin=" + UploadSupport.normalizeFingerprint(uploadCertSha256));
        WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
                "rogbid-deferred-upload",
                ExistingWorkPolicy.REPLACE,
                request);
        status.setText("Deferred upload queued");
    }

    private String runUploadTestBlocking() {
        StringBuilder evidence = new StringBuilder();
        evidence.append("started=").append(timestamp()).append('\n');
        evidence.append("upload_url=").append(uploadUrl).append('\n');
        evidence.append("cert_pin=").append(UploadSupport.normalizeFingerprint(uploadCertSha256)).append('\n');

        try {
            UploadSupport.UploadResult result =
                    UploadSupport.upload(this, uploadUrl, uploadCertSha256);
            evidence.append("payload_bytes=").append(result.payloadBytes).append('\n');
            evidence.append("uploaded_files=").append(result.uploadedFiles).append('\n');
            evidence.append("http_code=").append(result.httpCode).append('\n');
            evidence.append("response=").append(result.response.replace('\n', ' ')).append('\n');
            evidence.append("completed=").append(timestamp()).append('\n');
        } catch (Exception error) {
            evidence.append("ERROR=").append(error.getClass().getSimpleName())
                    .append(": ").append(error.getMessage()).append('\n');
            Log.e(TAG, "upload test failed", error);
        }

        String text = evidence.toString();
        writeEvidence("upload-evidence.txt", text);
        Log.i(TAG, text);
        return text;
    }

    private void recordTap() {
        tapCount += 1;
        String message = "Hello tapped " + tapCount + " time" + (tapCount == 1 ? "" : "s");
        status.setText(message);
        writeEvidence("tap-evidence.txt", message);
        Log.i(TAG, message);
    }

    private String runMediaTestBlocking() {
        StringBuilder evidence = new StringBuilder();
        evidence.append("started=").append(timestamp()).append('\n');
        evidence.append("camera_count=").append(Camera.getNumberOfCameras()).append('\n');
        AudioDeviceInfo[] audioInputs = getAudioInputs();
        appendAudioInputs(evidence, audioInputs);

        try {
            int count = Camera.getNumberOfCameras();
            for (int id = 0; id < count && id < 2; id++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(id, info);
                evidence.append("camera_").append(id).append("=")
                        .append("facing:").append(cameraFacingName(info.facing))
                        .append(",orientation:").append(info.orientation)
                        .append('\n');
                File image = new File(getFilesDir(), "camera-" + id + ".jpg");
                captureStill(id, image);
                evidence.append("camera_").append(id).append("_jpg=")
                        .append(image.length()).append(" bytes\n");
            }

            File audio = new File(getFilesDir(), "mic.m4a");
            int maxAmplitude = recordAudio(audio, 3000);
            evidence.append("mic_m4a=").append(audio.length()).append(" bytes\n");
            evidence.append("mic_max_amplitude=").append(maxAmplitude).append('\n');

            for (int i = 0; i < audioInputs.length && i < 2; i++) {
                File pcm = new File(getFilesDir(), "mic-input-" + i + ".pcm");
                AudioCaptureResult capture = recordAudioInput(audioInputs[i], pcm, 1200);
                evidence.append("audio_input_").append(i).append("_pcm=")
                        .append(capture.bytes).append(" bytes")
                        .append(",max:").append(capture.maxAmplitude)
                        .append(",preferred:").append(capture.preferred)
                        .append('\n');
            }
            evidence.append("completed=").append(timestamp()).append('\n');
        } catch (Exception error) {
            evidence.append("ERROR=").append(error.getClass().getSimpleName())
                    .append(": ").append(error.getMessage()).append('\n');
            Log.e(TAG, "media test failed", error);
        }

        String text = evidence.toString();
        writeEvidence("media-evidence.txt", text);
        Log.i(TAG, text);
        return text;
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
                camera.setParameters(parameters);
            }
            texture = new SurfaceTexture(11 + cameraId);
            camera.setPreviewTexture(texture);
            camera.startPreview();
            Thread.sleep(800);

            CountDownLatch latch = new CountDownLatch(1);
            final Exception[] failure = new Exception[1];
            camera.takePicture(null, null, (data, ignored) -> {
                try (FileOutputStream stream = new FileOutputStream(output, false)) {
                    stream.write(data);
                } catch (IOException error) {
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

    private String cameraFacingName(int facing) {
        if (facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return "front";
        }
        if (facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
            return "back";
        }
        return "unknown-" + facing;
    }

    private int recordAudio(File output, long durationMs) throws Exception {
        MediaRecorder recorder = new MediaRecorder();
        int maxAmplitude = 0;
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setOutputFile(output.getAbsolutePath());
            recorder.prepare();
            recorder.start();

            long end = System.currentTimeMillis() + durationMs;
            while (System.currentTimeMillis() < end) {
                Thread.sleep(250);
                maxAmplitude = Math.max(maxAmplitude, recorder.getMaxAmplitude());
            }
            recorder.stop();
            return maxAmplitude;
        } finally {
            recorder.release();
        }
    }

    private AudioCaptureResult recordAudioInput(
            AudioDeviceInfo device,
            File output,
            long durationMs) throws Exception {
        int sampleRate = 16000;
        int minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            throw new IOException("bad AudioRecord buffer size " + minBuffer);
        }

        int bufferSize = Math.max(minBuffer, 4096);
        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IOException("AudioRecord did not initialize");
        }

        boolean preferred = recorder.setPreferredDevice(device);
        byte[] buffer = new byte[bufferSize];
        int maxAmplitude = 0;

        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            recorder.startRecording();
            long end = System.currentTimeMillis() + durationMs;
            while (System.currentTimeMillis() < end) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    stream.write(buffer, 0, read);
                    maxAmplitude = Math.max(maxAmplitude, maxPcm16(buffer, read));
                }
            }
            recorder.stop();
        } finally {
            recorder.release();
        }

        return new AudioCaptureResult(output.length(), maxAmplitude, preferred);
    }

    private int maxPcm16(byte[] buffer, int byteCount) {
        int max = 0;
        for (int i = 0; i + 1 < byteCount; i += 2) {
            int sample = (short) (((buffer[i + 1] & 0xff) << 8) | (buffer[i] & 0xff));
            max = Math.max(max, Math.abs(sample));
        }
        return max;
    }

    private AudioDeviceInfo[] getAudioInputs() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null || android.os.Build.VERSION.SDK_INT < 23) {
            return new AudioDeviceInfo[0];
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
    }

    private void appendAudioInputs(StringBuilder evidence, AudioDeviceInfo[] devices) {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            evidence.append("audio_inputs=unavailable\n");
            return;
        }
        evidence.append("audio_input_count=").append(devices.length).append('\n');
        for (int i = 0; i < devices.length; i++) {
            evidence.append("audio_input_").append(i)
                    .append("=id:").append(devices[i].getId())
                    .append(",type:").append(devices[i].getType())
                    .append("(").append(audioTypeName(devices[i].getType())).append(")")
                    .append(",name:").append(devices[i].getProductName())
                    .append('\n');
        }
    }

    private String audioTypeName(int type) {
        if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            return "BUILTIN_MIC";
        }
        if (type == AudioDeviceInfo.TYPE_USB_DEVICE) {
            return "USB_DEVICE";
        }
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
            return "WIRED_HEADSET";
        }
        return "TYPE_" + type;
    }

    private void writeEvidence(String name, String message) {
        File evidence = new File(getFilesDir(), name);
        try (FileOutputStream output = new FileOutputStream(evidence, false)) {
            output.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            Log.e(TAG, "failed to write evidence", error);
        }
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static final class AudioCaptureResult {
        final long bytes;
        final int maxAmplitude;
        final boolean preferred;

        AudioCaptureResult(long bytes, int maxAmplitude, boolean preferred) {
            this.bytes = bytes;
            this.maxAmplitude = maxAmplitude;
            this.preferred = preferred;
        }
    }
}
