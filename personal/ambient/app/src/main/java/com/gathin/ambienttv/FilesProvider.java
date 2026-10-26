package com.gathin.ambienttv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.os.Binder;
import android.os.Process;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets sync.sh write the pictures into the app's own files dir, so the app owns them. Only the names
 * {@link Media} allows, and only for callers holding WRITE_SECURE_SETTINGS (the adb shell).
 *
 *   content write  --uri content://com.gathin.ambienttv.files/pictures/sea.jpg < /data/local/tmp/sea.jpg
 *   content write  --uri content://com.gathin.ambienttv.files/seconds.txt < /data/local/tmp/seconds.txt
 *   content delete --uri content://com.gathin.ambienttv.files/pictures/sea.jpg
 *   content delete --uri content://com.gathin.ambienttv.files/pictures      (every picture and clip)
 */
public class FilesProvider extends ContentProvider {

    /** Explicit shell-only playlist management; ContentProvider.call needs its own permission check. */
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        if ("agenda".equals(method)) return agendaCall(arg, extras);
        if (!"night_playlist".equals(method)) return super.call(method, arg, extras);
        if (getContext() == null) throw new IllegalStateException("Stockage indisponible");
        if (Binder.getCallingUid() != Process.myUid()) {
            getContext().enforceCallingPermission("android.permission.WRITE_SECURE_SETTINGS", "Commande ADB requise");
        }
        Bundle reply = new Bundle();
        JSONObject result = new JSONObject();
        try {
            java.util.List<NightLibrary.Entry> entries = new java.util.ArrayList<>(NightLibrary.load(getContext()));
            boolean changed = false;
            if (arg != null && arg.startsWith("add_")) {
                String id = arg.substring(4);
                if (!NightLibrary.validId(id)) throw new IllegalArgumentException("Identifiant YouTube invalide");
                String title = "Vidéo " + id;
                if (extras != null && extras.containsKey("title64")) {
                    String encoded = extras.getString("title64", "");
                    if (encoded.length() > 2048) throw new IllegalArgumentException("Titre trop long");
                    title = new String(Base64.decode(encoded, Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8).trim();
                }
                if (!NightLibrary.validTitle(title)) throw new IllegalArgumentException("Titre invalide (1 à 100 caractères)");
                int existing = -1;
                for (int i = 0; i < entries.size(); i++) if (id.equals(entries.get(i).id)) existing = i;
                if (existing < 0 && entries.size() >= NightLibrary.MAX_VIDEOS) throw new IllegalArgumentException("Limite de 50 vidéos atteinte");
                NightLibrary.Entry entry = new NightLibrary.Entry(id, title, existing < 0 ? null : entries.get(existing).file, "");
                if (existing < 0) entries.add(entry); else entries.set(existing, entry);
                changed = true;
            } else if (arg != null && arg.startsWith("remove_")) {
                String id = arg.substring(7);
                if (!NightLibrary.validId(id)) throw new IllegalArgumentException("Identifiant YouTube invalide");
                if (entries.size() == 1 && entries.get(0).id.equals(id)) throw new IllegalArgumentException("Ajoutez une vidéo avant de retirer la dernière");
                changed = entries.removeIf(entry -> entry.id.equals(id));
            } else if (!"list".equals(arg)) throw new IllegalArgumentException("Commande inconnue");
            if (changed) {
                File directory = dir();
                if (directory == null) throw new IllegalStateException("Stockage indisponible");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Stockage indisponible");
                java.nio.file.Path target = new File(directory, NightLibrary.CONFIG).toPath();
                java.nio.file.Path temporary = java.nio.file.Files.createTempFile(directory.toPath(), ".playlist-", ".json");
                try {
                    java.nio.file.Files.write(temporary, NightLibrary.playlistJson(entries).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try { java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
                    catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                        java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally { java.nio.file.Files.deleteIfExists(temporary); }
            }
            JSONArray videos = new JSONArray();
            for (NightLibrary.Entry entry : entries) videos.put(new JSONObject().put("id", entry.id).put("title", entry.title));
            result.put("success", true).put("message", changed ? "Liste mise à jour. Rouvrez le mode nuit." : "Liste actuelle").put("videos", videos);
        } catch (Exception failure) {
            try { result.put("success", false).put("message", failure.getMessage() == null ? "Commande impossible" : failure.getMessage()); }
            catch (org.json.JSONException ignored) { }
        }
        reply.putString("result", result.toString());
        return reply;
    }

    /** The private feed is passed locally by the owner's ADB tool, never echoed in a result. */
    private Bundle agendaCall(String action, Bundle extras) {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("Stockage indisponible");
        if (Binder.getCallingUid() != Process.myUid()) {
            context.enforceCallingPermission("android.permission.WRITE_SECURE_SETTINGS", "Commande ADB requise");
        }
        JSONObject result = new JSONObject();
        try {
            String message;
            if ("configure".equals(action)) {
                String encoded = extras == null ? "" : extras.getString("url64", "");
                if (encoded.isEmpty() || encoded.length() > 8192) throw new IllegalArgumentException();
                byte[] raw = Base64.decode(encoded, Base64.DEFAULT);
                String url = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(raw)).toString();
                int minutes = extras == null ? 10 : extras.getInt("minutes", 10);
                AgendaStore.configure(context, url, minutes);
                if (AgendaStore.enabled(context)) AgendaService.syncNow(context);
                message = "Agenda enregistré. Activez les rappels dans les réglages du téléviseur.";
            } else if ("enable".equals(action)) {
                if (!AgendaStore.configured(context)) throw new IllegalStateException();
                message = AgendaService.start(context) ? "Agenda activé."
                        : "Activez l’agenda depuis les réglages du téléviseur.";
            } else if ("disable".equals(action)) {
                AgendaService.stop(context);
                message = "Agenda désactivé.";
            } else if ("disconnect".equals(action)) {
                AgendaService.stop(context);
                AgendaStore.clear(context);
                message = "Agenda déconnecté et données locales effacées.";
            } else if ("sync".equals(action)) {
                if (!AgendaStore.enabled(context)) throw new IllegalStateException();
                AgendaService.syncNow(context);
                message = "Synchronisation demandée.";
            } else if ("status".equals(action)) {
                message = AgendaStore.status(context);
            } else throw new IllegalArgumentException();
            result.put("success", true).put("message", message)
                    .put("configured", AgendaStore.configured(context))
                    .put("enabled", AgendaStore.enabled(context))
                    .put("default_minutes", AgendaStore.defaultMinutes(context))
                    .put("last_success", AgendaStore.lastSuccess(context));
        } catch (Exception failure) {
            try { result.put("success", false).put("message", "Commande impossible. Vérifiez la configuration de l’agenda dans les réglages du téléviseur."); }
            catch (org.json.JSONException ignored) { }
        }
        Bundle reply = new Bundle();
        reply.putString("result", result.toString());
        return reply;
    }

    private File dir() {
        return getContext() == null ? null : getContext().getExternalFilesDir(null);
    }

    private File file(Uri uri) throws FileNotFoundException {
        String path = Media.path(uri.getPathSegments());
        File dir = dir();
        if (dir == null || path == null) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(dir, path);
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new FileNotFoundException(String.valueOf(uri));
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(uri);
        if (mode.contains("w")) {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        File dir = dir();
        if (dir != null && Media.isFolder(uri.getPathSegments())) {
            File[] files = new File(dir, Media.PICTURES).listFiles((d, name) -> Media.file(name));
            int n = 0;
            if (files != null) for (File f : files) if (f.delete()) n++;
            return n;
        }
        try {
            return file(uri).delete() ? 1 : 0;
        } catch (FileNotFoundException e) {
            return 0;
        }
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
