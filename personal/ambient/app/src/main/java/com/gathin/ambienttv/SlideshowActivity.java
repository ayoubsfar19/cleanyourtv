package com.gathin.ambienttv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * The module card on cleanyourtv's Home: the same scene as the screensaver, right away, until Back. Holding OK
 * or pressing Menu opens the settings. The screen stays on while it shows.
 */
public class SlideshowActivity extends Activity {

    private Scene scene;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Built again on every start, so options changed in the settings show at once.
        scene = new Scene(this);
        setContentView(scene.root);
        scene.start();
    }

    @Override
    protected void onPause() {
        if (scene != null) scene.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scene != null) scene.resume();
    }

    @Override
    protected void onStop() {
        if (scene != null) scene.stop();
        scene = null;
        super.onStop();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (scene != null && scene.nightKey(event)) return true;
        if (scene != null && scene.verticalKey(event)) return true;
        if (scene != null && scene.photoKey(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && (scene == null || !scene.back())) finish();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
