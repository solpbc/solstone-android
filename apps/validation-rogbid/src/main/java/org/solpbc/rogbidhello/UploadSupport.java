// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package org.solpbc.rogbidhello;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.json.JSONArray;
import org.json.JSONObject;

final class UploadSupport {
    private static final String TAG = "RogbidUpload";

    private UploadSupport() {
    }

    static UploadResult upload(Context context, String uploadUrl, String certFingerprint)
            throws Exception {
        HttpsURLConnection connection = null;
        try {
            JSONObject payload = buildUploadPayload(context, uploadUrl);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL(uploadUrl);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setSSLSocketFactory(createPinnedSocketFactory(certFingerprint));
            connection.setHostnameVerifier(createPinnedHostnameVerifier(certFingerprint));
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-Rogbid-Spike", "upload");
            connection.setFixedLengthStreamingMode(body.length);

            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int httpCode = connection.getResponseCode();
            String response = readResponse(connection, httpCode);
            int uploadedFiles = payload.getJSONArray("files").length();
            if (httpCode < 200 || httpCode > 299) {
                throw new IOException("unexpected HTTP " + httpCode + ": " + response);
            }
            return new UploadResult(body.length, uploadedFiles, httpCode, response);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static JSONObject buildUploadPayload(Context context, String uploadUrl) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("sent_at", timestamp());
        payload.put("device_model", android.os.Build.MODEL);
        payload.put("device_manufacturer", android.os.Build.MANUFACTURER);
        payload.put("android_release", android.os.Build.VERSION.RELEASE);
        payload.put("upload_url", uploadUrl);
        payload.put("media_evidence", readText(evidenceFile(context, "media-evidence.txt")));

        JSONArray files = new JSONArray();
        appendFile(context, files, "camera-0.jpg");
        appendFile(context, files, "camera-1.jpg");
        appendFile(context, files, "mic.m4a");
        appendFile(context, files, "mic-input-0.pcm");
        appendFile(context, files, "mic-input-1.pcm");
        payload.put("files", files);
        return payload;
    }

    static void writeEvidence(Context context, String name, String message) {
        File evidence = evidenceFile(context, name);
        try (FileOutputStream output = new FileOutputStream(evidence, false)) {
            output.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            Log.e(TAG, "failed to write evidence", error);
        }
    }

    static void appendEvidence(Context context, String name, String message) {
        File evidence = evidenceFile(context, name);
        try (FileOutputStream output = new FileOutputStream(evidence, true)) {
            output.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            Log.e(TAG, "failed to append evidence", error);
        }
    }

    static File evidenceFile(Context context, String name) {
        return new File(context.getFilesDir(), name);
    }

    static String normalizeFingerprint(String fingerprint) {
        return fingerprint.replace(":", "").replaceAll("\\s+", "").toLowerCase(Locale.US);
    }

    static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static void appendFile(Context context, JSONArray files, String name) throws Exception {
        File file = evidenceFile(context, name);
        if (!file.isFile()) {
            return;
        }
        byte[] bytes = readBytes(file);
        JSONObject item = new JSONObject();
        item.put("name", name);
        item.put("bytes", bytes.length);
        item.put("sha256", sha256Hex(bytes));
        item.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
        files.put(item);
    }

    private static String readText(File file) throws IOException {
        if (!file.isFile()) {
            return "";
        }
        return new String(readBytes(file), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static SSLSocketFactory createPinnedSocketFactory(String expectedFingerprint)
            throws Exception {
        final String expected = normalizeFingerprint(expectedFingerprint);
        TrustManager[] trustManagers = new TrustManager[] {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // This client never accepts incoming TLS connections.
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    if (chain == null || chain.length == 0) {
                        throw new CertificateException("empty server certificate chain");
                    }
                    try {
                        if (!certificateMatches(chain[0], expected)) {
                            throw new CertificateException("server certificate pin mismatch");
                        }
                    } catch (Exception error) {
                        if (error instanceof CertificateException) {
                            throw (CertificateException) error;
                        }
                        throw new CertificateException(error);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new SecureRandom());
        return context.getSocketFactory();
    }

    private static HostnameVerifier createPinnedHostnameVerifier(String expectedFingerprint) {
        final String expected = normalizeFingerprint(expectedFingerprint);
        return (String hostname, SSLSession session) -> {
            try {
                Certificate[] certificates = session.getPeerCertificates();
                return certificates.length > 0 && certificateMatches(certificates[0], expected);
            } catch (SSLPeerUnverifiedException error) {
                return false;
            } catch (Exception error) {
                Log.e(TAG, "hostname pin check failed", error);
                return false;
            }
        };
    }

    private static boolean certificateMatches(Certificate certificate, String expectedFingerprint)
            throws Exception {
        return sha256Hex(certificate.getEncoded()).equals(expectedFingerprint);
    }

    private static String readResponse(HttpsURLConnection connection, int httpCode)
            throws IOException {
        InputStream stream = httpCode >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static final class UploadResult {
        final int payloadBytes;
        final int uploadedFiles;
        final int httpCode;
        final String response;

        UploadResult(int payloadBytes, int uploadedFiles, int httpCode, String response) {
            this.payloadBytes = payloadBytes;
            this.uploadedFiles = uploadedFiles;
            this.httpCode = httpCode;
            this.response = response;
        }
    }
}
