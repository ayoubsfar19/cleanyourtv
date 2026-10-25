package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** A focus-driven shelf containing only real, successfully decoded companion artwork. */
final class RecommendationShelf {
    interface Navigation {
        void returnToDock(int position);
        void goToHeader();
        void shelfFocused();
    }
    private static final String AUTHORITY = "com.gathin.ambienttv.recommendations";
    private static final String[] COLUMNS = {"_id", "title", "description", "kind", "poster_uri",
            "intent_uri", "owner_package", "position", "duration", "live"};
    private final Activity activity;
    private final FrameLayout root;
    private final View header, hero;
    private final Navigation navigation;
    private final float density;
    private final boolean animations, advancedEffects, reducedMotion;
    private final String glassStyle;
    private final SharedPreferences settings;
    private final LinearLayout shelf, row;
    private final HorizontalScrollView scroll;
    private final TextView heading;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> artwork = new HashMap<>();
    private final List<ImageView> images = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private Future<?> queryJob;
    private CancellationSignal cancellation;
    private volatile int generation;
    private volatile boolean active;
    private boolean dockVisible, heroSuppressed, loading;
    private String owner, label, baseHeading = "";
    private int dockPosition, imageHeight, cardWidth;
    private final Rect dockRect = new Rect();
    private List<Program> programs = new ArrayList<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (preferences, key) -> {
        if (SuggestionsSettings.KEY.equals(key)) main.post(this::suggestionsChanged);
    };

