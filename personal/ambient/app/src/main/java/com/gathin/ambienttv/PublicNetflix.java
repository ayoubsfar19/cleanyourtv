package com.gathin.ambienttv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Public, weekly popularity only; no Netflix account or viewing-history access. */
final class PublicNetflix {
    private static final String OWNER = "com.netflix.ninja";
    private static final String URL = "https://www.netflix.com/tudum/top10/data/all-weeks-global.tsv";
    private static final long CACHE_MS = 6 * 60 * 60 * 1000L;
    private static final String[] CATEGORIES = {
        "Films (English)", "Films (Non-English)", "TV (English)", "TV (Non-English)"
    };
    private static final String[] LABELS = {
        "Films en anglais", "Films internationaux", "Séries en anglais", "Séries internationales"
    };

    private PublicNetflix() {}

    /** Call from the provider's background query, never the UI thread. */
    static synchronized List<RecommendationsData.Row> rows(Context c, String owner) {
        if (!OWNER.equals(owner)) return Collections.emptyList();
        Intent launch = c.getPackageManager().getLeanbackLaunchIntentForPackage(OWNER);
        if (launch == null) launch = c.getPackageManager().getLaunchIntentForPackage(OWNER);
        if (launch == null) return Collections.emptyList();
        SharedPreferences cache = c.getSharedPreferences("netflix_public_top10", Context.MODE_PRIVATE);
        String raw = "";
        try { raw = new JSONObject(cache.getString("public_data", "{}")).optString("tsv", ""); }
        catch (Exception ignored) { /* A damaged cache is disposable. */ }
        long now = System.currentTimeMillis(), attempted = cache.getLong("attempted", 0);
        if (attempted <= 0 || now < attempted || now - attempted >= CACHE_MS) {
            cache.edit().putLong("attempted", now).apply();
            try {
                String fresh = MatchFeed.get(URL, 2_000_000);
                if (!parse(fresh, LocalDate.now()).isEmpty()) {
                    raw = fresh;
                    cache.edit().putString("public_data", new JSONObject().put("tsv", raw).toString()).apply();
                }
            } catch (Exception ignored) { /* Keep only still-current public cached rankings. */ }
        }
        List<RecommendationsData.Row> result = new ArrayList<>();
        String intent = launch.toUri(Intent.URI_INTENT_SCHEME);
        for (Item item : parse(raw, LocalDate.now())) {
            result.add(new RecommendationsData.Row(OWNER, item.title, item.description,
                    intent, "", "trending", 0, 0, false));
        }
        return result;
    }

    static final class Item {
        final String title, description;
        Item(String title, String description) { this.title = title; this.description = description; }
    }

    private static final class Entry {
        LocalDate week;
        int category, rank;
        String title;
    }

    /** Pure parser: retain at most three ranks in each category from the latest usable week. */
    static List<Item> parse(String raw, LocalDate today) {
        List<Item> result = new ArrayList<>();
        if (raw == null || raw.length() > 2_000_000 || today == null) return result;
        List<List<String>> records = records(raw);
        if (records.isEmpty()) return result;
        List<String> header = records.get(0);
        int wi = header.indexOf("week"), ci = header.indexOf("category");
        int ri = header.indexOf("weekly_rank"), ti = header.indexOf("show_title");
        int si = header.indexOf("season_title");
        if (wi < 0 || ci < 0 || ri < 0 || ti < 0) return result;
        int required = Math.max(Math.max(wi, ci), Math.max(ri, ti));
        List<Entry> entries = new ArrayList<>();
        LocalDate latest = null;
        for (int i = 1; i < records.size(); i++) {
            List<String> fields = records.get(i);
            if (fields.size() <= required) continue;
            try {
                Entry e = new Entry();
                e.week = LocalDate.parse(fields.get(wi).trim());
                if (e.week.isAfter(today) || e.week.isBefore(today.minusDays(21))) continue;
                e.category = -1;
                for (int k = 0; k < CATEGORIES.length; k++) {
                    if (CATEGORIES[k].equals(fields.get(ci).trim())) e.category = k;
                }
                e.rank = Integer.parseInt(fields.get(ri).trim());
                e.title = clean(fields.get(ti));
                if (e.category < 0 || e.rank < 1 || e.rank > 10 || e.title.isEmpty()) continue;
                String season = si >= 0 && si < fields.size() ? clean(fields.get(si)) : "";
                if (!season.isEmpty() && !"N/A".equalsIgnoreCase(season)) {
                    String titleLower = e.title.toLowerCase(Locale.ROOT);
                    String seasonLower = season.toLowerCase(Locale.ROOT);
                    if (seasonLower.contains(titleLower)) e.title = season;
                    else if (!titleLower.contains(seasonLower)) e.title += " · " + season;
                }
                if (e.title.length() > 300) e.title = e.title.substring(0, 300);
                entries.add(e);
                if (latest == null || e.week.isAfter(latest)) latest = e.week;
            } catch (RuntimeException ignored) { /* A malformed row must not hide valid rows. */ }
        }
        entries.sort(Comparator.comparingInt((Entry e) -> e.category).thenComparingInt(e -> e.rank));
        int[] counts = new int[4];
        Set<String> seen = new HashSet<>();
        for (Entry e : entries) {
            if (!e.week.equals(latest) || counts[e.category] >= 3
                    || !seen.add(e.category + ":" + e.rank)) continue;
            counts[e.category]++;
            String description = "Semaine du " + e.week.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    + " · Top 10 mondial · À retrouver dans Netflix · " + LABELS[e.category] + " · #" + e.rank;
            result.add(new Item(e.title, description));
        }
        return result;
    }

    private static String clean(String value) { return value.replaceAll("\\s+", " ").trim(); }

    /** TSV uses CSV-style doubled quotes; quoted tabs/newlines remain in their field. */
    private static List<List<String>> records(String raw) {
        if (raw.startsWith("\uFEFF")) raw = raw.substring(1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '"' && (quoted || field.length() == 0)) {
                if (quoted && i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else quoted = !quoted;
            } else if (!quoted && (ch == '\t' || ch == '\r' || ch == '\n')) {
                row.add(field.toString()); field.setLength(0);
                if (ch != '\t') {
                    rows.add(row); row = new ArrayList<>();
                    if (ch == '\r' && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') i++;
                }
            } else field.append(ch);
        }
        if (!quoted && (field.length() > 0 || !row.isEmpty())) {
            row.add(field.toString()); rows.add(row);
        }
        return rows;
    }
}
