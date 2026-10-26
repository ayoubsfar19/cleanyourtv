package com.gathin.ambienttv;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-enabled calendar reminders, independent of photos, Night mode and the Home helper. */
public final class AgendaService extends Service {
    static final String ACTION_SYNC = "com.gathin.ambienttv.AGENDA_SYNC";
    static final String ACTION_RELOAD = "com.gathin.ambienttv.AGENDA_RELOAD";
    static final String ACTION_STOP = "com.gathin.ambienttv.AGENDA_STOP";
    private static final String CHANNEL = "agenda_monitor", REMINDERS = "agenda_reminders";
    private static final int FOREGROUND_ID = 54, REMINDER_ID = 55;
    private static final long DAY = 86_400_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean fetching = new AtomicBoolean();
    private final AtomicBoolean rebuildRequested = new AtomicBoolean();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private PowerManager power;
    private WindowManager windows;
    private LinearLayout overlay;
    private AgendaChime chime;
    private volatile boolean ending;
    private volatile long skipThrough;
    private volatile long nextPoll;
    private long lastPrune, rebuiltDay = Long.MIN_VALUE;
    private boolean started, registered;
    private final Runnable dismissOverlay = this::removeOverlay;

    private static final class Alert {
        final AgendaEvent event;
        final AgendaReminderPolicy.Reminder reminder;
        Alert(AgendaEvent event, AgendaReminderPolicy.Reminder reminder) {
            this.event = event;
            this.reminder = reminder;
        }
    }

    /** Called from the visible settings screen; receiver restarts never enable an opted-out service. */
    static boolean start(Context context) {
        if (!AgendaStore.configured(context)) return false;
        AgendaStore.setEnabled(context, true);
        try {
            context.startForegroundService(new Intent(context, AgendaService.class).setAction(ACTION_SYNC));
            return true;
        } catch (RuntimeException denied) {
            AgendaStore.setEnabled(context, false);
            return false;
        }
    }

    static void stop(Context context) {
        AgendaStore.setEnabled(context, false);
        context.stopService(new Intent(context, AgendaService.class));
    }

    static void syncNow(Context context) {
        if (!AgendaStore.enabled(context) || !AgendaStore.configured(context)) return;
        try { context.startForegroundService(new Intent(context, AgendaService.class).setAction(ACTION_SYNC)); }
        catch (RuntimeException ignored) { /* Settings remains usable if Android defers the service. */ }
    }

