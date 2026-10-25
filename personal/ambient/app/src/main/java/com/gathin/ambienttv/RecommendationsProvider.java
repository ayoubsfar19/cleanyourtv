package com.gathin.ambienttv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only, signature protected bridge to the TV rows apps actually share with Android. */
public final class RecommendationsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.gathin.ambienttv.recommendations";
    public static final String PERMISSION = "com.gathin.ambienttv.permission.RECOMMENDATIONS";
    private static final String TV_PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final String[] COLUMNS = {"_id", "title", "description", "kind", "poster_uri",
            "intent_uri", "owner_package", "position", "duration", "live"};
    private static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_CACHE_FILES = 64;
    private static final int MAX_MAPPINGS = 512;
    private static final int MAX_SOURCE_ROWS = 10000;
    private final Object artworkLock = new Object();

    @Override public boolean onCreate() { return true; }

    private void enforceReader() {
        if (getContext() == null) throw new SecurityException("Provider unavailable");
        getContext().enforceCallingOrSelfPermission(PERMISSION, "Signature permission required");
    }

    private boolean canReadListings() {
        return getContext() != null && getContext().checkSelfPermission(TV_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        enforceReader();
        List<String> path = uri.getPathSegments();
        if (!AUTHORITY.equals(uri.getAuthority()) || uri.getQuery() != null)
            throw new IllegalArgumentException("Unknown recommendations URI");
        // Nested provider calls must run with this app's identity and TV permission, not the launcher UID.
        long token = Binder.clearCallingIdentity();
        try {
            if (path.size() == 1 && "status".equals(path.get(0))) return status();
            if (path.size() != 2 || !"programs".equals(path.get(0))
                    || !RecommendationsData.validOwner(path.get(1)))
                throw new IllegalArgumentException("Unknown recommendations URI");
            String owner = path.get(1);
            List<RecommendationsData.Row> rows = new ArrayList<>();
            boolean permission = canReadListings();
            boolean watchAvailable = false, previewAvailable = false;
            if (permission) {
                watchAvailable = read(WATCH_NEXT, owner, true, rows, null);
                previewAvailable = read(PREVIEW, owner, false, rows, null);
            }
            MatrixCursor result = new MatrixCursor(COLUMNS);
            for (RecommendationsData.Row row : RecommendationsData.distinct(rows, 18)) {
                result.addRow(new Object[]{result.getCount() + 1L, row.title, row.description, row.kind,
                        poster(row.owner, row.poster), row.intent, row.owner,
                        row.position, row.duration, row.live ? 1 : 0});
            }
            // General, dated public popularity data is used only when the app shares no TV rows.
            if (result.getCount() == 0 && "com.netflix.ninja".equals(owner)) {
                for (RecommendationsData.Row row : PublicNetflix.rows(getContext(), owner)) {
                    result.addRow(new Object[]{result.getCount()+1L,row.title,row.description,row.kind,"",row.intent,row.owner,0L,0L,0});
                }
            }
            if (result.getCount() == 0) addShortcut(result, owner);
            Bundle extras = new Bundle();
            extras.putBoolean("read_tv_listings", permission);
            extras.putBoolean("watch_next_available", watchAvailable);
            extras.putBoolean("preview_available", previewAvailable);
            result.setExtras(extras);
            return result;
        } finally { Binder.restoreCallingIdentity(token); }
    }

    /** No SQL selection: the normal TV permission permits searchable rows only. */
    private boolean read(Uri source, String owner, boolean watchNext,
            List<RecommendationsData.Row> rows, Map<String, int[]> counts) {
        try (Cursor cursor = getContext().getContentResolver().query(source, null, null, null, null)) {
            if (cursor == null) return false;
            Set<String> seen = new HashSet<>();
            if (rows != null) for (RecommendationsData.Row row : rows) seen.add(row.owner + "\n" + row.intent);
            int visited = 0;
            while (visited++ < MAX_SOURCE_ROWS && cursor.moveToNext()) {
                String packageName = string(cursor, "package_name");
                if (!RecommendationsData.validOwner(packageName)) continue;
                if (counts != null) {
                    int[] value = counts.get(packageName);
                    if (value == null) { value = new int[2]; counts.put(packageName, value); }
                    value[watchNext ? 0 : 1]++;
                    continue;
                }
                if (!packageName.equals(owner)) continue;
                if (rows.size() >= 18) break;
                String description = string(cursor, "short_description");
                if (description.isEmpty()) description = string(cursor, "long_description");
                String artwork = string(cursor, "poster_art_uri");
                if (artwork.isEmpty()) artwork = string(cursor, "thumbnail_uri");
                RecommendationsData.Row row = RecommendationsData.row(packageName, owner,
                        string(cursor, "title"), description, string(cursor, "intent_uri"), artwork,
                        watchNext, (int) number(cursor, "watch_next_type", -1),
                        number(cursor, "last_playback_position_millis"), number(cursor, "duration_millis"),
                        number(cursor, "live") == 1);
                if (row != null && validIntent(row.intent) && seen.add(row.owner + "\n" + row.intent)) rows.add(row);
            }
            return true;
        } catch (RuntimeException ignored) {
            // Missing permissions, absent providers and malformed third-party rows are ordinary empty states.
            return false;
        }
    }

    private static String string(Cursor cursor, String name) {
        try {
            int column = cursor.getColumnIndex(name);
            return column < 0 || cursor.isNull(column) ? "" : cursor.getString(column);
        } catch (RuntimeException ignored) { return ""; }
    }

    private static boolean validIntent(String value) {
        try {
            Intent.parseUri(value, Intent.URI_INTENT_SCHEME);
            return true;
        } catch (URISyntaxException | RuntimeException ignored) { return false; }
    }

    private static long number(Cursor cursor, String name) {
        return number(cursor, name, 0);
    }

    private static long number(Cursor cursor, String name, long absent) {
        try {
            int column = cursor.getColumnIndex(name);
            return column < 0 || cursor.isNull(column) ? absent : cursor.getLong(column);
        } catch (RuntimeException ignored) { return absent; }
    }

    private void addShortcut(MatrixCursor result, String owner) {
        PackageManager packages = getContext().getPackageManager();
        Intent launch = packages.getLeanbackLaunchIntentForPackage(owner);
        if (launch == null) launch = packages.getLaunchIntentForPackage(owner);
        if (launch == null) return;
        String label = owner;
        try {
            ApplicationInfo info = packages.getApplicationInfo(owner, 0);
            label = packages.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException ignored) { }
        result.addRow(new Object[]{1L, "Ouvrir " + label, "Aucune suggestion partagée", "shortcut",
                "", launch.toUri(Intent.URI_INTENT_SCHEME), owner, 0L, 0L, 0});
    }

    private Cursor status() {
        boolean permission = canReadListings();
        Map<String, int[]> counts = new LinkedHashMap<>();
        boolean watchAvailable = permission && read(WATCH_NEXT, null, true, null, counts);
        boolean previewAvailable = permission && read(PREVIEW, null, false, null, counts);
        MatrixCursor cursor = new MatrixCursor(new String[]{"owner_package", "watch_next_count",
                "preview_count", "read_tv_listings", "watch_next_available", "preview_available"});
        if (counts.isEmpty()) counts.put("", new int[2]);
        for (Map.Entry<String, int[]> item : counts.entrySet())
            cursor.addRow(new Object[]{item.getKey(), item.getValue()[0], item.getValue()[1],
                    permission ? 1 : 0, watchAvailable ? 1 : 0, previewAvailable ? 1 : 0});
        return cursor;
    }

    private SharedPreferences metadata() {
        return getContext().getSharedPreferences("recommendation_artwork", 0);
    }

    /** Only HTTPS addresses obtained from a visible source row become proxy identifiers. */
    private String poster(String owner, String source) {
        if (source == null || source.length() > 8192) return "";
        Uri uri = Uri.parse(source);
        String scheme = uri.getScheme();
        if ("android.resource".equals(scheme) || "content".equals(scheme)) return source;
        if (!"https".equals(scheme) || uri.getHost() == null || uri.getUserInfo() != null) return "";
        String key = RecommendationsData.artworkKey(owner, source);
        synchronized (artworkLock) {
            SharedPreferences prefs = metadata();
            Map<String, ?> known = prefs.getAll();
            SharedPreferences.Editor editor = prefs.edit();
            if (known.size() >= MAX_MAPPINGS && !known.containsKey(key)) {
                int remove = known.size() - MAX_MAPPINGS + 1;
                for (String old : known.keySet()) {
                    if (remove-- <= 0) break;
                    editor.remove(old);
                }
            }
            editor.putString(key, source).apply();
        }
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("artwork").appendPath(key).build().toString();
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceReader();
        List<String> path = uri.getPathSegments();
        if (!"r".equals(mode) || !AUTHORITY.equals(uri.getAuthority()) || uri.getQuery() != null
                || path.size() != 2 || !"artwork".equals(path.get(0))
                || !path.get(1).matches("[a-f0-9]{64}")) throw new FileNotFoundException("Unknown artwork");
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (artworkLock) {
                String key = path.get(1);
                String source = metadata().getString(key, null);
                if (source == null) throw new FileNotFoundException("Artwork unavailable");
                File dir = new File(getContext().getCacheDir(), "recommendation_artwork");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cache unavailable");
                File file = new File(dir, key);
                if (!file.isFile()) download(source, file);
                file.setLastModified(System.currentTimeMillis());
                trimCache(dir, file);
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
        } catch (IOException | RuntimeException ignored) {
            throw new FileNotFoundException("Artwork unavailable");
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private static void download(String source, File destination) throws IOException {
        HttpURLConnection connection = null;
        File pending = new File(destination.getParentFile(), destination.getName() + ".part");
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        try {
            URL address = new URL(source);
            for (int redirects = 0; ; redirects++) {
                if (!"https".equals(address.getProtocol()) || address.getUserInfo() != null)
                    throw new IOException("Unsupported artwork");
                connection = (HttpURLConnection) address.openConnection();
                int remaining = (int) (deadline - android.os.SystemClock.elapsedRealtime());
                if (remaining <= 0) throw new IOException("Artwork timeout");
                connection.setConnectTimeout(remaining);
                connection.setReadTimeout(remaining);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "image/*");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400 && redirects < 2) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing artwork redirect");
                    address = new URL(address, location);
                    connection.disconnect();
                    if (android.os.SystemClock.elapsedRealtime() > deadline)
                        throw new IOException("Artwork timeout");
                    continue;
                }
                if (code != 200 || connection.getContentLengthLong() > MAX_IMAGE_BYTES)
                    throw new IOException("Artwork response rejected");
                break;
            }
            int remaining = (int) (deadline - android.os.SystemClock.elapsedRealtime());
            if (remaining <= 0) throw new IOException("Artwork timeout");
            connection.setReadTimeout(remaining);
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(pending)) {
                byte[] buffer = new byte[8192];
                long bytes = 0;
                int size;
                while (true) {
                    remaining = (int) (deadline - android.os.SystemClock.elapsedRealtime());
                    if (remaining <= 0) throw new IOException("Artwork timeout");
                    connection.setReadTimeout(remaining);
                    size = input.read(buffer);
                    if (size == -1) break;
                    bytes += size;
                    if (bytes > MAX_IMAGE_BYTES || android.os.SystemClock.elapsedRealtime() > deadline)
                        throw new IOException("Artwork limit exceeded");
                    output.write(buffer, 0, size);
                }
            }
            if (android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("Artwork timeout");
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(pending.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8192
                    || bounds.outHeight > 8192 || (long) bounds.outWidth * bounds.outHeight > 16000000)
                throw new IOException("Artwork dimensions rejected");
            if (!pending.renameTo(destination)) throw new IOException("Cache unavailable");
        } finally {
            if (connection != null) connection.disconnect();
            if (pending.exists()) pending.delete();
        }
    }

    private static void trimCache(File directory, File keep) {
        File[] files = directory.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long bytes = 0;
        int count = 0;
        for (File file : files) { bytes += file.length(); count++; }
        for (File file : files) {
            if (count <= MAX_CACHE_FILES && bytes <= MAX_CACHE_BYTES) break;
            if (!file.equals(keep)) {
                long size = file.length();
                if (file.delete()) { bytes -= size; count--; }
            }
        }
    }

    @Override public String getType(Uri uri) {
        enforceReader();
        List<String> path = uri.getPathSegments();
        if (path.size() == 2 && "artwork".equals(path.get(0))) return "image/*";
        return "vnd.android.cursor.dir/vnd.com.gathin.ambienttv.recommendation";
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new SecurityException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new SecurityException("Read only"); }
}
