package com.gathin.ambienttv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The small night-mode controls; the host supplies playback and retains its ambient widgets. */
final class NightMode {
    interface Playback {
        void play(int index);
        void stop();
        void pause();
        void resume();
    }

    interface Listener {
        void onEnter();
        void onExit();
    }

    private static final long CONTROLS_TIMEOUT_MS = 5_000L;
    private final Context context;
    private final FrameLayout root, videoLayer;
    private final Playback playback;
    private final Listener listener;
    private final String[] labels;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final View moon, dimmer;
    private final LinearLayout toolbar;
    private final TextView playlistButton, lightButton, photosButton, status;
    private final TextView[] buttons;
    private final Runnable autoHide = this::hideControls;
    private FrameLayout modal;
    private TextView[] choices;
    private View previousPhotoFocus;
    private Runnable entryAction;
    private boolean running, active, detached, available = true, dimmed = true;
    private int selected, modalSelection, toolbarSelection, capturedKey;

    NightMode(Context context, FrameLayout root, FrameLayout videoLayer,
              Playback playback, Listener listener, String[] labels) {
        this.context = context;
        this.root = root;
        this.videoLayer = videoLayer;
        this.playback = playback;
        this.listener = listener;
        if (labels == null || labels.length < 1 || labels.length > 50) {
            throw new IllegalArgumentException("Night mode needs between 1 and 50 playlist labels");
        }
        this.labels = labels.clone();

        // This stays inside the video layer: clocks, weather and notices keep their normal light.
        dimmer = new View(context);
        dimmer.setBackgroundColor(0x99000000);
        dimmer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        dimmer.setVisibility(View.GONE);
        videoLayer.addView(dimmer, new FrameLayout.LayoutParams(-1, -1));

        moon = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path crescent = new Path();
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (hasFocus()) {
                    paint.setColor(0xB8182734);
                    canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(12), dp(12), paint);
                }
                paint.setColor(hasFocus() ? 0xFFFFD44D : 0xCCFFFFFF);
                paint.setShadowLayer(dp(2), 0, dp(1), 0x99000000);
                float x = getWidth() / 2f, y = getHeight() / 2f;
                crescent.reset();
                crescent.moveTo(x + dp(5), y - dp(10));
                crescent.cubicTo(x - dp(13), y - dp(12), x - dp(14), y + dp(12), x + dp(2), y + dp(11));
                crescent.cubicTo(x + dp(8), y + dp(11), x + dp(11), y + dp(7), x + dp(12), y + dp(4));
                crescent.cubicTo(x + dp(1), y + dp(7), x - dp(4), y - dp(4), x + dp(5), y - dp(10));
                crescent.close();
                canvas.drawPath(crescent, paint);
                paint.clearShadowLayer();
            }
        };
        moon.setContentDescription("Mode nuit");
        moon.setFocusable(true);
        moon.setClickable(true);
        moon.setDefaultFocusHighlightEnabled(false);
        moon.setOnFocusChangeListener((v, focus) -> v.invalidate());
        moon.setOnClickListener(v -> { if (active) showControls(); else requestEntry(); });
        FrameLayout.LayoutParams moonPosition = new FrameLayout.LayoutParams(dp(48), dp(36),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        moonPosition.bottomMargin = dp(16);
        moon.setTranslationX(dp(64));
        root.addView(moon, moonPosition);
        moon.setVisibility(View.GONE);

        toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(8), dp(8), dp(8));
        toolbar.setBackground(background(0xE6121C28, 0x33FFFFFF, 18));
        playlistButton = button("Playlist", this::openPlaylist);
        lightButton = button("Lumière tamisée", this::toggleLight);
        photosButton = button("Photos", this::exit);
        buttons = new TextView[]{playlistButton, lightButton, photosButton};
        for (TextView button : buttons) {
            LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-2, dp(40));
            position.leftMargin = dp(3);
            position.rightMargin = dp(3);
            toolbar.addView(button, position);
        }
        FrameLayout.LayoutParams toolbarPosition = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        toolbarPosition.bottomMargin = dp(62);
        root.addView(toolbar, toolbarPosition);
        toolbar.setVisibility(View.GONE);

        status = text("", 14);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(3);
        status.setPadding(dp(14), dp(10), dp(14), dp(10));
        status.setBackground(background(0xDA121C28, 0, 12));
        FrameLayout.LayoutParams statusPosition = new FrameLayout.LayoutParams(dp(480), -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        statusPosition.bottomMargin = dp(132);
        root.addView(status, statusPosition);
        status.setVisibility(View.GONE);
    }

    void start() {
        if (detached || running) return;
        running = true;
        updateMoon();
    }

    /** Host lifecycle stop; release playback even if the window detaches a second time. */
    void stop() {
        running = false;
        exit();
        previousPhotoFocus = null;
        capturedKey = 0;
        ui.removeCallbacksAndMessages(null);
        updateMoon();
    }

    void pause() { if (active) playback.pause(); }
    void resume() { if (active && running) playback.resume(); }

    void detach() {
        if (detached) return;
        stop();
        detached = true;
        videoLayer.removeView(dimmer);
        root.removeView(moon);
        root.removeView(toolbar);
        root.removeView(status);
        previousPhotoFocus = null;
        entryAction = null;
    }

    /** The host hides entry while another full-screen page, such as football, owns the remote. */
    void setAvailable(boolean available) {
        this.available = available;
        updateMoon();
    }

    boolean isActive() { return active; }
    boolean ownsPhotoKeys() { return active || moon.hasFocus(); }

    void setExitLabel(String label) {
        photosButton.setText(label);
        photosButton.setContentDescription(label);
    }

    /** Optional host handoff: opening another activity leaves this controller inactive. */
    void setEntryAction(Runnable action) { entryAction = action; }

    /** Enter directly for a host that already owns the playback surface or service overlay. */
    void enterFromHost(int index) {
        if (index < 0 || index >= labels.length) {
            throw new IllegalArgumentException("Unknown night playlist index: " + index);
        }
        selected = index;
        enter();
    }

    /** Supply loading or native-player errors; an empty value removes the message. */
    void setStatus(String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(() -> setStatus(message));
            return;
        }
        if (detached || !active) return;
        status.setText(message == null ? "" : message);
        status.setVisibility(status.length() == 0 ? View.GONE : View.VISIBLE);
        status.bringToFront();
        if (modal != null) modal.bringToFront();
    }

    /** Call before photo and football arrow handlers, which otherwise consume these presses. */
    boolean handleKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP && key == capturedKey) {
            capturedKey = 0;
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || !isControlKey(key)) return false;
        if (event.getRepeatCount() > 0) return capturedKey == key;
        if (!running || detached) return false;
        if (!active) {
            if (!available) return false;
            if (moon.hasFocus()) {
                if (isConfirm(key)) {
                    capturedKey = key;
                    requestEntry();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // Return the same press to Scene so it still moves to the previous/next photo.
                    if (restorePhotoFocus()) return false;
                } else if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // This press leaves the moon; a later Down may open football normally.
                    restorePhotoFocus();
                }
                capturedKey = key;
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                previousPhotoFocus = root.findFocus();
                if (moon.requestFocus()) {
                    capturedKey = key;
                    return true;
                }
            }
            return false;
        }

        capturedKey = key;
        if (modal != null) {
            if (isConfirm(key)) choices[modalSelection].performClick();
            else if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                modalSelection = Math.max(0, Math.min(choices.length - 1,
                        modalSelection + (key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
                choices[modalSelection].requestFocus();
            }
            return true;
        }
        if (toolbar.getVisibility() != View.VISIBLE) {
            showControls();
            return true;
        }
        if (isConfirm(key)) buttons[toolbarSelection].performClick();
        else if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            toolbarSelection = Math.max(0, Math.min(buttons.length - 1,
                    toolbarSelection + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
            buttons[toolbarSelection].requestFocus();
        }
        scheduleHide();
        return true;
    }

    /** Back dismisses only the playlist first; a second Back restores the photos. */
    boolean back() {
        if (!active) return false;
        if (modal != null) closePlaylist(true);
        else exit();
        return true;
    }

    private void requestEntry() {
        if (!running || detached || !available || active) return;
        if (entryAction != null) entryAction.run();
        else enter();
    }

    private void enter() {
        if (!running || detached || !available || active) return;
        View focus = root.findFocus();
        if (focus != null && focus != moon) previousPhotoFocus = focus;
        active = true;
        status.setText("");
        status.setVisibility(View.GONE);
        listener.onEnter();
        videoLayer.setVisibility(View.VISIBLE);
        applyDimming();
        showControls();
        playback.play(selected);
        dimmer.bringToFront();
    }

    private void exit() {
        if (!active) return;
        active = false;
        ui.removeCallbacks(autoHide);
        closePlaylist(false);
        toolbar.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        dimmer.setVisibility(View.GONE);
        playback.stop();
        videoLayer.setVisibility(View.GONE);
        listener.onExit();
        updateMoon();
        if (running) restorePhotoFocus();
        previousPhotoFocus = null;
    }

    private boolean restorePhotoFocus() {
        if (previousPhotoFocus != null && previousPhotoFocus != moon
                && previousPhotoFocus.isShown() && previousPhotoFocus.isFocusable()
                && previousPhotoFocus.requestFocus()) return true;
        View fallback = moon.focusSearch(View.FOCUS_LEFT);
        if (fallback != null && fallback != moon && fallback.isShown()
                && fallback.isFocusable() && fallback.requestFocus()) return true;
        moon.clearFocus();
        return !moon.hasFocus();
    }

    private void toggleLight() {
        dimmed = !dimmed;
        applyDimming();
        scheduleHide();
    }

    private void applyDimming() {
        dimmer.setVisibility(active && dimmed ? View.VISIBLE : View.GONE);
        dimmer.bringToFront();
        lightButton.setText(dimmed ? "Lumière tamisée" : "Lumière normale");
        lightButton.setContentDescription((dimmed ? "Lumière tamisée. Passer en lumière normale"
                : "Lumière normale. Tamiser la lumière"));
    }

    private void showControls() {
        if (!active || modal != null) return;
        toolbar.setVisibility(View.VISIBLE);
        toolbar.bringToFront();
        buttons[toolbarSelection].requestFocus();
        updateMoon();
        scheduleHide();
    }

    private void hideControls() {
        ui.removeCallbacks(autoHide);
        if (!active || modal != null) return;
        toolbar.setVisibility(View.GONE);
        updateMoon();
        moon.requestFocus();
    }

    private void scheduleHide() {
        ui.removeCallbacks(autoHide);
        if (active && modal == null && toolbar.getVisibility() == View.VISIBLE) {
            ui.postDelayed(autoHide, CONTROLS_TIMEOUT_MS);
        }
    }

    private void openPlaylist() {
        if (!active || modal != null) return;
        ui.removeCallbacks(autoHide);
        toolbar.setVisibility(View.GONE);
        modal = new FrameLayout(context);
        modal.setBackgroundColor(0x99000000);
        modal.setClickable(true);
        root.addView(modal, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(24), dp(20));
        panel.setBackground(background(0xF5121C28, 0x44FFFFFF, 20));
        modal.addView(panel, new FrameLayout.LayoutParams(dp(500), -2, Gravity.CENTER));
        TextView title = text("Playlist · Mode nuit", 23);
        title.setTypeface(null, Typeface.BOLD);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = text("Choisissez une ambiance", 14);
        hint.setTextColor(0xFFB9C8D5);
        hint.setPadding(0, dp(6), 0, dp(16));
        panel.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(context);
        scroll.setFocusable(false);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(Math.min(labels.length * 60, 300))));
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        choices = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView choice = button((i == selected ? "●  " : "     ") + labels[i], () -> select(index));
            choice.setTextSize(17);
            choice.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            choice.setSingleLine(true);
            choice.setEllipsize(TextUtils.TruncateAt.END);
            choice.setOnFocusChangeListener((view, focus) -> {
                view.setBackground(background(focus ? 0xF0182734 : 0x80101823,
                        focus ? 0xFFFFD44D : 0x33FFFFFF, 12));
                if (focus) modalSelection = index;
            });
            LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, dp(52));
            position.topMargin = dp(8);
            list.addView(choice, position);
            choices[i] = choice;
        }
        TextView back = text("Retour : fermer la playlist", 12);
        back.setTextColor(0xFFB9C8D5);
        back.setPadding(0, dp(16), 0, 0);
        panel.addView(back, new LinearLayout.LayoutParams(-1, -2));
        modalSelection = selected;
        choices[modalSelection].requestFocus();
        updateMoon();
    }

    private void select(int index) {
        selected = index;
        status.setText("");
        status.setVisibility(View.GONE);
        closePlaylist(false);
        playback.play(index);
        dimmer.bringToFront();
        hideControls();
    }

    private void closePlaylist(boolean restoreControls) {
        if (modal == null) return;
        root.removeView(modal);
        modal = null;
        choices = null;
        if (restoreControls) {
            toolbarSelection = 0;
            showControls();
        }
    }

    private void updateMoon() {
        moon.setVisibility(!detached && running && (active
                ? modal == null && toolbar.getVisibility() != View.VISIBLE : available)
                ? View.VISIBLE : View.GONE);
        moon.setContentDescription(active ? "Afficher les commandes du mode nuit" : "Mode nuit");
    }

    private TextView button(String label, Runnable action) {
        TextView button = text(label, 15);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setFocusable(true);
        button.setClickable(true);
        button.setDefaultFocusHighlightEnabled(false);
        button.setBackground(background(0x80101823, 0x33FFFFFF, 12));
        button.setOnFocusChangeListener((view, focus) -> {
            view.setBackground(background(focus ? 0xF0182734 : 0x80101823,
                    focus ? 0xFFFFD44D : 0x33FFFFFF, 12));
            if (focus && buttons != null) {
                for (int i = 0; i < buttons.length; i++) if (buttons[i] == view) toolbarSelection = i;
                scheduleHide();
            }
        });
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private TextView text(String label, int size) {
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(size);
        text.setTextColor(Color.WHITE);
        return text;
    }

    private GradientDrawable background(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(2), stroke);
        return drawable;
    }

    private boolean isConfirm(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private boolean isControlKey(int key) {
        return isConfirm(key) || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
