package com.gathin.ambienttv;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/** A brief reminder tone mixed with existing playback. All calls belong to the main thread. */
final class AgendaChime {
    private static final String REMINDER_CHANNEL = "agenda_reminders";
    private static final int VOLUME_PERCENT = 12;
    private static final int DURATION_MS = 150;
    private static final long RELEASE_DELAY_MS = 350;

    private final AudioManager audio;
    private final NotificationManager notifications;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable release = this::stop;
    private ToneGenerator tone;

    AgendaChime(Context context) {
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    void play() {
        if (!soundAllowed()) return;
        stop();
        try {
            // No audio focus or volume changes: ongoing video and Night music keep playing.
            tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME_PERCENT);
            if (!tone.startTone(ToneGenerator.TONE_PROP_BEEP, DURATION_MS)
                    || !main.postDelayed(release, RELEASE_DELAY_MS)) {
                stop();
            }
        } catch (RuntimeException unavailable) {
            stop();
        }
    }

    void stop() {
        main.removeCallbacks(release);
        ToneGenerator previous = tone;
        tone = null;
        if (previous == null) return;
        try { previous.stopTone(); }
        catch (RuntimeException ignored) { /* Some TV audio backends can disappear mid-tone. */ }
        try { previous.release(); }
        catch (RuntimeException ignored) { /* Cleanup must not interrupt the reminder service. */ }
    }

    private boolean soundAllowed() {
        if (audio == null || notifications == null) return false;
        try {
            if (!notifications.areNotificationsEnabled()) return false;
            NotificationChannel channel = notifications.getNotificationChannel(REMINDER_CHANNEL);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
                return false;
            return audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL
                    && !audio.isStreamMute(AudioManager.STREAM_NOTIFICATION)
                    && audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0
                    && notifications.getCurrentInterruptionFilter()
                    == NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (RuntimeException unavailable) {
            // When the TV cannot report its quiet settings, leave the reminder silent.
            return false;
        }
    }
}
