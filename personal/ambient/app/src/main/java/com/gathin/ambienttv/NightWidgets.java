package com.gathin.ambienttv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Ambient widgets above external night playback; no photo, football or audio ownership. */
final class NightWidgets {
    private static final long MINUTE_MS = 60_000L;
    private static final long WEATHER_REFRESH_MS = 30 * MINUTE_MS;
    private static final String WEATHER_FALLBACK = "Oujda  ·  Météo…";

    private final Context context;
    private final View overlay, bottomLine;
    private final TextView weather;
    private final WifiStatus wifi;
    private final SharedPreferences cache;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private ExecutorService worker;
    private boolean running, weatherOffline;
    private int generation;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            updateDate();
            updateWeatherLabel();
            nudge();
            ui.postDelayed(this, MINUTE_MS);
        }
    };

    private final Runnable weatherPoll = new Runnable() {
        @Override public void run() {
            if (!running || worker == null) return;
            final int requestedGeneration = generation;
            try {
                worker.execute(() -> {
                    String fresh;
                    try { fresh = "Oujda  ·  " + Weather.now(34.68, -1.91); }
                    catch (Exception unavailable) { fresh = null; }
                    final String value = fresh;
                    ui.post(() -> {
                        if (!running || requestedGeneration != generation) return;
                        weatherOffline = value == null;
                        if (value != null) cache.edit().putString("weather", value).apply();
                        updateWeatherLabel();
                    });
                });
            } catch (RejectedExecutionException ignored) {
                // A host may stop while a refresh is being queued.
            }
            ui.postDelayed(this, WEATHER_REFRESH_MS);
        }
    };

    NightWidgets(Context context, FrameLayout root) {
        this.context = context;
        cache = context.getSharedPreferences("ambient-scores", Context.MODE_PRIVATE);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClipChildren(false);

        overlay = LayoutInflater.from(context).inflate(R.layout.overlay, root, false);
        overlay.setBackgroundColor(Color.TRANSPARENT);
        overlay.setClickable(false);
        overlay.setFocusable(false);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.findViewById(R.id.caption).setVisibility(View.GONE);
        overlay.findViewById(R.id.empty).setVisibility(View.GONE);
        ((TextView) overlay.findViewById(R.id.emptyPath)).setText("");
        overlay.findViewById(R.id.weather).setVisibility(View.GONE);
        bottomLine = overlay.findViewById(R.id.bottomLine);
        // A shallow fade matches the photo scene without obscuring the playing video.
        View fade = overlay.findViewById(R.id.bottomFade);
        ViewGroup.LayoutParams fadePosition = fade.getLayoutParams();
        fadePosition.height = dp(130);
        fade.setLayoutParams(fadePosition);

        weather = new TextView(context);
        weather.setTextColor(Color.WHITE);
        weather.setTextSize(17);
        weather.setFontFeatureSettings("tnum");
        weather.setShadowLayer(dp(4), 0, 1, Color.BLACK);
        weather.setSingleLine(true);
        weather.setEllipsize(TextUtils.TruncateAt.END);
        weather.setMaxWidth(Math.max(dp(180),
                context.getResources().getDisplayMetrics().widthPixels - dp(320)));
        weather.setFocusable(false);
        weather.setClickable(false);
        FrameLayout.LayoutParams weatherPosition = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.LEFT);
        weatherPosition.leftMargin = dp(32);
        weatherPosition.topMargin = dp(28);
        root.addView(weather, weatherPosition);

        wifi = new WifiStatus(context);
        FrameLayout.LayoutParams wifiPosition = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.RIGHT);
        wifiPosition.topMargin = dp(28);
        wifiPosition.rightMargin = dp(32);
        root.addView(wifi.view, wifiPosition);
        updateClockVisibility();
        updateDate();
        updateWeatherLabel();
    }

    void start() {
        if (running) return;
        running = true;
        generation++;
        weatherOffline = false;
        updateClockVisibility();
        worker = Executors.newSingleThreadExecutor();
        wifi.start();
        ui.post(tick);
        ui.post(weatherPoll);
    }

    void stop() {
        running = false;
        generation++;
        ui.removeCallbacksAndMessages(null);
        bottomLine.animate().cancel();
        wifi.stop();
        if (worker != null) worker.shutdownNow();
        worker = null;
    }

    private void updateClockVisibility() {
        int visibility = Options.load(context).clock ? View.VISIBLE : View.GONE;
        for (int id : new int[]{R.id.time, R.id.ampm, R.id.date}) {
            overlay.findViewById(id).setVisibility(visibility);
        }
        bottomLine.setVisibility(visibility);
        overlay.findViewById(R.id.bottomFade).setVisibility(visibility);
    }

    private void updateDate() {
        SimpleDateFormat format = new SimpleDateFormat("EEEE d MMMM", Locale.FRANCE);
        format.setTimeZone(TimeZone.getDefault());
        String value = "'" + format.format(new Date()) + "'";
        TextClock date = overlay.findViewById(R.id.date);
        date.setFormat12Hour(value);
        date.setFormat24Hour(value);
        for (int id : new int[]{R.id.time, R.id.date, R.id.ampm}) {
            ((TextClock) overlay.findViewById(id)).setTimeZone(null);
        }
    }

    private void updateWeatherLabel() {
        String value = cache.getString("weather", WEATHER_FALLBACK);
        if (value == null || value.trim().isEmpty()) value = WEATHER_FALLBACK;
        weather.setText(value + (weatherOffline ? " · hors ligne" : ""));
    }

    private void nudge() {
        if (bottomLine.getVisibility() != View.VISIBLE) return;
        float density = context.getResources().getDisplayMetrics().density;
        bottomLine.animate().translationX((random.nextFloat() * 2 - 1) * 20 * density)
                .translationY(random.nextFloat() * -6 * density).setDuration(1500).start();
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
