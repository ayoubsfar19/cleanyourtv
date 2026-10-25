package com.gathin.ambienttv;

import android.content.Context;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Native, looping night video. The host owns the video layer and all controls above it. */
final class NativeNightPlayback implements NightMode.Playback {
    private static final float VOLUME = 0.12f;
    private static final long PREPARE_TIMEOUT_MS = 20_000L;

    private final Context context;
    private final FrameLayout videoLayer;
    private final SurfaceView video;
    private final View shutter;
    private final SurfaceHolder holder;
    private final List<NightLibrary.Entry> entries;
    private final Consumer<String> status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audioManager;
    private final AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build();
    private MediaPlayer player;
    private AudioFocusRequest focusRequest;
    private Runnable prepareTimeout;
    private String source;
    private boolean sourceIsFile;
    private boolean active, prepared, hostPaused, focusGranted, focusPaused, ducked, surfaceReady;
    private long selectionGeneration, playerGeneration;
    private int videoWidth, videoHeight;
    private int bufferWidth, bufferHeight;

    NativeNightPlayback(Context context, FrameLayout videoLayer, List<NightLibrary.Entry> entries,
                        Consumer<String> status) {
        this.context = context.getApplicationContext();
        this.videoLayer = videoLayer;
        this.entries = new ArrayList<>(entries);
        this.status = status;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        // A separate compositor layer avoids first sampling a 4K source into a 1080p UI buffer.
        // Actual output resolution still depends on the TV's decoder, display and compositor.
        video = new SurfaceView(context);
        video.setZOrderOnTop(false);
        video.setZOrderMediaOverlay(false);
        video.setFocusable(false);
        video.setClickable(false);
        video.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        holder = video.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder surfaceHolder) {
                surfaceReady = true;
                if (active) preparePlayer();
            }

