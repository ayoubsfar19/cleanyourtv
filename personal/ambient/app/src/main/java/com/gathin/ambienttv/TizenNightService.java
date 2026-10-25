package com.gathin.ambienttv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.List;

/** Explicit, non-sticky widgets and controls above the user's installed TizenTube player. */
public final class TizenNightService extends Service {
    static final String TIZEN = "io.gh.reisxd.tizentube.cobalt";
    static final String EXTRA_INDEX = "index";
    private static final String ACTION_EXIT = "com.gathin.ambienttv.NIGHT_PHOTOS";
    private static final String CHANNEL = "night_mode";
    private static final int NOTIFICATION_ID = 41;
    private static final long STARTUP_TIMEOUT_MS = 6_000L;
    private static final String VOLUME_PREFS = "night_volume", VOLUME_BEFORE = "before", VOLUME_APPLIED = "applied";
    private static volatile TizenNightService running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private AudioManager audioManager;
    private FrameLayout root;
    private NightMode night;
    private NightWidgets widgets;
    private NightOverlayState state;
    private List<NightLibrary.Entry> entries;
    private boolean attached, receiverRegistered, ending;
    private int previousVolume = -1, ownVolume = -1;

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) finishSession(false);
        }
    };

    static boolean isRunning() { return running != null; }

    /** Recover only this session's unchanged cap after process death, without starting a service. */
    static void recoverVolume(Context context) {
        if (isRunning()) return;
        SharedPreferences ledger = context.getSharedPreferences(VOLUME_PREFS, MODE_PRIVATE);
        int before = ledger.getInt(VOLUME_BEFORE, -1);
        int applied = ledger.getInt(VOLUME_APPLIED, -1);
        if (before < 0 || applied < 0) {
            if (ledger.contains(VOLUME_BEFORE) || ledger.contains(VOLUME_APPLIED)) {
                ledger.edit().remove(VOLUME_BEFORE).remove(VOLUME_APPLIED).apply();
            }
            return;
        }
        AudioManager audio = (AudioManager) context.getSystemService(AUDIO_SERVICE);
        try {
            if (audio != null && !audio.isVolumeFixed()
                    && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == applied) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0);
            }
        } catch (RuntimeException ignored) { /* Volume support is optional; playback remains available. */ }
        ledger.edit().remove(VOLUME_BEFORE).remove(VOLUME_APPLIED).apply();
    }

    /** The existing helper supplies only the package/class identity of window changes. */
    static void windowChanged(CharSequence packageName, CharSequence className) {
        TizenNightService service = running;
        if (service == null) return;
        String name = packageName == null ? null : packageName.toString();
        String type = className == null ? null : className.toString();
        service.onMain(() -> service.observeWindow(name, type));
    }

    static void helperDisconnected() { stopForDeparture(); }

    /** Home, another app or helper loss removes the overlay without opening another activity. */
    static void stopForDeparture() {
        TizenNightService service = running;
        if (service != null) service.onMain(() -> service.finishSession(false));
    }

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            finishSession(false);
            return START_NOT_STICKY;
        }
        if (ACTION_EXIT.equals(intent.getAction())) {
            finishSession(true);
            return START_NOT_STICKY;
        }
        if (ending || !intent.hasExtra(EXTRA_INDEX)) {
            finishSession(false);
            return START_NOT_STICKY;
        }
        if (night != null) return START_NOT_STICKY;
        try {
            recoverVolume(this);
            showForegroundNotification();
            if (!Settings.canDrawOverlays(this)) {
                failToPhotos("Autorisez l’affichage au-dessus des applications pour le mode nuit.");
                return START_NOT_STICKY;
            }
            if (!HomeBackService.connected()) {
                failToPhotos("Activez le raccourci Retour dans les options du mode nuit, puis réessayez.");
                return START_NOT_STICKY;
            }
            startOverlay(intent.getIntExtra(EXTRA_INDEX, 0));
        } catch (RuntimeException failure) {
            failToPhotos("Impossible d’ouvrir le mode nuit. Vérifiez les autorisations puis réessayez.");
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        finishSession(false);
        super.onDestroy();
    }

    private void startOverlay(int index) {
        entries = NightLibrary.load(this);
        if (index < 0 || index >= entries.size()) index = 0;
        state = new NightOverlayState(getPackageName(), TIZEN);
        root = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (ending || night == null) return super.dispatchKeyEvent(event);
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) night.back();
                    return true;
                }
                if (night.handleKey(event)) return true;
                return super.dispatchKeyEvent(event);
            }
        };
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setFocusableInTouchMode(true);
        FrameLayout videoLayer = new FrameLayout(this);
        videoLayer.setBackgroundColor(Color.TRANSPARENT);
        videoLayer.setVisibility(View.GONE);
        root.addView(videoLayer, new FrameLayout.LayoutParams(-1, -1));
        widgets = new NightWidgets(this, root);
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) labels[i] = entries.get(i).title;
        NightMode.Playback playback = new NightMode.Playback() {
            @Override public void play(int selection) { launchVideo(selection); }
            @Override public void stop() { }
            @Override public void pause() { }
            @Override public void resume() { }
        };
        night = new NightMode(this, root, videoLayer, playback, new NightMode.Listener() {
            @Override public void onEnter() { }
            @Override public void onExit() { if (!ending) finishSession(true); }
        }, labels);
        WindowManager.LayoutParams position = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        position.gravity = Gravity.TOP | Gravity.LEFT;
        position.setTitle("Mode nuit");
        windowManager.addView(root, position);
        attached = true;
        running = this;
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }
        receiverRegistered = true;
        quietVolume();
        widgets.start();
        night.start();
        main.postDelayed(() -> {
            if (!ending && !state.playerSeen()) {
                Toast.makeText(this, "TizenTube n’a pas démarré. Relancez le mode nuit depuis les photos.",
                        Toast.LENGTH_LONG).show();
                finishSession(false);
            }
        }, STARTUP_TIMEOUT_MS);
        night.enterFromHost(index);
    }

    private void launchVideo(int index) {
        if (ending || entries == null || index < 0 || index >= entries.size()) return;
        String id = entries.get(index).id;
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) {
            failToPhotos("Cette vidéo est indisponible. Choisissez une autre ambiance.");
            return;
        }
        Intent player = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id))
                .setPackage(TIZEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(player);
        } catch (ActivityNotFoundException | SecurityException failure) {
            failToPhotos("Impossible d’ouvrir TizenTube. Vérifiez que l’application est installée.");
        }
    }

    private void observeWindow(String packageName, String className) {
        if (!ending && state != null && state.window(packageName, className)) finishSession(false);
    }

    private void showForegroundNotification() {
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Mode nuit",
                NotificationManager.IMPORTANCE_LOW));
        Intent photos = new Intent(this, TizenNightService.class).setAction(ACTION_EXIT);
        PendingIntent exit = PendingIntent.getService(this, 0, photos,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle("Mode nuit")
                .setContentText("TizenTube · horloge et météo. Touchez pour revenir aux photos.")
                .setContentIntent(exit)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void quietVolume() {
        if (audioManager == null) return;
        try {
            if (audioManager.isVolumeFixed()) return;
            previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int minimum = Build.VERSION.SDK_INT >= 28
                    ? audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC) : 0;
            int target = Math.min(previousVolume, Math.max(minimum, Math.round(maximum * 0.15f)));
            if (target < previousVolume) {
                // Commit before changing global volume so an interrupted process can recover it.
                if (!getSharedPreferences(VOLUME_PREFS, MODE_PRIVATE).edit()
                        .putInt(VOLUME_BEFORE, previousVolume).putInt(VOLUME_APPLIED, target).commit()) return;
                ownVolume = target;
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            }
        } catch (RuntimeException ignored) { /* Fixed or restricted TV audio must not prevent video. */ }
    }

    private void restoreVolume() {
        int before = previousVolume, applied = ownVolume;
        previousVolume = ownVolume = -1;
        try {
            if (audioManager != null && before >= 0 && applied >= 0 && !audioManager.isVolumeFixed()
                    && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == applied) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0);
            }
        } catch (RuntimeException ignored) { }
        getSharedPreferences(VOLUME_PREFS, MODE_PRIVATE).edit()
                .remove(VOLUME_BEFORE).remove(VOLUME_APPLIED).apply();
    }

    private void finishSession(boolean returnToPhotos) {
        if (ending) return;
        boolean pausePlayer = returnToPhotos && state != null && state.canPausePlayer();
        ending = true;
        if (running == this) running = null;
        // Clear ownership before calling code that can synchronously reenter this service.
        boolean removeReceiver = receiverRegistered, removeWindow = attached;
        NightMode oldNight = night;
        NightWidgets oldWidgets = widgets;
        FrameLayout oldRoot = root;
        receiverRegistered = attached = false;
        night = null;
        widgets = null;
        root = null;
        state = null;
        entries = null;
        try {
            attemptCleanup(() -> main.removeCallbacksAndMessages(null));
            if (pausePlayer && audioManager != null) {
                attemptCleanup(() -> audioManager.dispatchMediaKeyEvent(
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)));
                attemptCleanup(() -> audioManager.dispatchMediaKeyEvent(
                        new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)));
            }
            if (removeReceiver) attemptCleanup(() -> unregisterReceiver(screenOff));
            if (oldNight != null) attemptCleanup(oldNight::detach);
            if (oldWidgets != null) attemptCleanup(oldWidgets::stop);
            if (removeWindow && oldRoot != null) {
                attemptCleanup(() -> windowManager.removeViewImmediate(oldRoot));
            }
        } finally {
            attemptCleanup(this::restoreVolume);
            attemptCleanup(() -> stopForeground(STOP_FOREGROUND_REMOVE));
            attemptCleanup(this::stopSelf);
        }
        if (returnToPhotos) {
            try {
                startActivity(new Intent(this, SlideshowActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            } catch (RuntimeException ignored) {
                attemptCleanup(() -> Toast.makeText(this, "Impossible d’ouvrir les photos", Toast.LENGTH_SHORT).show());
            }
        }
    }

    private static void attemptCleanup(Runnable action) {
        try { action.run(); }
        catch (RuntimeException ignored) { /* Every other teardown step must still be attempted. */ }
    }

    private void failToPhotos(String message) {
        try { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
        finally { finishSession(true); }
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }
}
