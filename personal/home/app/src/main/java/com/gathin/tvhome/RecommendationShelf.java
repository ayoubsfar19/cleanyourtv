package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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

/** A local, focus-driven shelf. All content and artwork are supplied by the companion provider. */
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
    private final LinearLayout shelf, row;
    private final HorizontalScrollView scroll;
    private final TextView heading, subtitle;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> artwork = new HashMap<>();
    private final List<ImageView> images = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private Future<?> queryJob, imageJob;
    private CancellationSignal cancellation;
    private volatile int generation;
    private volatile boolean active;
    private boolean dockVisible;
    private String owner, label;
    private int dockPosition;
    private final Rect dockRect = new Rect();
    private int imageHeight, cardWidth;
    private boolean heroSuppressed;
    private List<Program> programs = new ArrayList<>();

    RecommendationShelf(Activity activity, FrameLayout root, View header, View hero, Navigation navigation) {
        this.activity = activity;
        this.root = root;
        this.header = header;
        this.hero = hero;
        this.navigation = navigation;
        density = activity.getResources().getDisplayMetrics().density;
        shelf = new LinearLayout(activity);
        shelf.setOrientation(LinearLayout.VERTICAL);
        shelf.setClipChildren(false);
        shelf.setClipToPadding(false);
        shelf.setVisibility(View.GONE);
        heading = text(15, Color.rgb(225, 229, 235));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setSingleLine(true);
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
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.setClipToPadding(false);
        row.setClipChildren(false);
        scroll.addView(row, new ViewGroup.LayoutParams(-2, -1));
        shelf.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        subtitle = text(10, Color.rgb(176, 187, 203));
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setGravity(Gravity.CENTER_VERTICAL);
        shelf.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(20)));
        root.addView(shelf, new FrameLayout.LayoutParams(-2, -2));
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> layoutShelf());
    }

    void resume() {
        active = true;
        if (owner != null && dockVisible) load();
    }

    void onAppFocused(int position, View card, float dockTop, String packageName, String appLabel, boolean isDock) {
        dockPosition = position;
        dockVisible = isDock;
        if (!isDock || TextUtils.isEmpty(packageName)) {
            ++generation;
            cancelJobs();
            clearViews();
            releaseArtwork();
            programs.clear();
            shelf.setVisibility(View.GONE);
            restoreHero();
            return;
        }
        dockRect.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, dockRect);
        dockRect.offsetTo(dockRect.left, Math.round(dockTop));
        boolean changed = !packageName.equals(owner);
        owner = packageName;
        label = appLabel;
        layoutShelf();
        if (changed || programs.isEmpty()) load();
        // The hero's measured size may change with the app name on this frame.
        shelf.post(this::layoutShelf);
    }

    private void layoutShelf() {
        if (!dockVisible || owner == null || root.getWidth() == 0) return;
        Rect headerRect = new Rect(0, 0, header.getWidth(), header.getHeight());
        root.offsetDescendantRectToMyCoords(header, headerRect);
        int top = headerRect.bottom + dp(12);
        // The shelf heading names the app, replacing the large hero visually without moving the dock.
        int bottom = dockRect.top - dp(16);
        int available = bottom - top;
        if (available < dp(80)) {
            if (hasFocus()) navigation.returnToDock(dockPosition);
            shelf.setVisibility(View.GONE);
            restoreHero();
            return;
        }
        boolean showSubtitle = available >= dp(125);
        subtitle.setVisibility(showSubtitle ? View.VISIBLE : View.GONE);
        int extra = dp(showSubtitle ? 87 : 67);
        int desiredImage = Math.min(dp(88), available - extra);
        desiredImage = Math.max(dp(13), desiredImage);
        int width = Math.min(root.getWidth() - dp(128), dp(672));
        int desiredCardWidth = Math.min(dp(156), Math.max(dp(104), (width - dp(56)) / 4));
        boolean resized = imageHeight != desiredImage || cardWidth != desiredCardWidth;
        imageHeight = desiredImage;
        cardWidth = desiredCardWidth;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) shelf.getLayoutParams();
        lp.width = width;
        lp.height = Math.min(available, imageHeight + extra);
        lp.leftMargin = dp(64);
        lp.topMargin = top + Math.max(0, (available - lp.height) / 2);
        if (shelf.getWidth() != lp.width || shelf.getHeight() != lp.height
                || shelf.getLeft() != lp.leftMargin || shelf.getTop() != lp.topMargin) shelf.setLayoutParams(lp);
        if (resized && !programs.isEmpty()) render();
        shelf.setVisibility(programs.isEmpty() ? View.GONE : View.VISIBLE);
        if (!programs.isEmpty()) {
            hero.animate().cancel();
            hero.setAlpha(0f);
            heroSuppressed = true;
        } else restoreHero();
    }

    private void restoreHero() {
        if (heroSuppressed && dockVisible && hero.getVisibility() != View.GONE) hero.setAlpha(1f);
        heroSuppressed = false;
    }

    boolean focus() {
        if (shelf.getVisibility() != View.VISIBLE || cards.isEmpty()) return false;
        cards.get(0).requestFocus();
        return true;
    }

    boolean hasFocus() { return shelf.hasFocus(); }

    boolean backToDock() {
        if (!hasFocus()) return false;
        navigation.returnToDock(dockPosition);
        return true;
    }

    private void load() {
        cancelJobs();
        final int request = ++generation;
        if (!active || owner == null) return;
        final String requestedOwner = owner;
        final String requestedLabel = label;
        // Never show the preceding app's cards while a different provider query is pending.
        programs = new ArrayList<>();
        clearViews();
        releaseArtwork();
        shelf.setVisibility(View.GONE);
        cancellation = new CancellationSignal();
        final CancellationSignal signal = cancellation;
        queryJob = worker.submit(() -> {
            List<Program> result = new ArrayList<>();
            Uri uri = Uri.parse("content://" + AUTHORITY + "/programs").buildUpon().appendPath(requestedOwner).build();
            try (Cursor cursor = activity.getContentResolver().query(uri, COLUMNS, null, null, null, signal)) {
                if (cursor != null) while (result.size() < 18 && !signal.isCanceled() && cursor.moveToNext()) {
                    String rowOwner = value(cursor, "owner_package");
                    if (!requestedOwner.equals(rowOwner)) continue;
                    Program program = new Program();
                    program.title = value(cursor, "title");
                    program.description = value(cursor, "description");
                    program.kind = value(cursor, "kind");
                    program.poster = value(cursor, "poster_uri");
                    program.intent = value(cursor, "intent_uri");
                    program.position = number(cursor, "position");
                    program.duration = number(cursor, "duration");
                    program.live = number(cursor, "live") != 0;
                    if (!TextUtils.isEmpty(program.title)) result.add(program);
                }
            } catch (RuntimeException ignored) {
                // Companion absent, permission unavailable, or provider canceled: use the real app launch.
            }
            if (result.isEmpty()) {
                Program fallback = new Program();
                fallback.title = "Ouvrir " + requestedLabel;
                fallback.kind = "open";
                result.add(fallback);
            }
            main.post(() -> {
                if (!active || request != generation || !requestedOwner.equals(owner)) return;
                programs = result;
                render();
                layoutShelf();
                loadArtwork(request);
            });
        });
    }

    private void render() {
        boolean focused = hasFocus();
        clearViews();
        boolean continuing = false, trending = false, onlyShortcuts = true;
        for (Program program : programs) {
            if ("continue".equals(program.kind)) continuing = true;
            if ("trending".equals(program.kind)) trending = true;
            if (!"open".equals(program.kind) && !"shortcut".equals(program.kind)) onlyShortcuts = false;
        }
        heading.setText(label + "  ·  " + (continuing ? "À reprendre" : trending ? "Top 10 mondial"
                : onlyShortcuts ? "Accès à l’application" : "Suggestions"));
        subtitle.setText(programs.isEmpty() ? "" : programDescription(programs.get(0)));
        int width = cardWidth > 0 ? cardWidth : dp(156);
        int height = imageHeight > 0 ? imageHeight : dp(88);
        for (int index = 0; index < programs.size(); index++) {
            Program program = programs.get(index);
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            card.setPadding(dp(3), dp(3), dp(3), dp(3));
            card.setBackground(background(false));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(width, height + dp(32));
            cardLp.rightMargin = index == programs.size()-1 ? 0 : dp(16);
            row.addView(card, cardLp);
            FrameLayout poster = new FrameLayout(activity);
            card.addView(poster, new LinearLayout.LayoutParams(-1, height));
            ImageView picture = new ImageView(activity);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setBackgroundColor(Color.rgb(30, 36, 45));
            picture.setImageBitmap(artwork.get(program.poster));
            poster.addView(picture, new FrameLayout.LayoutParams(-1, -1));
            images.add(picture);
            TextView placeholder = text(16, Color.rgb(165, 176, 190));
            placeholder.setGravity(Gravity.CENTER);
            boolean ranked = "trending".equals(program.kind);
            placeholder.setText(ranked ? (index + 1) + "  " + program.title : label);
            if (ranked) { placeholder.setTextSize(14); placeholder.setSingleLine(false); placeholder.setMaxLines(3); }
            placeholder.setSingleLine(!ranked);
            placeholder.setEllipsize(TextUtils.TruncateAt.END);
            placeholder.setPadding(dp(8), 0, dp(8), 0);
            placeholder.setVisibility(artwork.containsKey(program.poster) ? View.GONE : View.VISIBLE);
            poster.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            picture.setTag(placeholder);
            if (program.live) {
                TextView live = text(9, Color.WHITE);
                live.setText("LIVE");
                live.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                live.setBackgroundColor(Color.rgb(195, 39, 58));
                live.setPadding(dp(5), dp(2), dp(5), dp(2));
                FrameLayout.LayoutParams liveLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                liveLp.setMargins(dp(6), dp(6), 0, 0);
                poster.addView(live, liveLp);
            }
            if (program.duration > 0 && program.position > 0 && !program.live) {
                ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
                progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66535d6b));
                progress.setMax(1000);
                progress.setProgress((int) Math.min(1000, 1000d * program.position / program.duration));
                poster.addView(progress, new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM));
            }
            TextView title = text(11, Color.rgb(224, 228, 233));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setText(program.title);
            title.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(title, new LinearLayout.LayoutParams(-1, dp(26)));
            card.setContentDescription(program.title + (program.live ? ", LIVE" : ""));
            card.setOnFocusChangeListener((v, hasFocus) -> {
                card.setBackground(background(hasFocus));
                title.setTextColor(hasFocus ? Color.WHITE : Color.rgb(224, 228, 233));
                if (hasFocus) {
                    subtitle.setText(programDescription(program));
                    navigation.shelfFocused();
                }
            });
            final int cardIndex = index;
            card.setOnKeyListener((v, key, event) -> {
                boolean up = key == KeyEvent.KEYCODE_DPAD_UP;
                boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN;
                boolean back = key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE;
                if (up || down || back) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
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
        scroll.scrollTo(0, 0);
        if (focused && !cards.isEmpty()) cards.get(0).requestFocus();
    }

    private String programDescription(Program program) {
        String detail = TextUtils.isEmpty(program.description) ? "" : program.description;
        return "trending".equals(program.kind) ? "Ouvre " + label + " · " + detail : detail;
    }

    private void loadArtwork(int request) {
        final List<Program> rows = new ArrayList<>(programs);
        imageJob = worker.submit(() -> {
            for (int index = 0; index < rows.size(); index++) {
                if (!active || request != generation || Thread.currentThread().isInterrupted()) return;
                String raw = rows.get(index).poster;
                if (TextUtils.isEmpty(raw)) continue;
                Uri uri;
                try { uri = Uri.parse(raw); } catch (RuntimeException ignored) { continue; }
                if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())) continue;
                Bitmap bitmap = decode(uri);
                if (bitmap == null) continue;
                final int imageIndex = index;
                main.post(() -> {
                    if (!active || request != generation) { bitmap.recycle(); return; }
                    Bitmap existing = artwork.get(raw);
                    if (existing != null) bitmap.recycle();
                    else artwork.put(raw, bitmap);
                    if (imageIndex < images.size()) {
                        ImageView image = images.get(imageIndex);
                        image.setImageBitmap(existing == null ? bitmap : existing);
                        ((View) image.getTag()).setVisibility(View.GONE);
                    }
                });
            }
        });
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
        ++generation;
        cancelJobs();
        clearViews();
        releaseArtwork();
        programs.clear();
        shelf.setVisibility(View.GONE);
        restoreHero();
    }

    void close() { stop(); worker.shutdownNow(); }

    private void cancelJobs() {
        if (cancellation != null) cancellation.cancel();
        if (queryJob != null) queryJob.cancel(true);
        if (imageJob != null) imageJob.cancel(true);
    }
    private void clearViews() {
        for (ImageView image : images) image.setImageDrawable(null);
        images.clear();
        cards.clear();
        row.removeAllViews();
    }
    private void releaseArtwork() {
        for (Bitmap bitmap : artwork.values()) if (!bitmap.isRecycled()) bitmap.recycle();
        artwork.clear();
    }
    private GradientDrawable background(boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(focused ? 0xff27364c : 0xff10151d);
        shape.setCornerRadius(dp(9));
        shape.setStroke(dp(focused ? 2 : 1), focused ? Color.WHITE : 0x443e4c5e);
        return shape;
    }
    private TextView text(int size, int color) {
        TextView view = new TextView(activity);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
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
        String title, description, kind, poster, intent;
        long position, duration;
        boolean live;
    }
}
