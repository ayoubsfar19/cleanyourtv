package com.gathin.ambienttv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Four owner-selected native video sources, kept separate from the photo collection. */
final class NightLibrary {
    static final String FOLDER = "night-videos", CONFIG = "night-videos.json";
    private static final String[] IDS = {"7_Wbmy79wxQ", "BVdHrTfO2PE", "yTRHheKoVhY", "BUeCNBg_qYk"};
    private static final String[] TITLES = {"The Art Corner · 1", "The Art Corner · 2",
            "Bedroom R&B · 1", "Bedroom R&B · 2"};

    static final class Entry {
        final String id, title, url;
        final File file;

        Entry(String id, String title, File file, String url) {
            this.id = id;
            this.title = title;
            this.file = file;
            this.url = url;
        }
    }

    private NightLibrary() { }

    static List<Entry> load(Context context) {
        File base = context.getExternalFilesDir(null);
        String json = "";
        File config = base == null ? null : new File(base, CONFIG);
        if (config != null && config.isFile() && config.length() <= 65_536) {
            try { json = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8); }
            catch (Exception ignored) { /* The default slots remain available for local MP4s. */ }
        }
        return parse(json, base);
    }

    static List<Entry> parse(String raw, File base) {
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < IDS.length; i++) {
            File file = base == null ? null : new File(new File(base, FOLDER), (i + 1) + ".mp4");
            result.add(new Entry(IDS[i], TITLES[i], file, ""));
        }
        if (raw == null || raw.length() > 65_536) return result;
        try {
            JSONObject config = new JSONObject(raw.startsWith("\uFEFF") ? raw.substring(1) : raw);
            if (config.optInt("version", 0) != 1) return result;
            JSONArray videos = config.optJSONArray("videos");
            if (videos == null || videos.length() > 4) return result;
            boolean[] seen = new boolean[4];
            for (int row = 0; row < videos.length(); row++) {
                JSONObject video = videos.optJSONObject(row);
                if (video == null) continue;
                for (int i = 0; i < IDS.length; i++) {
                    if (!IDS[i].equals(video.optString("id", "")) || seen[i]) continue;
                    seen[i] = true;
                    Entry old = result.get(i);
                    String title = video.optString("title", old.title).trim();
                    if (title.isEmpty() || title.length() > 100 || title.matches("(?s).*[\\p{Cntrl}].*")) title = old.title;
                    // Local names are derived from the fixed slot, never from supplied paths.
                    String url = directUrl(video.optString("url", ""));
                    result.set(i, new Entry(old.id, title, old.file, url));
                }
            }
        } catch (Exception ignored) { /* Invalid configuration cannot break the photo screensaver. */ }
        return result;
    }

    static String directUrl(String value) {
        if (value == null || value.length() > 4096) return "";
        try {
            URI uri = new URI(value.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null) return "";
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (host.equals("youtu.be") || host.endsWith(".youtu.be") || host.equals("youtube.com")
                    || host.endsWith(".youtube.com") || host.equals("youtube-nocookie.com")
                    || host.endsWith(".youtube-nocookie.com") || host.equals("googlevideo.com")
                    || host.endsWith(".googlevideo.com")) return "";
            return uri.toASCIIString();
        } catch (Exception ignored) { return ""; }
    }
}
