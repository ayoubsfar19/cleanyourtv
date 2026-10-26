package com.gathin.ambienttv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** A passive view of the cached agenda. It never owns networking or remote focus. */
final class AgendaCard {
    private static final long REFRESH_MS = 15_000L;
    private static final long HORIZON_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int GOLD = 0xffddc99d;
    private static final Locale FRENCH = Locale.FRANCE;

    private final Context context;
    private final LinearLayout card;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private boolean running, visible = true, loading;
    private int generation;
    private List<AgendaEvent> events = Collections.emptyList();

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!running) return;
            reload();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private final Runnable preferencesChanged = this::onPreferencesChanged;

    private void onPreferencesChanged() {
        if (!running) return;
        // Disable and disconnect take effect before a cache read finishes.
        // A replacement source also clears its success timestamp and old view.
        if (AgendaStore.lastSuccess(context) == 0) {
            events = Collections.emptyList();
            generation++;
            loading = false;
        }
        render();
        reload();
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        // Reminder bookkeeping can change thousands of keys in one commit.
        if (key != null && (key.startsWith("seen_") || key.startsWith("done_"))) return;
        main.removeCallbacks(preferencesChanged);
        main.post(preferencesChanged);
    };

    AgendaCard(Context context, FrameLayout root) {
        this.context = context;
        preferences = AgendaStore.preferences(context);
        card = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int limit = dp(140);
                if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED) {
                    limit = Math.min(limit, View.MeasureSpec.getSize(heightSpec));
                }
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(
                        limit, View.MeasureSpec.AT_MOST));
            }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(background());
        card.setClipToOutline(true);
        card.setFocusable(false);
        card.setClickable(false);
        card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        card.setVisibility(View.GONE);
        int available = context.getResources().getDisplayMetrics().widthPixels - dp(56);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(
                Math.max(dp(180), Math.min(dp(300), available)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
        position.leftMargin = dp(28);
        position.bottomMargin = dp(28);
        root.addView(card, position);
    }

    void start() {
        if (running) return;
        running = true;
        generation++;
        loading = false;
        worker = Executors.newSingleThreadExecutor();
        preferences.registerOnSharedPreferenceChangeListener(changes);
        render();
        main.post(refresh);
    }

    void stop() {
        running = false;
        generation++;
        loading = false;
        events = Collections.emptyList();
        preferences.unregisterOnSharedPreferenceChangeListener(changes);
        main.removeCallbacksAndMessages(null);
        if (worker != null) worker.shutdownNow();
        worker = null;
        card.setVisibility(View.GONE);
    }

    void setVisible(boolean visible) {
        this.visible = visible;
        render();
    }

    private void reload() {
        if (!running || loading || worker == null) return;
        if (!AgendaStore.enabled(context) || !AgendaStore.configured(context)) {
            events = Collections.emptyList();
            render();
            return;
        }
        loading = true;
        final int requestedGeneration = generation;
        try {
            worker.execute(() -> {
                List<AgendaEvent> loaded;
                try { loaded = AgendaStore.load(context); }
                catch (RuntimeException unavailable) { loaded = Collections.emptyList(); }
                final List<AgendaEvent> result = loaded == null
                        ? Collections.emptyList() : new ArrayList<>(loaded);
                main.post(() -> {
                    if (!running || requestedGeneration != generation) return;
                    loading = false;
                    events = result;
                    render();
                });
            });
        } catch (RejectedExecutionException stopped) {
            loading = false;
        }
    }

    private void render() {
        if (!running || !visible || !AgendaStore.enabled(context)
                || !AgendaStore.configured(context)) {
            card.setVisibility(View.GONE);
            return;
        }
        long now = System.currentTimeMillis();
        List<AgendaEvent> upcoming = new ArrayList<>();
        for (AgendaEvent event : events) {
            if (event == null || event.endMillis <= now || event.startMillis > now + HORIZON_MS) continue;
            upcoming.add(event);
        }
        upcoming.sort(Comparator.comparingLong((AgendaEvent event) -> event.startMillis)
                .thenComparingLong(event -> event.endMillis));
        if (upcoming.isEmpty()) {
            card.setVisibility(View.GONE);
            return;
        }

        card.removeAllViews();
        int count = Math.min(2, upcoming.size());
        float fontScale = context.getResources().getConfiguration().fontScale;
        for (int i = 0; i < count; i++) {
            AgendaEvent event = upcoming.get(i);
            if (i > 0) {
                View divider = new View(context);
                divider.setBackgroundColor(0x23ddc99d);
                LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, dp(1));
                spacing.topMargin = dp(7);
                spacing.bottomMargin = dp(6);
                card.addView(divider, spacing);
            }
            TextView date = text(when(event, now), 11, GOLD, 1);
            date.setFontFeatureSettings("tnum");
            card.addView(date);

            String title = clean(event.title, 180);
            TextView name = text(title.isEmpty() ? "Rendez-vous" : title, 15, 0xfff3f3ef, 1);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams titlePosition = new LinearLayout.LayoutParams(-1, -2);
            titlePosition.topMargin = dp(3);
            card.addView(name, titlePosition);

            String description = clean(event.description, 240);
            if (!description.isEmpty() && (count == 1 || fontScale <= 1.0f)) {
                int lines = count == 1 && fontScale <= 1.3f ? 2 : 1;
                TextView detail = text(description, 12, 0xbfdce0e1, lines);
                LinearLayout.LayoutParams detailPosition = new LinearLayout.LayoutParams(-1, -2);
                detailPosition.topMargin = dp(3);
                card.addView(detail, detailPosition);
            }
        }
        card.setVisibility(View.VISIBLE);
    }

    private String when(AgendaEvent event, long now) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime start = Instant.ofEpochMilli(event.startMillis).atZone(zone);
        ZonedDateTime end = Instant.ofEpochMilli(event.endMillis).atZone(zone);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        String day = start.toLocalDate().equals(today) ? "Aujourd’hui"
                : start.toLocalDate().equals(today.plusDays(1)) ? "Demain"
                : start.format(DateTimeFormatter.ofPattern("EEE d MMM", FRENCH));
        if (event.allDay) return day + " · toute la journée";
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm", FRENCH);
        if (event.startMillis <= now && end.toLocalDate().equals(today)) {
            return "En cours · jusqu’à " + end.format(time);
        }
        return day + " · " + start.format(time)
                + (end.toLocalDate().equals(start.toLocalDate()) ? "–" + end.format(time) : "");
    }

    private TextView text(String value, int size, int color, int lines) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setMaxLines(lines);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setFocusable(false);
        view.setClickable(false);
        view.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
        return view;
    }

    private GradientDrawable background() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0x9912171b);
        shape.setCornerRadius(dp(12));
        shape.setStroke(dp(1), 0x36ddc99d);
        return shape;
    }

    private static String clean(String value, int maximum) {
        if (value == null) return "";
        String result = value.replaceAll("[\\s\\p{Cntrl}]+", " ").trim();
        return result.length() > maximum ? result.substring(0, maximum) : result;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
