// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package org.solpbc.rogbidhello;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QrProbeActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final String TAG = "RogbidQrProbe";
    private static final String EVIDENCE_FILE = "qr-evidence.txt";
    private static final String PL_EVIDENCE_FILE = "pl-link-evidence.txt";
    private static final int PERMISSION_REQUEST = 11;
    private static final int DEFAULT_CAMERA_ID = 0;
    private static final long DECODE_INTERVAL_MS = 350L;

    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final ExecutorService linkWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private final MultiFormatReader reader = new MultiFormatReader();
    private SurfaceView preview;
    private TextView status;
    private Camera camera;
    private int cameraId = DEFAULT_CAMERA_ID;
    private int previewWidth = 0;
    private int previewHeight = 0;
    private int frameCount = 0;
    private long startedAtMs = 0L;
    private long firstFrameAtMs = 0L;
    private long lastDecodeAttemptAtMs = 0L;
    private boolean decoded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cameraId = getIntent().getIntExtra("camera_id", DEFAULT_CAMERA_ID);
        configureReader();
        buildUi();
        writeEvidence("started=" + timestamp()
                + "\ncamera_id=" + cameraId
                + "\nstate=created");
        String debugPairLink = getIntent().getStringExtra("pl_pair_link");
        if (PlLinkClient.looksLikePairLink(debugPairLink)) {
            decoded = true;
            appendEvidence("debug_pl_pair_link=provided");
            setStatus("PL link provided - linking...");
            runPlLink(debugPairLink);
        } else if (!hasCameraPermission()) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, PERMISSION_REQUEST);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!decoded && hasCameraPermission()
                && preview != null && preview.getHolder().getSurface().isValid()) {
            openCamera(preview.getHolder());
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        linkWorker.shutdownNow();
        decoder.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!decoded && requestCode == PERMISSION_REQUEST && hasCameraPermission()
                && preview != null && preview.getHolder().getSurface().isValid()) {
            openCamera(preview.getHolder());
        } else if (!hasCameraPermission()) {
            setStatus("Camera permission needed");
            appendEvidence("ERROR=missing camera permission");
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!decoded && hasCameraPermission()) {
            openCamera(holder);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera != null) {
            restartPreview(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        closeCamera();
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera ignored) {
        frameCount += 1;
        if (firstFrameAtMs == 0L) {
            firstFrameAtMs = System.currentTimeMillis();
            appendEvidence("first_frame_ms=" + (firstFrameAtMs - startedAtMs));
            setStatus("Preview live - point side camera at QR");
        }
        if (decoded || previewWidth <= 0 || previewHeight <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDecodeAttemptAtMs < DECODE_INTERVAL_MS) {
            return;
        }
        if (!decoding.compareAndSet(false, true)) {
            return;
        }
        lastDecodeAttemptAtMs = now;
        byte[] frame = data.clone();
        int width = previewWidth;
        int height = previewHeight;
        decoder.execute(() -> decodeFrame(frame, width, height));
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(8, 8, 8, 8);

        TextView title = new TextView(this);
        title.setText("QR probe");
        title.setGravity(Gravity.CENTER);
        title.setTextSize(18);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        preview = new SurfaceView(this);
        preview.getHolder().addCallback(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f);
        previewParams.setMargins(0, 6, 0, 6);
        root.addView(preview, previewParams);

        status = new TextView(this);
        status.setId(R.id.qr_status);
        status.setContentDescription("qr_status");
        status.setText("Opening side camera...");
        status.setGravity(Gravity.CENTER);
        status.setTextSize(14);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void openCamera(SurfaceHolder holder) {
        if (camera != null) {
            return;
        }
        try {
            startedAtMs = System.currentTimeMillis();
            camera = Camera.open(cameraId);
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, info);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = choosePreviewSize(parameters.getSupportedPreviewSizes());
            if (size != null) {
                parameters.setPreviewSize(size.width, size.height);
                previewWidth = size.width;
                previewHeight = size.height;
            } else {
                Camera.Size current = parameters.getPreviewSize();
                previewWidth = current.width;
                previewHeight = current.height;
            }
            parameters.setPreviewFormat(ImageFormat.NV21);
            camera.setParameters(parameters);
            camera.setDisplayOrientation(displayOrientation(info));
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
            setStatus("Preview starting...");
            appendEvidence("camera_opened=" + timestamp()
                    + "\nfacing=" + facingName(info.facing)
                    + "\norientation=" + info.orientation
                    + "\npreview_size=" + previewWidth + "x" + previewHeight
                    + "\nzoom=" + camera.getParameters().getZoom());
        } catch (Exception error) {
            setStatus("QR camera failed");
            appendEvidence("ERROR=" + error.getClass().getSimpleName() + ": " + error.getMessage());
            Log.e(TAG, "failed to open QR camera", error);
            closeCamera();
        }
    }

    private void restartPreview(SurfaceHolder holder) {
        try {
            camera.stopPreview();
        } catch (RuntimeException ignored) {
            // Preview may already be stopped during surface changes.
        }
        try {
            camera.setPreviewDisplay(holder);
            camera.startPreview();
        } catch (IOException error) {
            setStatus("Preview restart failed");
            appendEvidence("ERROR=preview restart: " + error.getMessage());
        }
    }

    private void closeCamera() {
        if (camera == null) {
            return;
        }
        try {
            camera.setPreviewCallback(null);
            camera.stopPreview();
        } catch (RuntimeException ignored) {
            // Best-effort cleanup for this diagnostic activity.
        }
        camera.release();
        camera = null;
    }

    private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        Camera.Size best = sizes.get(0);
        long bestPixels = Math.abs((long) best.width * best.height - 640L * 480L);
        for (Camera.Size size : sizes) {
            long pixels = (long) size.width * size.height;
            long score = Math.abs(pixels - 640L * 480L);
            if (score < bestPixels) {
                best = size;
                bestPixels = score;
            }
        }
        return best;
    }

    private void decodeFrame(byte[] frame, int width, int height) {
        try {
            Result result = decode(frame, width, height);
            if (result != null) {
                decoded = true;
                String text = result.getText();
                boolean isPairLink = PlLinkClient.looksLikePairLink(text);
                runOnUiThread(() -> {
                    setStatus(isPairLink ? "PL QR decoded - linking..." : "Decoded: " + text);
                    if (isPairLink) {
                        closeCamera();
                    }
                });
                appendEvidence("decoded=" + timestamp()
                        + "\nframes_seen=" + frameCount
                        + "\nformat=" + result.getBarcodeFormat()
                        + "\npayload=" + (isPairLink ? "solstone_pair_link_redacted" : text));
                if (isPairLink) {
                    runPlLink(text);
                }
            }
        } finally {
            decoding.set(false);
        }
    }

    private void runPlLink(String pairLink) {
        String deviceLabel = PlLinkClient.defaultDeviceLabel();
        UploadSupport.writeEvidence(this, PL_EVIDENCE_FILE,
                "started=" + timestamp()
                        + "\ndevice_label=" + deviceLabel
                        + "\npair_link=solstone_pair_link_redacted");
        linkWorker.execute(() -> {
            StringBuilder evidence = new StringBuilder();
            evidence.append("started=").append(timestamp()).append('\n');
            evidence.append("device_label=").append(deviceLabel).append('\n');
            evidence.append("pair_link=solstone_pair_link_redacted\n");
            try {
                PlLinkClient.PlResult result =
                        PlLinkClient.pairAndProbe(getApplicationContext(), pairLink, deviceLabel);
                evidence.append("pair_http=").append(result.pairStatus).append('\n');
                evidence.append("pair_tls_peer_chain_pinned=")
                        .append(result.handshakePinned).append('\n');
                evidence.append("home_label=").append(result.state.homeLabel).append('\n');
                evidence.append("home_instance_id=").append(result.state.instanceId).append('\n');
                evidence.append("client_fingerprint=").append(result.state.fingerprint).append('\n');
                evidence.append("ca_fingerprint=sha256:")
                        .append(hex(result.state.caFingerprintBytes)).append('\n');
                evidence.append("direct_endpoint=").append(result.endpoint).append('\n');
                evidence.append("api_status_http=").append(result.apiStatus).append('\n');
                evidence.append("api_status_body=").append(abbreviate(result.apiBody)).append('\n');
                evidence.append("completed=").append(timestamp()).append('\n');
                UploadSupport.writeEvidence(this, PL_EVIDENCE_FILE, evidence.toString());
                appendEvidence("pl_link=success\npl_evidence=" + PL_EVIDENCE_FILE);
                runOnUiThread(() -> setStatus("PL linked: "
                        + (result.state.homeLabel.isEmpty()
                        ? result.state.instanceId
                        : result.state.homeLabel)));
            } catch (Exception error) {
                evidence.append("ERROR=").append(error.getClass().getSimpleName())
                        .append(": ").append(error.getMessage()).append('\n');
                UploadSupport.writeEvidence(this, PL_EVIDENCE_FILE, evidence.toString());
                appendEvidence("pl_link=error " + error.getClass().getSimpleName());
                Log.e(TAG, "PL link failed", error);
                runOnUiThread(() -> setStatus("PL link failed: " + error.getClass().getSimpleName()));
            }
        });
    }

    private Result decode(byte[] frame, int width, int height) {
        Result direct = tryDecode(frame, width, height);
        if (direct != null) {
            return direct;
        }
        byte[] rotated = rotateClockwise(frame, width, height);
        return tryDecode(rotated, height, width);
    }

    private Result tryDecode(byte[] frame, int width, int height) {
        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    frame,
                    width,
                    height,
                    0,
                    0,
                    width,
                    height,
                    false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            return reader.decodeWithState(bitmap);
        } catch (NotFoundException error) {
            return null;
        } finally {
            reader.reset();
        }
    }

    private byte[] rotateClockwise(byte[] data, int width, int height) {
        byte[] rotated = new byte[data.length];
        int index = 0;
        for (int x = 0; x < width; x++) {
            for (int y = height - 1; y >= 0; y--) {
                rotated[index++] = data[y * width + x];
            }
        }
        return rotated;
    }

    private void configureReader() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);
    }

    private int displayOrientation(Camera.CameraInfo info) {
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - info.orientation) % 360;
        }
        return info.orientation;
    }

    private String facingName(int facing) {
        if (facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return "front";
        }
        if (facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
            return "back";
        }
        return "unknown-" + facing;
    }

    private boolean hasCameraPermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void setStatus(String text) {
        if (status != null) {
            status.setText(text);
        }
    }

    private void writeEvidence(String message) {
        File outputFile = new File(getFilesDir(), EVIDENCE_FILE);
        try (FileOutputStream output = new FileOutputStream(outputFile, false)) {
            output.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            Log.e(TAG, "failed to write QR evidence", error);
        }
    }

    private void appendEvidence(String message) {
        File outputFile = new File(getFilesDir(), EVIDENCE_FILE);
        try (FileOutputStream output = new FileOutputStream(outputFile, true)) {
            output.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            Log.e(TAG, "failed to append QR evidence", error);
        }
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private String abbreviate(String text) {
        String compact = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
        if (compact.length() <= 500) {
            return compact;
        }
        return compact.substring(0, 500) + "...";
    }

    private String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return out.toString();
    }
}
