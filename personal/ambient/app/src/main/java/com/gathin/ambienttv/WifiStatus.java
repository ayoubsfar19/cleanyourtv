package com.gathin.ambienttv;

import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Local connection status. Never requests a scan or changes location/network settings. */
final class WifiStatus {
    private static final long REFRESH_MS = 45_000L;
    private static final String WIFI_CONNECTED = "Wi-Fi connecté";
    private static final String OFFLINE = "Hors connexion";

    final TextView view;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private boolean running;
    private int generation;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!running || worker == null) return;
            final int requestedGeneration = generation;
            try {
                worker.execute(() -> {
                    String label = readLabel();
                    handler.post(() -> {
                        if (!running || requestedGeneration != generation) return;
                        view.setText(label);
                        view.setContentDescription(label);
                    });
                });
            } catch (RejectedExecutionException ignored) {
                // A host may stop while a refresh is being queued.
            }
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    WifiStatus(Context context) {
        Context appContext = context.getApplicationContext();
        this.context = appContext == null ? context : appContext;
        view = new TextView(context);
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setAlpha(0.9f);
        view.setGravity(Gravity.END);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setMaxWidth(Math.round(220 * context.getResources().getDisplayMetrics().density));
        view.setShadowLayer(3, 0, 1, 0xb0000000);
        view.setFocusable(false);
        view.setClickable(false);
        view.setText(OFFLINE);
    }

    void start() {
        if (running) return;
        running = true;
        generation++;
        worker = Executors.newSingleThreadExecutor();
        handler.post(refresh);
    }

    void stop() {
        running = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    @SuppressWarnings("deprecation")
    private String readLabel() {
        try {
            ConnectivityManager connectivity = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity == null) return OFFLINE;
            Network active = connectivity.getActiveNetwork();
            NetworkCapabilities capabilities = active == null ? null
                    : connectivity.getNetworkCapabilities(active);
            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return wifiLabel();
            }
            // A VPN can be the default network: inspect the connected local transport too.
            NetworkInfo ethernet = connectivity.getNetworkInfo(ConnectivityManager.TYPE_ETHERNET);
            if (ethernet != null && ethernet.isConnected()) return "Ethernet";
            NetworkInfo wifi = connectivity.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (wifi != null && wifi.isConnected()) return wifiLabel();
            return capabilities == null ? OFFLINE : "Connecté";
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Missing/restricted permissions must never interrupt the slideshow.
            return OFFLINE;
        }
    }

    @SuppressWarnings("deprecation")
    private String wifiLabel() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            WifiInfo connection = wifi == null ? null : wifi.getConnectionInfo();
            return ssidLabel(connection == null ? null : connection.getSSID());
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Android may redact the SSID while location is disabled; leave it disabled.
            return WIFI_CONNECTED;
        }
    }

    static String ssidLabel(String ssid) {
        if (ssid == null) return WIFI_CONNECTED;
        String name = ssid.trim();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1);
        }
        // A network name must not add extra lines or override the status label's text direction.
        name = name.replaceAll("[\\p{Cntrl}\\u202a-\\u202e\\u2066-\\u2069]", "").trim();
        return name.isEmpty() || "<unknown ssid>".equalsIgnoreCase(name)
                || "0x".equalsIgnoreCase(name) ? WIFI_CONNECTED : name;
    }
}
