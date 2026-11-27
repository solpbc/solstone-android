// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pure-Java test {@link ContentProvider} running in the instrumentation test package process
 * (so it needs zero Kotlin runtime classes from the target APK) and exercising real Android
 * ContentResolver / Binder {@link ParcelFileDescriptor} + {@code Os.fstatvfs} +
 * {@link DocumentsContract} paths for both seekable ({@code rw} + read-back) and write-only
 * (pipe-backed, refusing {@code r} and {@code rw}) storage providers.
 */
public final class TestExportDocumentsProvider extends ContentProvider {
    public static final String AUTHORITY = "app.solstone.observer.phone.test.export.documents";

    private static final ConcurrentHashMap<String, CountDownLatch> WRITE_ONLY_LATCHES =
            new ConcurrentHashMap<>();
    private static final Set<String> DELETED_IDS = ConcurrentHashMap.newKeySet();

    public static Uri authorityUri() {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).build();
    }

    public static Uri documentUri(String documentId) {
        return DocumentsContract.buildDocumentUri(AUTHORITY, documentId);
    }

    public static Uri capturedUri(String documentId) {
        return DocumentsContract.buildDocumentUri(AUTHORITY, "captured/" + documentId);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File seekableFile(String docId) {
        String leaf = docId.startsWith("seekable/") ? docId.substring("seekable/".length()) : docId;
        return new File(new File(requireContext().getCacheDir(), "test-saf-docs"), leaf);
    }

    private File writeOnlyCaptureFile(String docId) {
        String leaf = docId.startsWith("writeonly/") ? docId.substring("writeonly/".length()) : docId;
        return new File(new File(requireContext().getCacheDir(), "test-saf-captured"), leaf);
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        String docId = DocumentsContract.getDocumentId(uri);
        String[] cols = projection != null
                ? projection
                : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor cursor = new MatrixCursor(cols);
        int slash = docId.lastIndexOf('/');
        String displayName = slash >= 0 ? docId.substring(slash + 1) : docId;
        long size = -1L;
        if (docId.startsWith("seekable/")) {
            File f = seekableFile(docId);
            size = f.exists() ? f.length() : 0L;
        }
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add(displayName);
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(size);
            } else if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(col)) {
                row.add(docId);
            } else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(col)) {
                row.add("application/zip");
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/zip";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        String docId = DocumentsContract.getDocumentId(uri);
        DELETED_IDS.add(docId);
        if (docId.startsWith("seekable/")) {
            return seekableFile(docId).delete() ? 1 : 0;
        }
        return 1;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Bundle call(String method, String arg, Bundle extras) {
        if ("reset".equals(method)) {
            WRITE_ONLY_LATCHES.clear();
            DELETED_IDS.clear();
            Context ctx = requireContext();
            deleteTree(new File(ctx.getCacheDir(), "test-saf-docs"));
            deleteTree(new File(ctx.getCacheDir(), "test-saf-captured"));
            return new Bundle();
        }
        if ("wasDeleted".equals(method) && arg != null) {
            Bundle out = new Bundle();
            out.putBoolean("deleted", DELETED_IDS.contains(arg));
            return out;
        }
        if ("android:deleteDocument".equals(method) || "android:removeDocument".equals(method)) {
            if (extras != null) {
                Uri targetUri = extras.getParcelable("uri");
                if (targetUri != null) {
                    delete(targetUri, null, null);
                }
            }
            return new Bundle();
        }
        return super.call(method, arg, extras);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String docId = DocumentsContract.getDocumentId(uri);
        if (docId.startsWith("captured/")) {
            String inner = docId.substring("captured/".length());
            if (inner.startsWith("writeonly/")) {
                CountDownLatch latch = WRITE_ONLY_LATCHES.get(inner);
                if (latch == null) {
                    throw new FileNotFoundException("no write-only capture for " + inner);
                }
                try {
                    if (!latch.await(10, TimeUnit.SECONDS)) {
                        throw new FileNotFoundException("write-only drain timed out for " + inner);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new FileNotFoundException("interrupted waiting for " + inner);
                }
                File captured = writeOnlyCaptureFile(inner);
                return ParcelFileDescriptor.open(captured, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            if (inner.startsWith("seekable/")) {
                return ParcelFileDescriptor.open(seekableFile(inner), ParcelFileDescriptor.MODE_READ_ONLY);
            }
            throw new FileNotFoundException("unknown captured id: " + inner);
        }

        if (docId.startsWith("seekable/")) {
            File file = seekableFile(docId);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            if (!file.exists()) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    file.createNewFile();
                } catch (IOException e) {
                    FileNotFoundException fnf = new FileNotFoundException("failed to create " + file);
                    fnf.initCause(e);
                    throw fnf;
                }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }

        if (docId.startsWith("writeonly/")) {
            if (mode != null && mode.indexOf('r') >= 0) {
                throw new FileNotFoundException("write-only provider refuses read mode: " + mode);
            }
            final ParcelFileDescriptor[] pipe;
            try {
                pipe = ParcelFileDescriptor.createPipe();
            } catch (IOException e) {
                FileNotFoundException fnf = new FileNotFoundException("failed to create pipe");
                fnf.initCause(e);
                throw fnf;
            }
            final ParcelFileDescriptor readEnd = pipe[0];
            final ParcelFileDescriptor writeEnd = pipe[1];
            final File outFile = writeOnlyCaptureFile(docId);
            final CountDownLatch done = new CountDownLatch(1);
            WRITE_ONLY_LATCHES.put(docId, done);
            Thread drainer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File parent = outFile.getParentFile();
                        if (parent != null && !parent.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            parent.mkdirs();
                        }
                        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readEnd);
                             FileOutputStream out = new FileOutputStream(outFile)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) >= 0) {
                                if (n > 0) {
                                    out.write(buf, 0, n);
                                }
                            }
                            out.flush();
                        }
                    } catch (IOException ignored) {
                        // Test assertion on captured bytes will surface any truncation.
                    } finally {
                        done.countDown();
                    }
                }
            }, "test-writeonly-saf-drain");
            drainer.start();
            return writeEnd;
        }

        throw new FileNotFoundException("unknown documentId: " + docId);
    }
}
