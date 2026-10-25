package com.gathin.ambienttv;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/** Optional Back-on-Home shortcut. Observes window identity only; never retrieves window contents. */
public final class HomeBackService extends AccessibilityService {
    private static volatile boolean connected;
    private final HomeBackState state = new HomeBackState();

    static boolean connected() { return connected; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        state.reset();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            state.window(event.getPackageName(), event.getClassName());
            TizenNightService.windowChanged(event.getPackageName(), event.getClassName());
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (TizenNightService.isRunning() && (event.getKeyCode() == KeyEvent.KEYCODE_HOME
                || event.getKeyCode() == KeyEvent.KEYCODE_ALL_APPS)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) TizenNightService.stopForDeparture();
            return false;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) return state.begin();
            return state.captured();
        }
        if (event.getAction() == KeyEvent.ACTION_UP && state.captured()) {
            if (state.finish(event.isCanceled())) {
                Intent intent = new Intent(this, SlideshowActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    Toast.makeText(this, "Impossible d’ouvrir les photos", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        return false;
    }

    @Override public void onInterrupt() { state.reset(); TizenNightService.helperDisconnected(); }

    @Override public void onDestroy() {
        connected = false;
        TizenNightService.helperDisconnected();
        state.reset();
        super.onDestroy();
    }
}
