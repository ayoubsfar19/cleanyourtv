package com.gathin.ambienttv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** User-visible permission/entry gate for the explicitly requested TizenTube night overlay. */
public final class NightAccessActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean launched, waitingForHelper, resumed;
    private int helperChecks;
    private TextView message, allowOverlay, allowHelper, begin;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TizenNightService.recoverVolume(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(64), dp(32), dp(64), dp(32));
        panel.setBackgroundColor(0xff0d1420);
        TextView title = text("Mode nuit", 30);
        panel.addView(title);
        message = text("", 18);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(18), 0, dp(18));
        panel.addView(message, new LinearLayout.LayoutParams(-1, -2));
        allowOverlay = button("Autoriser l’affichage au-dessus de TizenTube", () -> openSettings(
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
        allowHelper = button("Activer « " + getString(R.string.home_back_label) + " »", () -> openSettings(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        begin = button("Démarrer", this::tryStart);
        panel.addView(allowOverlay);
        panel.addView(allowHelper);
        panel.addView(begin);
        panel.addView(button("Retour aux photos", this::returnToPhotos));
        setContentView(panel);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        helperChecks = 0;
        if (launched) return;
        if (Settings.canDrawOverlays(this) && helperEnabled() && !HomeBackService.connected()
                && !waitingForHelper) {
            waitingForHelper = true;
            main.postDelayed(() -> { waitingForHelper = false; if (!isFinishing()) tryStart(); }, 700);
        } else tryStart();
    }

    private void tryStart() {
        if (launched || isFinishing() || !resumed) return;
        boolean overlay = Settings.canDrawOverlays(this);
        boolean helper = HomeBackService.connected();
        if (overlay && !helper && helperEnabled() && helperChecks++ < 8) {
            message.setText("Préparation du mode nuit…");
            main.postDelayed(this::tryStart, 350);
            return;
        }
        allowOverlay.setVisibility(overlay ? View.GONE : View.VISIBLE);
        allowHelper.setVisibility(helper ? View.GONE : View.VISIBLE);
        begin.setVisibility(overlay && helper ? View.VISIBLE : View.GONE);
        if (!overlay || !helper) {
            message.setText(!overlay
                    ? "Autorisez les widgets au-dessus de TizenTube pour garder l’heure, la météo et les commandes."
                    : "Activez « " + getString(R.string.home_back_label)
                            + " » dans les services d’accessibilité pour fermer le mode nuit en quittant TizenTube.");
            (overlay ? allowHelper : allowOverlay).requestFocus();
            return;
        }
        try {
            launched = true;
            startForegroundService(new Intent(this, TizenNightService.class)
                    .putExtra(TizenNightService.EXTRA_INDEX, 0));
            finish();
        } catch (RuntimeException unavailable) {
            launched = false;
            message.setText("Impossible de démarrer le mode nuit. Revenez aux photos puis réessayez.");
            begin.requestFocus();
        }
    }

    private boolean helperEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            ComponentName expected = new ComponentName(this, HomeBackService.class);
            for (String value : enabled.split(":")) {
                if (expected.equals(ComponentName.unflattenFromString(value))) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (RuntimeException unavailable) {
            Toast.makeText(this, "Ouvrez les autorisations dans les réglages du téléviseur.", Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        return view;
    }

    private TextView button(String value, Runnable action) {
        TextView view = text(value, 17);
        view.setPadding(dp(22), dp(12), dp(22), dp(12));
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(background(false));
        view.setOnFocusChangeListener((v, focused) -> view.setBackground(background(focused)));
        view.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(10);
        view.setLayoutParams(lp);
        return view;
    }

    private GradientDrawable background(boolean focus) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(focus ? 0xff294259 : 0xff172434);
        shape.setCornerRadius(dp(14));
        shape.setStroke(dp(1), focus ? Color.WHITE : 0x44ffffff);
        return shape;
    }

    private void returnToPhotos() {
        startActivity(new Intent(this, SlideshowActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override public void onBackPressed() { returnToPhotos(); }
    @Override protected void onPause() {
        resumed = false;
        waitingForHelper = false;
        main.removeCallbacksAndMessages(null);
        super.onPause();
    }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