            @Override public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int w, int h) {
                fitVideo();
            }

            @Override public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
                surfaceReady = false;
                releasePlayer();
            }
        });
        // The dimmer is also a child of this layer and must stay above the moving picture.
        videoLayer.addView(video, 0, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        // SurfaceView alpha is not reliable on older TVs. Cover stale/loading frames in the UI.
        shutter = new View(context);
        shutter.setBackgroundColor(Color.BLACK);
        shutter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        videoLayer.addView(shutter, 1, new FrameLayout.LayoutParams(-1, -1));
        videoLayer.addOnLayoutChangeListener((view, left, top, right, bottom,
                                             oldLeft, oldTop, oldRight, oldBottom) -> fitVideo());
    }

    /** Called only for an explicit entry or playlist selection, never from a lifecycle callback. */
    @Override public void play(int index) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> play(index));
            return;
        }
        stopInternal();
        hostPaused = false;
        if (index < 0 || index >= entries.size()) {
            report("Ambiance indisponible. Choisissez une autre vidéo. Retour : photos.");
            return;
        }
        NightLibrary.Entry entry = entries.get(index);
        File file = entry.file;
        if (file != null && file.isFile() && file.canRead()) {
            source = file.getAbsolutePath();
            sourceIsFile = true;
        } else if (isDirectHttps(entry.url)) {
            source = entry.url.trim();
            sourceIsFile = false;
        } else {
            String name = file == null ? "un fichier MP4" : "le MP4 « " + file.getName() + " »";
            report("Ajoutez " + name + " au dossier « " + NightLibrary.FOLDER
                    + " », puis réessayez. Retour : photos.");
            return;
        }
        active = true;
        long selection = selectionGeneration;
        if (!requestFocus(selection)) {
            fail("Le son est utilisé par une autre application. Réessayez depuis la playlist. Retour : photos.");
            return;
        }
        report("Chargement de la vidéo…");
        if (surfaceReady) preparePlayer();
        else watchPreparation(selection, playerGeneration);
    }

    @Override public void stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::stop);
            return;
        }
        stopInternal();
        report("");
    }

    @Override public void pause() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::pause);
            return;
        }
        hostPaused = true;
        pausePlayer();
    }

    @Override public void resume() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::resume);
            return;
        }
        hostPaused = false;
        startIfAllowed();
    }

    private void preparePlayer() {
        if (!active || !surfaceReady || !holder.getSurface().isValid() || source == null) return;
        releasePlayer();
        long selection = selectionGeneration;
        long generation = playerGeneration;
        MediaPlayer candidate = new MediaPlayer();
        player = candidate;
        try {
            candidate.setAudioAttributes(audioAttributes);
            candidate.setDisplay(holder);
            candidate.setVolume(ducked ? VOLUME * 0.2f : VOLUME, ducked ? VOLUME * 0.2f : VOLUME);
            candidate.setLooping(true);
            candidate.setOnVideoSizeChangedListener((media, width, height) -> {
                if (!isCurrent(media, selection, generation)) return;
                videoWidth = width;
                videoHeight = height;
                fitVideo();
            });
            candidate.setOnPreparedListener(media -> {
                if (!isCurrent(media, selection, generation)) return;
                clearPrepareTimeout();
                prepared = true;
                videoWidth = media.getVideoWidth();
                videoHeight = media.getVideoHeight();
                fitVideo();
                report(focusPaused ? "Lecture en pause pendant une interruption audio." : "");
                startIfAllowed();
            });
            candidate.setOnInfoListener((media, what, extra) -> {
                if (isCurrent(media, selection, generation)
                        && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) shutter.setVisibility(View.GONE);
                return false;
            });
            candidate.setOnErrorListener((media, what, extra) -> {
                if (isCurrent(media, selection, generation)) {
                    fail(sourceIsFile
                            ? "Impossible de lire ce MP4. Essayez un fichier H.264/AAC compatible avec la TV. Retour : photos."
                            : "Flux indisponible. Vérifiez la connexion et le lien HTTPS direct, ou ajoutez un MP4 local. Retour : photos.");
                }
                return true;
            });
            if (sourceIsFile) candidate.setDataSource(source);
            else candidate.setDataSource(context, Uri.parse(source));
            candidate.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            candidate.prepareAsync();
            watchPreparation(selection, generation);
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException failure) {
            if (isCurrent(candidate, selection, generation)) {
                fail(sourceIsFile
                        ? "MP4 inaccessible. Vérifiez le fichier et son format, puis réessayez. Retour : photos."
                        : "Impossible d’ouvrir ce flux. Utilisez un lien HTTPS direct ou un MP4 local. Retour : photos.");
            }
        }
    }

    private boolean requestFocus(long selection) {
        if (audioManager == null) return false;
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(change -> onFocusChanged(selection, change), main)
                .build();
        focusGranted = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focusGranted;
    }

    private void onFocusChanged(long selection, int change) {
        if (!active || selection != selectionGeneration || focusRequest == null) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusGranted = true;
            focusPaused = false;
            ducked = false;
            applyVolume();
            if (prepared) report("");
            startIfAllowed();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            ducked = true;
            applyVolume();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            focusGranted = false;
            focusPaused = true;
            pausePlayer();
            report("Lecture en pause pendant une interruption audio.");
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            fail("Lecture interrompue par une autre application. Choisissez à nouveau une ambiance. Retour : photos.");
        }
    }

    private void startIfAllowed() {
        if (!active || !prepared || player == null || hostPaused || !focusGranted || focusPaused) return;
        try {
            player.start();
            video.setKeepScreenOn(true);
        } catch (IllegalStateException failure) {
            fail("Lecture interrompue. Réessayez depuis la playlist. Retour : photos.");
        }
    }

    private void pausePlayer() {
        video.setKeepScreenOn(false);
        if (player == null || !prepared) return;
        try {
            if (player.isPlaying()) player.pause();
        } catch (IllegalStateException failure) {
            fail("Lecture interrompue. Réessayez depuis la playlist. Retour : photos.");
        }
    }

    private void applyVolume() {
        if (player == null) return;
        float level = ducked ? VOLUME * 0.2f : VOLUME;
        try {
            player.setVolume(level, level);
        } catch (IllegalStateException ignored) { }
    }

    private void watchPreparation(long selection, long generation) {
        clearPrepareTimeout();
        prepareTimeout = () -> {
            if (active && !prepared && selection == selectionGeneration && generation == playerGeneration) {
                fail("La vidéo ne répond pas. Vérifiez le MP4 ou la connexion, puis réessayez. Retour : photos.");
            }
        };
        main.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
    }

    private void clearPrepareTimeout() {
        if (prepareTimeout != null) main.removeCallbacks(prepareTimeout);
        prepareTimeout = null;
    }

    private boolean isCurrent(MediaPlayer media, long selection, long generation) {
        return active && player == media && selection == selectionGeneration && generation == playerGeneration;
    }

    private void stopInternal() {
        active = false;
        selectionGeneration++;
        source = null;
        releasePlayer();
        abandonFocus();
        hostPaused = false;
        bufferWidth = bufferHeight = 0;
        holder.setSizeFromLayout();
    }

    private void releasePlayer() {
        playerGeneration++;
        clearPrepareTimeout();
        prepared = false;
        videoWidth = videoHeight = 0;
        video.setKeepScreenOn(false);
        shutter.setVisibility(View.VISIBLE);
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            old.setOnPreparedListener(null);
            old.setOnVideoSizeChangedListener(null);
            old.setOnInfoListener(null);
            old.setOnErrorListener(null);
            old.release();
        }
    }

    private void abandonFocus() {
        AudioFocusRequest old = focusRequest;
        focusRequest = null;
        focusGranted = focusPaused = ducked = false;
        if (audioManager != null && old != null) audioManager.abandonAudioFocusRequest(old);
    }

    private void fail(String message) {
        stopInternal();
        report(message);
    }

    private void report(String message) {
        if (status != null) status.accept(message);
    }

    /** Keep the native buffer at source resolution while centering the entire picture in the UI. */
    private void fitVideo() {
        int width = videoLayer.getWidth(), height = videoLayer.getHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return;
        if (bufferWidth != videoWidth || bufferHeight != videoHeight) {
            bufferWidth = videoWidth;
            bufferHeight = videoHeight;
            holder.setFixedSize(bufferWidth, bufferHeight);
        }
        float scale = Math.min((float) width / videoWidth, (float) height / videoHeight);
        int fittedWidth = Math.max(1, Math.round(videoWidth * scale));
        int fittedHeight = Math.max(1, Math.round(videoHeight * scale));
        FrameLayout.LayoutParams position = (FrameLayout.LayoutParams) video.getLayoutParams();
        if (position.width != fittedWidth || position.height != fittedHeight) {
            position.width = fittedWidth;
            position.height = fittedHeight;
            position.gravity = Gravity.CENTER;
            video.setLayoutParams(position);
        }
    }

    private static boolean isDirectHttps(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        Uri uri = Uri.parse(url.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return !host.equals("youtube.com") && !host.endsWith(".youtube.com")
                && !host.equals("youtu.be") && !host.endsWith(".youtu.be")
                && !host.equals("youtube-nocookie.com") && !host.endsWith(".youtube-nocookie.com");
    }
}
