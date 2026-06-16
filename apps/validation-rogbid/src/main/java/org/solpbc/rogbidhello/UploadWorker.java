// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package org.solpbc.rogbidhello;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class UploadWorker extends Worker {
    static final String KEY_UPLOAD_URL = "upload_url";
    static final String KEY_CERT_SHA256 = "upload_cert_sha256";
    static final String EVIDENCE_FILE = "deferred-upload-evidence.txt";

    private static final String TAG = "RogbidUploadWorker";

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String uploadUrl = getInputData().getString(KEY_UPLOAD_URL);
        String certSha256 = getInputData().getString(KEY_CERT_SHA256);
        int attempt = getRunAttemptCount();

        if (uploadUrl == null || uploadUrl.trim().isEmpty()
                || certSha256 == null || certSha256.trim().isEmpty()) {
            UploadSupport.appendEvidence(context, EVIDENCE_FILE,
                    "attempt=" + attempt
                            + "\nfailed=" + UploadSupport.timestamp()
                            + "\nretrying=false"
                            + "\nERROR=missing upload worker input");
            return Result.failure();
        }

        UploadSupport.appendEvidence(context, EVIDENCE_FILE,
                "attempt=" + attempt
                        + "\nstarted=" + UploadSupport.timestamp()
                        + "\nupload_url=" + uploadUrl
                        + "\ncert_pin=" + UploadSupport.normalizeFingerprint(certSha256));

        try {
            UploadSupport.UploadResult result = UploadSupport.upload(context, uploadUrl, certSha256);
            UploadSupport.appendEvidence(context, EVIDENCE_FILE,
                    "payload_bytes=" + result.payloadBytes
                            + "\nuploaded_files=" + result.uploadedFiles
                            + "\nhttp_code=" + result.httpCode
                            + "\nresponse=" + result.response.replace('\n', ' ')
                            + "\nretrying=false"
                            + "\ncompleted=" + UploadSupport.timestamp());
            return Result.success();
        } catch (Exception error) {
            UploadSupport.appendEvidence(context, EVIDENCE_FILE,
                    "failed=" + UploadSupport.timestamp()
                            + "\nretrying=true"
                            + "\nERROR=" + error.getClass().getSimpleName()
                            + ": " + error.getMessage());
            Log.e(TAG, "deferred upload attempt failed", error);
            return Result.retry();
        }
    }
}