    RecommendationShelf(Activity activity, FrameLayout root, View header, View hero, Navigation navigation) {
        this.activity = activity;
        this.root = root;
        this.header = header;
        this.hero = hero;
        this.navigation = navigation;
        density = activity.getResources().getDisplayMetrics().density;
        Config config = Config.load(activity);
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        reducedMotion = config.reducedMotion;
        glassStyle = config.glassStyle;
        settings = activity.getSharedPreferences(Config.SETTINGS_NAME, android.content.Context.MODE_PRIVATE);
        shelf = new LinearLayout(activity);
        shelf.setOrientation(LinearLayout.VERTICAL);
        shelf.setClipChildren(false);
        shelf.setClipToPadding(false);
        shelf.setVisibility(View.GONE);
        heading = new TextView(activity);
        heading.setTextSize(15);
        heading.setTextColor(Color.rgb(225, 229, 235));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setSingleLine(true);
        heading.setIncludeFontPadding(false);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        shelf.addView(heading, new LinearLayout.LayoutParams(-1, dp(27)));
        scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setFillViewport(false);
        scroll.setFocusable(false);
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), 0, dp(4), 0);
        row.setClipToPadding(false);
        row.setClipChildren(false);
        scroll.addView(row, new ViewGroup.LayoutParams(-2, -1));
        shelf.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        root.addView(shelf, new FrameLayout.LayoutParams(-2, -2));
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> layoutShelf());
        settings.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    void resume() {
        active = true;
        if (!SuggestionsSettings.enabled(activity)) { clearSelection(); return; }
        if (owner != null && dockVisible) load();
    }

    void onAppFocused(int position, View card, float dockTop, String packageName, String appLabel, boolean isDock) {
        dockPosition = position;
        dockVisible = isDock;
        heading.setText(baseHeading);
        if (!isDock || TextUtils.isEmpty(packageName)) {
            clearSelection();
            return;
        }
        dockRect.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, dockRect);
        dockRect.offsetTo(dockRect.left, Math.round(dockTop));
        boolean changed = !packageName.equals(owner);
        if (changed) clearSelection();
        owner = packageName;
        label = appLabel;
        if (!SuggestionsSettings.enabled(activity)) { clearSelection(); updateHeading(); return; }
        updateHeading();
        layoutShelf();
        if (changed || programs.isEmpty() && !loading) load();
        shelf.post(this::layoutShelf);
    }

    private void layoutShelf() {
        if (!SuggestionsSettings.enabled(activity)) {
            shelf.setVisibility(View.GONE);
            restoreHero();
            return;
        }
        if (!active || !dockVisible || owner == null || root.getWidth() == 0) return;
        Rect headerRect = new Rect(0, 0, header.getWidth(), header.getHeight());
        root.offsetDescendantRectToMyCoords(header, headerRect);
        int top = headerRect.bottom + dp(12);
        int available = dockRect.top - dp(16) - top;
        if (available < dp(80) || programs.isEmpty()) {
            shelf.setVisibility(View.GONE);
            restoreHero();
            return;
        }
        int width = Math.min(root.getWidth() - dp(128), dp(712));
        int fit = Math.max(dp(52), (width - dp(8)) / 4 - dp(20));
        int heightFit = Math.max(dp(16), available - dp(51));
        int desiredWidth = Math.min(dp(156), Math.min(fit,
                Math.round(heightFit * AppAdapter.DESIGN_W_DP / AppAdapter.DESIGN_H_DP)));
        int desiredHeight = AppAdapter.tileHeightPx(desiredWidth);
        boolean resized = cardWidth != desiredWidth || imageHeight != desiredHeight;
        cardWidth = desiredWidth;
        imageHeight = desiredHeight;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) shelf.getLayoutParams();
        lp.width = width;
        lp.height = imageHeight + dp(51);
        lp.leftMargin = dp(64);
        lp.topMargin = top + Math.max(0, (available - lp.height) / 2);
        if (shelf.getWidth() != lp.width || shelf.getHeight() != lp.height
                || shelf.getLeft() != lp.leftMargin || shelf.getTop() != lp.topMargin) shelf.setLayoutParams(lp);
        // Resizing existing views preserves focus and scroll; it never rebuilds or refocuses a card.
        if (resized) resizeCards();
        shelf.setVisibility(View.VISIBLE);
        hero.animate().cancel();
        hero.setAlpha(0f);
        heroSuppressed = true;
    }

    private void restoreHero() {
        if (heroSuppressed && dockVisible && hero.getVisibility() != View.GONE) hero.setAlpha(1f);
        heroSuppressed = false;
    }

    boolean focus() {
        if (!active || !SuggestionsSettings.enabled(activity)
                || shelf.getVisibility() != View.VISIBLE || cards.isEmpty()) return false;
        return cards.get(0).requestFocus();
    }

    boolean hasFocus() { return shelf.hasFocus(); }

    boolean backToDock() {
        if (!hasFocus()) return false;
        navigation.returnToDock(dockPosition);
        return true;
    }

    private void clearSelection() {
        ++generation;
        cancelJob();
        loading = false;
        clearViews();
        releaseArtwork();
        programs.clear();
        shelf.setVisibility(View.GONE);
        restoreHero();
    }

    private void load() {
        if (!SuggestionsSettings.enabled(activity)) { clearSelection(); return; }
        cancelJob();
        final int request = ++generation;
        if (!active || owner == null || !dockVisible) return;
        final String requestedOwner = owner;
        loading = true;
        clearViews();
        releaseArtwork();
        programs.clear();
        shelf.setVisibility(View.GONE);
        restoreHero();
        cancellation = new CancellationSignal();
        final CancellationSignal signal = cancellation;
        queryJob = worker.submit(() -> {
            List<Program> rows = new ArrayList<>();
            Map<String, Bitmap> decoded = new HashMap<>();
            Uri uri = Uri.parse("content://" + AUTHORITY + "/programs").buildUpon().appendPath(requestedOwner).build();
            try (Cursor cursor = activity.getContentResolver().query(uri, COLUMNS, null, null, null, signal)) {
                if (cursor != null) while (rows.size() < 18 && !signal.isCanceled() && cursor.moveToNext()) {
                    if (!requestedOwner.equals(value(cursor, "owner_package"))) continue;
                    String poster = value(cursor, "poster_uri");
                    String kind = value(cursor, "kind");
                    if (TextUtils.isEmpty(poster) || "open".equals(kind) || "shortcut".equals(kind)) continue;
                    Uri posterUri = Uri.parse(poster);
                    if (!"content".equals(posterUri.getScheme()) || !AUTHORITY.equals(posterUri.getAuthority())) continue;
                    Program program = new Program();
                    program.title = value(cursor, "title");
                    if (TextUtils.isEmpty(program.title)) continue;
                    program.kind = kind;
                    program.poster = poster;
                    program.intent = value(cursor, "intent_uri");
                    program.position = number(cursor, "position");
                    program.duration = number(cursor, "duration");
                    program.live = number(cursor, "live") != 0;
                    rows.add(program);
                }
            } catch (RuntimeException ignored) {
                // Missing companion or canceled provider means no shelf, never invented content.
            }
            List<Program> ready = new ArrayList<>();
            for (Program program : rows) {
                if (signal.isCanceled() || request != generation || Thread.currentThread().isInterrupted()) {
                    recycle(decoded);
                    return;
                }
                Bitmap bitmap = decoded.get(program.poster);
                if (bitmap == null) bitmap = decode(Uri.parse(program.poster));
                if (bitmap != null) {
                    decoded.put(program.poster, bitmap);
                    ready.add(program);
                }
            }
            main.post(() -> {
                if (!active || !SuggestionsSettings.enabled(activity) || !dockVisible
                        || request != generation || !requestedOwner.equals(owner)) {
                    recycle(decoded);
                    return;
                }
                loading = false;
                programs = ready;
                artwork.putAll(decoded);
                // Publish the complete decoded row once. Async completion never requests focus.
                render();
                layoutShelf();
            });
        });
    }

    private void updateHeading() {
        boolean continuing = false, trending = false;
        for (Program program : programs) {
            if ("continue".equals(program.kind)) continuing = true;
            if ("trending".equals(program.kind)) trending = true;
        }
        baseHeading = label + "  ·  " + (continuing ? "À reprendre" : trending ? "Top 10 mondial" : "Suggestions");
        heading.setText(baseHeading);
    }

    private void render() {
        clearViews();
        updateHeading();
        for (int index = 0; index < programs.size(); index++) {
            Program program = programs.get(index);
            Bitmap bitmap = artwork.get(program.poster);
            if (bitmap == null || bitmap.isRecycled()) continue;
            FrameLayout cell = new FrameLayout(activity);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            row.addView(cell, new LinearLayout.LayoutParams(-2, -2));
            FrameLayout card = new FrameLayout(activity);
            card.setFocusable(true);
            card.setClickable(true);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AppAdapter.cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);
            cell.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            ImageView picture = new ImageView(activity);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setImageBitmap(bitmap);
            card.addView(picture, new FrameLayout.LayoutParams(-1, -1));
            images.add(picture);
            GlassView glass = new GlassView(activity, null);
            glass.setStyle(glassStyle);
            glass.setAlpha(0f);
            glass.setFocusable(false);
            glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell.addView(glass, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            card.setTag(glass);
            if (program.live) {
                TextView live = new TextView(activity);
                live.setTextSize(9);
                live.setTextColor(Color.WHITE);
                live.setText("LIVE");
                live.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                live.setBackgroundColor(Color.rgb(195, 39, 58));
                live.setPadding(dp(5), dp(2), dp(5), dp(2));
                FrameLayout.LayoutParams liveLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                liveLp.setMargins(dp(6), dp(6), 0, 0);
                card.addView(live, liveLp);
            }
            if (program.duration > 0 && program.position > 0 && !program.live) {
                ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
                progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66535d6b));
                progress.setMax(1000);
                progress.setProgress((int) Math.min(1000, 1000d * program.position / program.duration));
                card.addView(progress, new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM));
            }
            card.setContentDescription(program.title + (program.live ? ", LIVE" : ""));
            card.setOnFocusChangeListener((v, focused) -> {
                float scale = focused ? focusScale() : 1f;
                float lift = focused && advancedEffects ? -dp(4) : 0;
                card.animate().cancel();
                if (animations) card.animate().scaleX(scale).scaleY(scale).translationY(lift)
                        .translationZ(focused && advancedEffects ? dp(8) : 0)
                        .setDuration(reducedMotion ? 80 : focused ? 220 : 170)
                        .setInterpolator(new DecelerateInterpolator(1.5f)).start();
                else { card.setScaleX(scale); card.setScaleY(scale); card.setTranslationY(lift); }
                glass.setTranslationY(lift);
                glass.setAlpha(focused ? 1f : 0f);
                heading.setText(focused ? baseHeading + "  ·  " + program.title : baseHeading);
                if (focused) navigation.shelfFocused();
            });
            final int cardIndex = index;
            card.setOnKeyListener((v, key, event) -> {
                boolean up = key == KeyEvent.KEYCODE_DPAD_UP;
                boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN;
                boolean back = key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE;
                if (up || down || back) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        heading.setText(baseHeading);
                        if (up) navigation.goToHeader();
                        else navigation.returnToDock(dockPosition);
                    }
                    return true;
                }
                return key == KeyEvent.KEYCODE_DPAD_LEFT && cardIndex == 0
                        || key == KeyEvent.KEYCODE_DPAD_RIGHT && cardIndex == programs.size()-1;
            });
            card.setOnClickListener(v -> open(program));
            cards.add(card);
        }
        resizeCards();
    }

    private float focusScale() { return !animations ? 1f : advancedEffects ? 1.12f : 1.10f; }

    private void resizeCards() {
        int width = cardWidth > 0 ? cardWidth : dp(156);
        int height = imageHeight > 0 ? imageHeight : AppAdapter.tileHeightPx(width);
        for (View card : cards) {
            View cell = (View) card.getParent();
            cell.setLayoutParams(new LinearLayout.LayoutParams(width + dp(20), height + dp(24)));
            card.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
            GlassView glass = (GlassView) card.getTag();
            glass.setLayoutParams(new FrameLayout.LayoutParams(Math.round(width * focusScale()) + dp(12),
                    Math.round(height * focusScale()) + dp(12), Gravity.CENTER));
            glass.setRadius(AppAdapter.cornerRadius(width, density) * focusScale());
            card.invalidateOutline();
        }
    }

    private Bitmap decode(Uri uri) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, options); }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 640 || options.outHeight / options.inSampleSize > 360) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(in, null, options); }
            if (bitmap != null && (bitmap.getWidth() > 320 || bitmap.getHeight() > 180)) {
                float scale = Math.min(320f / bitmap.getWidth(), 180f / bitmap.getHeight());
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth()*scale)), Math.max(1, Math.round(bitmap.getHeight()*scale)), true);
                if (bitmap != scaled) bitmap.recycle();
                return scaled;
            }
            return bitmap;
        } catch (Exception | OutOfMemoryError ignored) { return null; }
    }

    private void open(Program program) {
        Intent intent = RecommendationIntentPolicy.resolve(activity, owner, program.intent);
        if (intent == null) { Toast.makeText(activity, "Application indisponible", Toast.LENGTH_SHORT).show(); return; }
        try { activity.startActivity(intent); }
        catch (RuntimeException ignored) {
            Intent fallback = RecommendationIntentPolicy.resolve(activity, owner, null);
            if (fallback != null) try { activity.startActivity(fallback); } catch (RuntimeException unavailable) {
                Toast.makeText(activity, "Application indisponible", Toast.LENGTH_SHORT).show();
            }
        }
    }

    void stop() {
        active = false;
        clearSelection();
    }

    private void suggestionsChanged() {
        if (!SuggestionsSettings.enabled(activity)) {
            boolean wasFocused = hasFocus();
            clearSelection();
            if (wasFocused) navigation.returnToDock(dockPosition);
        } else if (active && owner != null && dockVisible) load();
    }

    void close() {
        settings.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        stop();
        worker.shutdownNow();
    }

    private void cancelJob() {
        if (cancellation != null) cancellation.cancel();
        if (queryJob != null) queryJob.cancel(true);
    }
    private void clearViews() {
        for (View card : cards) card.animate().cancel();
        for (ImageView image : images) image.setImageDrawable(null);
        images.clear();
        cards.clear();
        row.removeAllViews();
        heading.setText(baseHeading);
    }
    private void releaseArtwork() { recycle(artwork); artwork.clear(); }
    private static void recycle(Map<String, Bitmap> bitmaps) {
        for (Bitmap bitmap : bitmaps.values()) if (!bitmap.isRecycled()) bitmap.recycle();
    }
    private int dp(float value) { return Math.round(value * density); }
    private static String value(Cursor cursor, String name) {
        int column = cursor.getColumnIndex(name);
        return column < 0 || cursor.isNull(column) ? "" : cursor.getString(column);
    }
    private static long number(Cursor cursor, String name) {
        int column = cursor.getColumnIndex(name);
        return column < 0 || cursor.isNull(column) ? 0 : cursor.getLong(column);
    }
    private static final class Program {
        String title, kind, poster, intent;
        long position, duration;
        boolean live;
    }
}
