package com.gathin.ambienttv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** A small passive view above night video; host calls constructor and lifecycle on the UI thread. */
final class AdhkarOverlay {
    private static final long ROTATION_MS = 25_000L;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Context context;
    private final LinearLayout card;
    private final TextView label, text;
    private boolean running;
    private String videoId;
    private int index;
    private AdhkarContent.Period period;

    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (!running || !AdhkarContent.supportsVideo(videoId)) {
                card.setVisibility(View.GONE);
                return;
            }
            render(true);
            ui.postDelayed(this, ROTATION_MS);
        }
    };

    AdhkarOverlay(Context context, FrameLayout root) {
        this.context = context;
        card = new LinearLayout(context) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int limit = dp(120);
                if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                    limit = Math.min(limit, MeasureSpec.getSize(heightMeasureSpec));
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(10), dp(16), dp(12));
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setGravity(Gravity.RIGHT);
        card.setFocusable(false);
        card.setFocusableInTouchMode(false);
        card.setClickable(false);
        card.setLongClickable(false);
        card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(126, 24, 27, 33), Color.argb(92, 12, 15, 21)});
        glass.setCornerRadius(dp(12));
        glass.setStroke(dp(1), Color.argb(68, 215, 184, 128));
        card.setBackground(glass);

        label = arabicText();
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        label.setTextColor(Color.rgb(224, 199, 151));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(label, new LinearLayout.LayoutParams(-1, -2));

        text = arabicText();
        text.setTextColor(Color.rgb(247, 245, 239));
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setMaxLines(3);
        // Bound this passive widget's footprint while fitting every complete short quotation.
        text.setAutoSizeTextTypeUniformWithConfiguration(14, 20, 1, TypedValue.COMPLEX_UNIT_DIP);
        text.setMaxHeight(dp(82));
        text.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams textPosition = new LinearLayout.LayoutParams(-1, -2);
        textPosition.topMargin = dp(4);
        card.addView(text, textPosition);

        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(dp(340), -2,
                Gravity.BOTTOM | Gravity.LEFT);
        position.leftMargin = dp(28);
        position.bottomMargin = dp(26);
        card.setVisibility(View.GONE);
        root.addView(card, position);
    }

    /** Call before changing video: a non-qualifying ID hides immediately and cancels rotation. */
    void select(String selectedVideoId) {
        if (videoId == null ? selectedVideoId == null : videoId.equals(selectedVideoId)) return;
        videoId = selectedVideoId;
        reset();
        showIfAllowed();
    }

    void start() {
        if (running) return;
        running = true;
        showIfAllowed();
    }

    void stop() {
        running = false;
        reset();
    }

    private void reset() {
        ui.removeCallbacks(rotate);
        card.setVisibility(View.GONE);
        period = null;
        index = 0;
    }

    private void showIfAllowed() {
        if (!running || !AdhkarContent.supportsVideo(videoId)) return;
        render(false);
        ui.postDelayed(rotate, ROTATION_MS);
    }

    private void render(boolean advance) {
        // Resolve the default zone each time so a device timezone change is picked up as well.
        AdhkarContent.Period current = AdhkarContent.periodAt(System.currentTimeMillis(), TimeZone.getDefault());
        if (current != period) {
            period = current;
            index = 0;
        } else if (advance) {
            index = (index + 1) % AdhkarContent.cards(period).size();
        }
        List<String> cards = AdhkarContent.cards(period);
        label.setText(period.label);
        text.setText(cards.get(index));
        card.setVisibility(View.VISIBLE);
    }

    private TextView arabicText() {
        TextView view = new TextView(context);
        view.setTextLocale(new Locale("ar"));
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        view.setTextDirection(View.TEXT_DIRECTION_RTL);
        view.setGravity(Gravity.RIGHT);
        view.setIncludeFontPadding(false);
        view.setShadowLayer(dp(2), 0, dp(1), Color.argb(180, 0, 0, 0));
        view.setFocusable(false);
        view.setClickable(false);
        view.setLongClickable(false);
        view.setTextIsSelectable(false);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
