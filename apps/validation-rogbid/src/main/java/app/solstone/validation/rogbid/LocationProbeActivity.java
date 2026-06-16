// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.validation.rogbid;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class LocationProbeActivity extends Activity {
    private static final String TAG = "RogbidLocationProbe";
    private static final String EVIDENCE_FILE = "location-evidence.txt";
    private static final long TIMEOUT_MS = 45000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder evidence = new StringBuilder();
    private final List<LocationListener> listeners = new ArrayList<>();
    private int pendingProviders = 0;
    private boolean finished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runProbe();
    }

    @Override
    protected void onDestroy() {
        removeListeners();
        super.onDestroy();
    }

    private void runProbe() {
        evidence.append("started=").append(timestamp()).append('\n');
        if (!hasLocationPermission()) {
            evidence.append("ERROR=missing location permission\n");
            finishProbe();
            return;
        }

        LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            evidence.append("ERROR=missing LocationManager\n");
            finishProbe();
            return;
        }

        List<String> allProviders = manager.getAllProviders();
        List<String> enabledProviders = manager.getProviders(true);
        evidence.append("all_providers=").append(allProviders).append('\n');
        evidence.append("enabled_providers=").append(enabledProviders).append('\n');

        for (String provider : allProviders) {
            appendProviderState(manager, provider);
        }

        List<String> requestProviders = new ArrayList<>();
        for (String provider : allProviders) {
            if (!"passive".equals(provider) && isEnabled(manager, provider)) {
                requestProviders.add(provider);
            }
        }
        evidence.append("request_providers=").append(requestProviders).append('\n');
        pendingProviders = requestProviders.size();
        if (pendingProviders == 0) {
            evidence.append("no_enabled_active_providers=true\n");
            finishProbe();
            return;
        }

        for (String provider : requestProviders) {
            requestSingleUpdate(manager, provider);
        }
        handler.postDelayed(() -> {
            if (!finished) {
                evidence.append("timeout_ms=").append(TIMEOUT_MS).append('\n');
                finishProbe();
            }
        }, TIMEOUT_MS);
    }

    private void appendProviderState(LocationManager manager, String provider) {
        evidence.append("provider_").append(provider)
                .append("_enabled=").append(isEnabled(manager, provider)).append('\n');
        try {
            evidence.append("last_").append(provider).append('=')
                    .append(formatLocation(manager.getLastKnownLocation(provider))).append('\n');
        } catch (SecurityException error) {
            evidence.append("last_").append(provider).append("_error=")
                    .append(error.getClass().getSimpleName()).append(": ")
                    .append(error.getMessage()).append('\n');
        } catch (IllegalArgumentException error) {
            evidence.append("last_").append(provider).append("_error=")
                    .append(error.getClass().getSimpleName()).append(": ")
                    .append(error.getMessage()).append('\n');
        }
    }

    private void requestSingleUpdate(LocationManager manager, String provider) {
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                evidence.append("update_").append(provider).append('=')
                        .append(formatLocation(location)).append('\n');
                providerDone(manager, this, provider);
            }

            @Override
            public void onProviderDisabled(String disabledProvider) {
                evidence.append("disabled_").append(disabledProvider).append('\n');
            }

            @Override
            public void onProviderEnabled(String enabledProvider) {
                evidence.append("enabled_").append(enabledProvider).append('\n');
            }

            @Override
            public void onStatusChanged(String statusProvider, int status, Bundle extras) {
                evidence.append("status_").append(statusProvider)
                        .append('=').append(status).append('\n');
            }
        };
        listeners.add(listener);
        try {
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            evidence.append("requested_").append(provider).append("=true\n");
        } catch (SecurityException error) {
            evidence.append("requested_").append(provider).append("_error=")
                    .append(error.getClass().getSimpleName()).append(": ")
                    .append(error.getMessage()).append('\n');
            providerDone(manager, listener, provider);
        } catch (IllegalArgumentException error) {
            evidence.append("requested_").append(provider).append("_error=")
                    .append(error.getClass().getSimpleName()).append(": ")
                    .append(error.getMessage()).append('\n');
            providerDone(manager, listener, provider);
        }
    }

    private void providerDone(LocationManager manager, LocationListener listener, String provider) {
        try {
            manager.removeUpdates(listener);
        } catch (SecurityException ignored) {
            // Probe cleanup only; the evidence above already carries the provider result.
        }
        listeners.remove(listener);
        pendingProviders -= 1;
        evidence.append("done_").append(provider).append("=true\n");
        if (pendingProviders <= 0) {
            finishProbe();
        }
    }

    private boolean hasLocationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isEnabled(LocationManager manager, String provider) {
        try {
            return manager.isProviderEnabled(provider);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private String formatLocation(Location location) {
        if (location == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder();
        text.append("provider:").append(location.getProvider())
                .append(",lat:").append(location.getLatitude())
                .append(",lon:").append(location.getLongitude())
                .append(",accuracy:").append(location.hasAccuracy() ? location.getAccuracy() : -1)
                .append(",time:").append(location.getTime());
        if (android.os.Build.VERSION.SDK_INT >= 17) {
            text.append(",elapsed_nanos:").append(location.getElapsedRealtimeNanos());
        }
        return text.toString();
    }

    private void finishProbe() {
        if (finished) {
            return;
        }
        finished = true;
        removeListeners();
        evidence.append("completed=").append(timestamp()).append('\n');
        writeEvidence(evidence.toString());
        Log.i(TAG, evidence.toString());
        finish();
    }

    private void removeListeners() {
        LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return;
        }
        for (LocationListener listener : new ArrayList<>(listeners)) {
            try {
                manager.removeUpdates(listener);
            } catch (SecurityException ignored) {
                // Best-effort cleanup for a short-lived diagnostic activity.
            }
        }
        listeners.clear();
    }

    private void writeEvidence(String message) {
        File outputFile = new File(getFilesDir(), EVIDENCE_FILE);
        try (FileOutputStream output = new FileOutputStream(outputFile, false)) {
            output.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            Log.e(TAG, "failed to write location evidence", error);
        }
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