    @Override public void onCreate() {
        super.onCreate();
        power = (PowerManager) getSystemService(POWER_SERVICE);
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        chime = new AgendaChime(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AgendaStore.setEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!AgendaStore.enabled(this) || !AgendaStore.configured(this) || ending) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            foreground();
            if (!started) {
                started = true;
                // A newly started monitor arms future deadlines only, including from the offline cache.
                skipThrough = System.currentTimeMillis();
                rebuiltDay = Instant.ofEpochMilli(skipThrough).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();
                nextPoll = skipThrough + (interactive() ? 60_000 : 300_000);
                IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                screen.addAction(Intent.ACTION_SCREEN_ON);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenChanges, screen, Context.RECEIVER_NOT_EXPORTED);
                else registerReceiver(screenChanges, screen);
                registered = true;
                clock.scheduleWithFixedDelay(this::tickSafely, 0, 10, TimeUnit.SECONDS);
                requestSync(true);
            } else if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
                requestSync(true);
            } else if (intent != null && ACTION_SYNC.equals(intent.getAction())) {
                requestSync(false);
            }
        } catch (RuntimeException unavailable) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private final BroadcastReceiver screenChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            removeOverlay();
            if (chime != null) chime.stop();
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                // Consume deadlines that passed while the TV was off, even between two 10-second ticks.
                skipThrough = System.currentTimeMillis();
                nextPoll = 0;
            }
            if (!ending) clock.execute(AgendaService.this::tickSafely);
        }
    };

    private boolean interactive() {
        return power != null && power.isInteractive();
    }

    private void tickSafely() {
        if (ending) return;
        try {
            if (!AgendaStore.enabled(this) || !AgendaStore.configured(this)) {
                main.post(this::stopSelf);
                return;
            }
            long now = System.currentTimeMillis();
            boolean awake = interactive();
            evaluate(now, awake);
            long day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();
            if (now >= nextPoll || day != rebuiltDay) {
                nextPoll = now + (awake ? 60_000 : 300_000);
                requestSync(day != rebuiltDay);
                rebuiltDay = day;
            }
        } catch (RuntimeException ignored) { /* An optional reminder cannot take down the host app. */ }
    }

    private void requestSync(boolean rebuild) {
        if (rebuild) rebuildRequested.set(true);
        if (ending || !fetching.compareAndSet(false, true)) return;
        try { network.execute(() -> {
            try {
                if (rebuildRequested.getAndSet(false)) AgendaStore.rebuildCache(this);
                if (!ending && AgendaStore.enabled(this)) AgendaStore.synchronize(this);
            } finally {
                fetching.set(false);
                if (!ending && rebuildRequested.get()) requestSync(false);
                if (!ending) {
                    try { clock.execute(this::tickSafely); } catch (RuntimeException ignored) { }
                }
            }
        }); } catch (RuntimeException stopped) { fetching.set(false); }
    }

    private void evaluate(long now, boolean awake) {
        final String revision = AgendaStore.sourceRevision(this);
        if (revision.isEmpty()) return;
        SharedPreferences p = AgendaStore.preferences(this);
        SharedPreferences.Editor edits = p.edit();
        List<Alert> due = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        int fallback = AgendaStore.defaultMinutes(this);
        boolean repeated = AgendaStore.repeated(this);
        boolean changed = false;
        for (AgendaEvent event : AgendaStore.load(this)) {
            if (event.endMillis < now - DAY) continue;
            for (AgendaReminderPolicy.Reminder reminder : AgendaReminderPolicy.reminders(event, fallback, zone, repeated)) {
                long when = reminder.when;
                String key = AgendaReminderPolicy.reminderKey(event, reminder);
                String seenKey = "seen_" + key, doneKey = "done_" + key;
                long firstSeen = p.getLong(seenKey, 0);
                if (firstSeen == 0) {
                    firstSeen = now;
                    edits.putLong(seenKey, now);
                    changed = true;
                }
                if (p.contains(doneKey)) continue;
                AgendaReminderPolicy.Decision decision = AgendaReminderPolicy.decide(now, when,
                        Math.max(firstSeen, skipThrough), awake, false);
                if (decision == AgendaReminderPolicy.Decision.WAIT) continue;
                // Persist before presentation so process death or an overlay failure cannot repeat it.
                edits.putLong(doneKey, now);
                changed = true;
                if (decision == AgendaReminderPolicy.Decision.SHOW) due.add(new Alert(event, reminder));
            }
        }
        if (now - lastPrune > DAY) {
            for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
                if ((entry.getKey().startsWith("seen_") || entry.getKey().startsWith("done_"))
                        && entry.getValue() instanceof Long && (Long) entry.getValue() < now - 35 * DAY) {
                    edits.remove(entry.getKey());
                    changed = true;
                }
            }
            lastPrune = now;
        }
        synchronized (AgendaStore.class) {
            if (!AgendaStore.enabled(this) || !revision.equals(AgendaStore.sourceRevision(this))) return;
            if (changed && !edits.commit()) return;
        }
        if (!due.isEmpty()) main.post(() -> {
            if (revision.equals(AgendaStore.sourceRevision(this))) showReminders(due);
        });
    }

    private void foreground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) throw new IllegalStateException("Notifications indisponibles");
        NotificationChannel monitor = new NotificationChannel(CHANNEL, "Agenda actif", NotificationManager.IMPORTANCE_LOW);
        monitor.setSound(null, null);
        monitor.enableVibration(false);
        manager.createNotificationChannel(monitor);
        NotificationChannel reminders = new NotificationChannel(REMINDERS, "Rappels de l’agenda", NotificationManager.IMPORTANCE_DEFAULT);
        reminders.setSound(null, null);
        reminders.enableVibration(false);
        manager.createNotificationChannel(reminders);
        PendingIntent open = PendingIntent.getActivity(this, 54, new Intent(this, AgendaSettingsActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 55, new Intent(this, AgendaService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar).setContentTitle("Agenda actif")
                .setContentText("Rappels sur la TV · actualisation chaque minute lorsqu’elle est allumée")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Désactiver", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(FOREGROUND_ID, notification);
    }

    private void showReminders(List<Alert> due) {
        if (ending || !AgendaStore.enabled(this) || !interactive()) return;
        removeOverlay();
        if (windows != null && Settings.canDrawOverlays(this)) {
            try {
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(24), dp(18), dp(24), dp(18));
                GradientDrawable glass = new GradientDrawable();
                glass.setColor(0xEB172126);
                glass.setCornerRadius(dp(18));
                glass.setStroke(dp(1), 0x45FFFFFF);
                card.setBackground(glass);
                for (int i = 0; i < Math.min(3, due.size()); i++) {
                    Alert alert = due.get(i);
                    AgendaEvent event = alert.event;
                    TextView title = new TextView(this);
                    title.setText(cleanTitle(event.title));
                    title.setTextColor(urgent(event) ? 0xFFFF9A78 : Color.WHITE);
                    title.setTextSize(18);
                    title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    title.setMaxLines(2);
                    if (i > 0) title.setPadding(0, dp(14), 0, 0);
                    card.addView(title);
                    TextView time = new TextView(this);
                    time.setText(stageLabel(alert.reminder.stage) + " · " + timeLabel(event));
                    time.setTextColor(0xFFD4E0E3);
                    time.setTextSize(14);
                    card.addView(time);
                }
                if (due.size() > 3) {
                    TextView more = new TextView(this);
                    more.setText("+" + (due.size() - 3) + " autres rappels");
                    more.setTextSize(14);
                    more.setTextColor(0xFFD4E0E3);
                    more.setPadding(0, dp(10), 0, 0);
                    card.addView(more);
                }
                int width = Math.min(dp(420), (int) (getResources().getDisplayMetrics().widthPixels * .6f));
                WindowManager.LayoutParams position = new WindowManager.LayoutParams(width, -2,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                position.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                position.y = dp(36);
                position.setTitle("Rappel de l’agenda");
                windows.addView(card, position);
                overlay = card;
                main.postDelayed(dismissOverlay, 10_000);
                playChime();
                return;
            } catch (RuntimeException ignored) { removeOverlay(); }
        }
        if (fallbackNotification(due)) playChime();
    }

    private boolean fallbackNotification(List<Alert> due) {
        if (!interactive() || (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return false;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(REMINDERS);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        Alert firstAlert = due.get(0);
        AgendaEvent first = firstAlert.event;
        String title = cleanTitle(first.title) + (due.size() > 1 ? " (+" + (due.size() - 1) + ")" : "");
        try {
            manager.notify(REMINDER_ID, new Notification.Builder(this, REMINDERS)
                    .setSmallIcon(android.R.drawable.ic_menu_my_calendar).setContentTitle(title)
                    .setContentText(stageLabel(firstAlert.reminder.stage) + " · " + timeLabel(first))
                    .setOnlyAlertOnce(true).setTimeoutAfter(10_000)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build());
            return true;
        } catch (RuntimeException ignored) { return false; }
    }

    private void playChime() {
        if (chime != null && AgendaStore.sound(this) && interactive()) chime.play();
    }

    private static String stageLabel(String stage) {
        if ("start".equals(stage)) return "Début du rendez-vous";
        if ("middle".equals(stage)) return "Rendez-vous en cours";
        if ("end".equals(stage)) return "Fin du rendez-vous";
        return "Rappel";
    }

    private static boolean urgent(AgendaEvent event) {
        return event.title != null && event.title.toUpperCase(Locale.ROOT).contains("URGENT");
    }
    private static String cleanTitle(String title) {
        if (title == null || title.trim().isEmpty()) return "Événement";
        return title.substring(0, Math.min(title.length(), 240)).replace('\r', ' ').replace('\n', ' ');
    }
    private static String timeLabel(AgendaEvent event) {
        String day = Instant.ofEpochMilli(event.startMillis).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH));
        return day + (event.allDay ? " · toute la journée" : " · " + Instant.ofEpochMilli(event.startMillis)
                .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void removeOverlay() {
        main.removeCallbacks(dismissOverlay);
        if (overlay != null && windows != null) {
            try { windows.removeViewImmediate(overlay); } catch (RuntimeException ignored) { }
        }
        overlay = null;
    }

    @Override public void onDestroy() {
        ending = true;
        clock.shutdownNow();
        network.shutdownNow();
        if (registered) {
            try { unregisterReceiver(screenChanges); } catch (RuntimeException ignored) { }
            registered = false;
        }
        removeOverlay();
        if (chime != null) chime.stop();
        main.removeCallbacksAndMessages(null);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(REMINDER_ID);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
