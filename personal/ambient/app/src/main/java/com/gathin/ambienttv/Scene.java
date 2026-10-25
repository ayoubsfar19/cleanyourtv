package com.gathin.ambienttv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the screensaver and the card's slideshow show: the pictures (full screen, or as prints), and on top what
 * the options switch on: the time and date, the weather, the sky behind it all. Both hosts call start and stop.
 */
final class Scene {

    private static final long MINUTE_MS = 60_000L;
    /** How far the bottom line may wander so it does not burn in. */
    private static final int NUDGE_DP = 20, NUDGE_UP_DP = 6;

    final FrameLayout root;
    private final Context context;
    private final Options options;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final SkyView sky;
    private final View bottomLine;
    private final TextView weather;
    private final Slides slides;
    private final Prints prints;
    private final SportsHub sports;
    private final WifiStatus wifi;
    private boolean running;
    private int photoArrowKey;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (sky != null) sky.update();
            updateDate();
            nudge();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    Scene(Context context) {
        this.context = context;
        options = Options.load(context);
        root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        if (options.sky && options.prints) {
            sky = new SkyView(context);
            root.addView(sky, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            sky = null;
        }
        FrameLayout stage = new FrameLayout(context);
        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View overlay = LayoutInflater.from(context).inflate(R.layout.overlay, root, false);
        root.addView(overlay);
        bottomLine = overlay.findViewById(R.id.bottomLine);
        weather = overlay.findViewById(R.id.weather);
        TextView caption = overlay.findViewById(R.id.caption);
        View empty = overlay.findViewById(R.id.empty);
        File dir = context.getExternalFilesDir(null);
        ((TextView) overlay.findViewById(R.id.emptyPath)).setText(
                dir == null ? "" : new File(dir, Media.PICTURES).getPath());

        int clockVisibility = options.clock ? View.VISIBLE : View.GONE;
        overlay.findViewById(R.id.time).setVisibility(clockVisibility);
        overlay.findViewById(R.id.ampm).setVisibility(clockVisibility);
        overlay.findViewById(R.id.date).setVisibility(clockVisibility);
        weather.setVisibility(View.GONE);
        bottomLine.setVisibility(options.clock || options.weather ? View.VISIBLE : View.GONE);
        // In full-screen mode the pictures cover everything: a dark fade keeps the bottom line readable.
        overlay.findViewById(R.id.bottomFade).setVisibility(
                !options.prints && (options.clock || options.weather) ? View.VISIBLE : View.GONE);

        if (options.prints) {
            caption.setVisibility(View.GONE);
            prints = new Prints(context, stage, options.clock || options.weather ? bottomLine : null, empty,
                    options.dates);
            slides = null;
        } else {
            slides = new Slides(context, stage, options.dates ? caption : null, empty);
            prints = null;
            if (!options.dates) caption.setVisibility(View.GONE);
        }
        bottomLine.bringToFront();
        sports = new SportsHub(context, root);
        wifi = new WifiStatus(context);
        FrameLayout.LayoutParams wifiPosition = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        float density = context.getResources().getDisplayMetrics().density;
        wifiPosition.topMargin = Math.round(28 * density);
        wifiPosition.rightMargin = Math.round(32 * density);
        root.addView(wifi.view, wifiPosition);
        updateDate();
    }

    void start() {
        if (running) return;
        running = true;
        sports.start();
        wifi.start();
        if (slides != null) slides.start();
        if (prints != null) prints.start();
        handler.post(tick);

    }

    void stop() {
        running = false;
        photoArrowKey=0;
        sports.stop();
        wifi.stop();
        handler.removeCallbacksAndMessages(null);
        if (slides != null) slides.stop();
        if (prints != null) prints.stop();
    }

    private void updateDate() {
        android.widget.TextClock date = root.findViewById(R.id.date);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.FRANCE);
        f.setTimeZone(java.util.TimeZone.getDefault());
        String value="'"+f.format(new java.util.Date())+"'";
        date.setFormat12Hour(value);date.setFormat24Hour(value);
        for(int id : new int[]{R.id.time,R.id.date,R.id.ampm}) {
            ((android.widget.TextClock)root.findViewById(id)).setTimeZone(null);
        }
    }

    /** Consume photo arrows only on the ambient photo layer, never in the sports navigation. */
    boolean photoKey(android.view.KeyEvent event) {
        int key=event.getKeyCode();
        if(key!=android.view.KeyEvent.KEYCODE_DPAD_LEFT && key!=android.view.KeyEvent.KEYCODE_DPAD_RIGHT)return false;
        if(event.getAction()==android.view.KeyEvent.ACTION_UP && photoArrowKey==key){photoArrowKey=0;return true;}
        if(event.getAction()!=android.view.KeyEvent.ACTION_DOWN)return false;
        if(event.getRepeatCount()>0)return photoArrowKey==key;
        if(!running || sports.isOpen() || slides==null)return false;
        if(!slides.navigate(key==android.view.KeyEvent.KEYCODE_DPAD_RIGHT?1:-1))return false;
        photoArrowKey=key;
        return true;
    }

    boolean back() { return sports.back(); }

    boolean verticalKey(android.view.KeyEvent event) {
        return running && sports.handleVerticalKey(event);
    }

    private void nudge() {
        if (bottomLine.getVisibility() != View.VISIBLE) return;
        float d = context.getResources().getDisplayMetrics().density;
        bottomLine.animate().translationX((random.nextFloat() * 2 - 1) * NUDGE_DP * d)
                .translationY(random.nextFloat() * -NUDGE_UP_DP * d).setDuration(1500).start();
    }

}
