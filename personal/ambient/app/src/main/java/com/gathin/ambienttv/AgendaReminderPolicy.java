package com.gathin.ambienttv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Pure reminder rules. Persistence and Android presentation belong to AgendaService. */
final class AgendaReminderPolicy {
    static final long GRACE_MILLIS = 60_000L;
    enum Decision { WAIT, SHOW, SKIP }

    static final class Reminder {
        final long when;
        final String stage;

        Reminder(long when, String stage) {
            this.when = when;
            this.stage = stage;
        }
    }

    private AgendaReminderPolicy() { }

    static boolean validDefault(int minutes) {
        return minutes == 0 || minutes == 5 || minutes == 10 || minutes == 15
                || minutes == 30 || minutes == 60;
    }

    static List<Integer> minutes(AgendaEvent event, int fallback) {
        List<Integer> nativeMinutes = event.reminderMinutes;
        if (nativeMinutes == null || nativeMinutes.isEmpty()) return Collections.singletonList(fallback);
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (Integer value : nativeMinutes) {
            if (value != null && value >= 0 && value <= 43_200) result.add(value);
        }
        return result.isEmpty() ? Collections.singletonList(fallback) : new ArrayList<>(result);
    }

    /** A DATE event keeps its original midnight; its reminder uses 09:00 in today's device zone. */
    static long eventTime(AgendaEvent event, ZoneId zone) {
        if (!event.allDay) return event.startMillis;
        return Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalDate()
                .atTime(9, 0).atZone(zone).toInstant().toEpochMilli();
    }

    static long reminderTime(AgendaEvent event, int minutes, ZoneId zone) {
        return eventTime(event, zone) - minutes * 60_000L;
    }

    /** Timed events can repeat through their duration; DATE events keep their calendar alarms. */
    static List<Reminder> reminders(AgendaEvent event, int fallback, ZoneId zone, boolean repeated) {
        List<Reminder> result = new ArrayList<>();
        if (repeated && !event.allDay) {
            result.add(new Reminder(event.startMillis, "start"));
            if (event.endMillis > event.startMillis) {
                // Average without overflowing, including occurrences crossing the epoch.
                long middle = (event.startMillis & event.endMillis)
                        + ((event.startMillis ^ event.endMillis) >> 1);
                if (middle > event.startMillis) result.add(new Reminder(middle, "middle"));
                result.add(new Reminder(event.endMillis, "end"));
            }
        } else {
            for (int minutes : minutes(event, fallback)) {
                result.add(new Reminder(reminderTime(event, minutes, zone), "before"));
            }
            result.sort((a, b) -> Long.compare(a.when, b.when));
        }
        return result;
    }

    static String occurrenceKey(AgendaEvent event) {
        return digest(event.id + "\n" + event.startMillis);
    }

    static String reminderKey(AgendaEvent event, long reminderTime) {
        return digest(event.id + "\n" + event.startMillis + "\n" + reminderTime);
    }

    static String reminderKey(AgendaEvent event, Reminder reminder) {
        if ("before".equals(reminder.stage)) return reminderKey(event, reminder.when);
        return digest(event.id + "\n" + event.startMillis + "\n" + reminder.stage
                + "\n" + reminder.when);
    }

    /** New entries are armed only for future deadlines; screen-off deadlines are consumed silently. */
    static Decision decide(long now, long due, long firstSeen, boolean interactive, boolean handled) {
        if (handled) return Decision.SKIP;
        if (now < due) return Decision.WAIT;
        if (due <= firstSeen || now - due > GRACE_MILLIS || !interactive) return Decision.SKIP;
        return Decision.SHOW;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder result = new StringBuilder(64);
            for (byte b : bytes) result.append(hex[(b & 255) >>> 4]).append(hex[b & 15]);
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
