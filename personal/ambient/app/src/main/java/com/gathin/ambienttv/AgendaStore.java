package com.gathin.ambienttv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Private read-only calendar state; neither this store nor its status exposes the secret URL. */
final class AgendaStore {
    private static final String PREFS = "agenda_private";
    private static final String URL = "private_url", ENABLED = "enabled", DEFAULT = "default_minutes";
    private static final String SUCCESS = "last_success", ERROR = "sync_error";
    private static final String ETAG = "etag", MODIFIED = "last_modified";
    private static final int MAX_RAW_BYTES = 4 * 1024 * 1024, MAX_EVENTS = 1000;
    private static final long DAY = 86_400_000L;
    private static List<AgendaEvent> memory;
    private static String memoryPath;
    private static long memoryModified = -1, memoryLength = -1;

    private AgendaStore() { }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean configured(Context context) { return !url(context).isEmpty(); }
    static String sourceRevision(Context context) {
        return preferences(context).getString("source_revision", "");
    }
    static boolean enabled(Context context) { return preferences(context).getBoolean(ENABLED, false); }
    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(ENABLED, enabled && configured(context)).commit();
    }
    static int defaultMinutes(Context context) {
        int minutes = preferences(context).getInt(DEFAULT, 10);
        return AgendaReminderPolicy.validDefault(minutes) ? minutes : 10;
    }
    static void setDefaultMinutes(Context context, int minutes) {
        if (!AgendaReminderPolicy.validDefault(minutes)) throw new IllegalArgumentException("Délai invalide");
        preferences(context).edit().putInt(DEFAULT, minutes).commit();
    }
    static long lastSuccess(Context context) { return preferences(context).getLong(SUCCESS, 0); }
    static String status(Context context) {
        if (!configured(context)) return "Non configuré";
        if (preferences(context).getBoolean(ERROR, false)) return lastSuccess(context) > 0
                ? "Synchronisation indisponible · cache conservé" : "Synchronisation indisponible";
        return lastSuccess(context) == 0 ? "En attente de synchronisation" : "Synchronisé";
    }

    static synchronized void configure(Context context, String secretUrl, int minutes) {
        String validated = AgendaFeed.validate(secretUrl);
        if (!AgendaReminderPolicy.validDefault(minutes)) throw new IllegalArgumentException("Délai invalide");
        SharedPreferences p = preferences(context);
        boolean changed = !validated.equals(url(context));
        if (changed) {
            boolean wasEnabled = enabled(context);
            deleteFiles(context);
            p.edit().clear().putString(URL, validated).putInt(DEFAULT, minutes)
                    .putString("source_revision", java.util.UUID.randomUUID().toString())
                    .putBoolean(ENABLED, wasEnabled).commit();
        } else p.edit().putInt(DEFAULT, minutes).commit();
    }

    static synchronized void clear(Context context) {
        preferences(context).edit().clear().commit();
        deleteFiles(context);
        context.stopService(new android.content.Intent(context, AgendaService.class));
    }

    /** Reads only a bounded, parsed view. Raw iCalendar parsing always runs on the service worker. */
    static synchronized List<AgendaEvent> load(Context context) {
        File file = eventsFile(context);
        String path = file.getAbsolutePath();
        if (memory != null && path.equals(memoryPath) && memoryModified == file.lastModified()
                && memoryLength == file.length()) return memory;
        List<AgendaEvent> events = new ArrayList<>();
        try {
            String body = read(new AtomicFile(file), MAX_RAW_BYTES);
            if (body != null) {
                JSONArray rows = new JSONObject(body).getJSONArray("events");
                int count = Math.min(rows.length(), MAX_EVENTS);
                for (int i = 0; i < count; i++) {
                    JSONObject row = rows.getJSONObject(i);
                    JSONArray alarms = row.optJSONArray("reminders");
                    List<Integer> reminders = new ArrayList<>();
                    if (alarms != null) for (int n = 0; n < Math.min(32, alarms.length()); n++)
                        reminders.add(alarms.getInt(n));
                    events.add(new AgendaEvent(row.getString("id"), row.getString("title"),
                            row.optString("description"), row.getLong("start"), row.getLong("end"),
                            row.optBoolean("all_day"), reminders));
                }
            }
        } catch (IOException | JSONException | RuntimeException ignored) { events.clear(); }
        memory = Collections.unmodifiableList(events);
        memoryPath = path;
        memoryModified = file.lastModified();
        memoryLength = file.length();
        return memory;
    }

    /** Network and parsing work. A failed response leaves every last-good cache file intact. */
    static void synchronize(Context context) {
        String secret = url(context);
        if (secret.isEmpty() || !enabled(context)) return;
        SharedPreferences p = preferences(context);
        try {
            AgendaFeed.Result result = AgendaFeed.fetch(secret, p.getString(ETAG, ""),
                    p.getString(MODIFIED, ""));
            String raw = result.notModified ? read(new AtomicFile(rawFile(context)), MAX_RAW_BYTES) : result.body;
            if (raw == null) throw new IOException("Cache indisponible");
            long now = System.currentTimeMillis();
            List<AgendaEvent> events = parse(raw, now);
            String view = serialize(events);
            synchronized (AgendaStore.class) {
                if (!secret.equals(url(context)) || !enabled(context)) return;
                if (!result.notModified) write(new AtomicFile(rawFile(context)), raw);
                write(new AtomicFile(eventsFile(context)), view);
                invalidateMemory();
                SharedPreferences.Editor edit = p.edit().putLong(SUCCESS, now).putBoolean(ERROR, false);
                if (result.etag != null) edit.putString(ETAG, result.etag);
                else if (!result.notModified) edit.remove(ETAG);
                if (result.lastModified != null) edit.putString(MODIFIED, result.lastModified);
                else if (!result.notModified) edit.remove(MODIFIED);
                edit.commit();
            }
        } catch (Exception unavailable) {
            synchronized (AgendaStore.class) {
                if (secret.equals(url(context)) && enabled(context)) p.edit().putBoolean(ERROR, true).commit();
            }
        }
    }

    /** Re-expands the private offline source after a date, wall-clock or timezone change. */
    static void rebuildCache(Context context) {
        String secret = url(context);
        if (secret.isEmpty() || !enabled(context)) return;
        try {
            String raw = read(new AtomicFile(rawFile(context)), MAX_RAW_BYTES);
            if (raw == null) return;
            String view = serialize(parse(raw, System.currentTimeMillis()));
            synchronized (AgendaStore.class) {
                if (!secret.equals(url(context)) || !enabled(context)) return;
                write(new AtomicFile(eventsFile(context)), view);
                invalidateMemory();
            }
        } catch (Exception ignored) { /* The previously parsed view is still usable offline. */ }
    }

    private static List<AgendaEvent> parse(String raw, long now) {
        List<AgendaEvent> events = AgendaIcal.parse(raw, now - DAY, now + 30 * DAY, ZoneId.systemDefault());
        return events.size() > MAX_EVENTS ? events.subList(0, MAX_EVENTS) : events;
    }

    private static String serialize(List<AgendaEvent> events) throws JSONException {
        JSONArray rows = new JSONArray();
        int bytes = 16;
        for (AgendaEvent event : events) {
            JSONArray alarms = new JSONArray();
            if (event.reminderMinutes != null) {
                for (int i = 0; i < Math.min(32, event.reminderMinutes.size()); i++)
                    alarms.put(event.reminderMinutes.get(i));
            }
            JSONObject row = new JSONObject().put("id", event.id)
                    .put("title", bounded(event.title, 1000)).put("description", bounded(event.description, 1000))
                    .put("start", event.startMillis).put("end", event.endMillis)
                    .put("all_day", event.allDay).put("reminders", alarms);
            int rowBytes = row.toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes + rowBytes > MAX_RAW_BYTES) break;
            rows.put(row);
            bytes += rowBytes;
        }
        return new JSONObject().put("events", rows).toString();
    }

    private static String bounded(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), max));
    }
    private static String url(Context context) { return preferences(context).getString(URL, ""); }
    private static File rawFile(Context context) { return new File(context.getNoBackupFilesDir(), "agenda-source.ics"); }
    private static File eventsFile(Context context) { return new File(context.getNoBackupFilesDir(), "agenda-events.json"); }
    private static void deleteFiles(Context context) {
        new AtomicFile(rawFile(context)).delete();
        new AtomicFile(eventsFile(context)).delete();
        invalidateMemory();
    }
    private static void invalidateMemory() { memory = null; memoryModified = -1; memoryLength = -1; }

    private static String read(AtomicFile file, int maxBytes) throws IOException {
        try (FileInputStream in = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = in.read(bytes)) != -1) {
                if (out.size() + count > maxBytes) throw new IOException("Cache trop volumineux");
                out.write(bytes, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (FileNotFoundException absent) { return null; }
    }
    private static void write(AtomicFile file, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RAW_BYTES) throw new IOException("Cache trop volumineux");
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(bytes);
            file.finishWrite(out);
        } catch (IOException failure) {
            if (out != null) file.failWrite(out);
            throw new IOException("Écriture du cache indisponible");
        }
    }
}
