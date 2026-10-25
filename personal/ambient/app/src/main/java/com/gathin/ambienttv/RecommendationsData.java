package com.gathin.ambienttv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small, Android-independent validation of rows shared by the TV provider. */
final class RecommendationsData {
    private RecommendationsData() {}

    static boolean validOwner(String owner) {
        return owner != null && !owner.isEmpty() && owner.length() <= 160
                && owner.matches("[A-Za-z0-9_.]+");
    }

    static final class Row {
        final String owner, title, description, intent, poster, kind;
        final long position, duration;
        final boolean live;

        Row(String owner, String title, String description, String intent, String poster,
                String kind, long position, long duration, boolean live) {
            this.owner = owner;
            this.title = title;
            this.description = description;
            this.intent = intent;
            this.poster = poster;
            this.kind = kind;
            this.position = position;
            this.duration = duration;
            this.live = live;
        }
    }

    static Row row(String owner, String requestedOwner, String title, String description,
            String intent, String poster, boolean watchNext, int watchNextType,
            long position, long duration, boolean live) {
        if (!validOwner(owner) || !owner.equals(requestedOwner)) return null;
        if (title == null || title.trim().isEmpty() || !validIntent(intent)) return null;
        long safeDuration = Math.max(0, duration);
        long safePosition = Math.max(0, position);
        if (safeDuration > 0) safePosition = Math.min(safePosition, safeDuration);
        String kind = watchNext && watchNextType == 0 ? "continue"
                : watchNext && watchNextType == 1 ? "next" : "preview";
        return new Row(owner, clipped(title.trim(), 300), clipped(description, 2000), intent,
                poster == null || poster.length() > 16384 ? "" : poster, kind,
                safePosition, safeDuration, live);
    }

    private static boolean validIntent(String intent) {
        if (intent == null || intent.length() > 16384 || intent.trim().isEmpty()
                || !intent.matches("[A-Za-z][A-Za-z0-9+.-]*:.*")) return false;
        for (int i = 0; i < intent.length(); i++) {
            if (Character.isISOControl(intent.charAt(i))) return false;
        }
        return true;
    }

    private static String clipped(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) : value;
    }

    static List<Row> distinct(List<Row> rows, int max) {
        List<Row> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int limit = Math.min(18, Math.max(0, max));
        for (Row row : rows) {
            if (result.size() >= limit) break;
            if (row != null && seen.add(row.owner + "\n" + row.intent)) result.add(row);
        }
        return result;
    }

    static String artworkKey(String owner, String uri) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((owner + "\n" + uri).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
