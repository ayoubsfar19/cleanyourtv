package com.gathin.ambienttv;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Full-screen mode: loops through the pictures sync.sh put in the app's pictures/ folder, in filename order,
 * each slowly zooming and drifting (Ken Burns) and crossfading into the next after seconds.txt (30 s by
 * default). Two screen-sized pictures at most, held as GPU bitmaps. MP4 clips play once, muted and cropped to
 * fill the screen, on the hardware decoder (one player at a time), then the loop moves on.
 */
final class Slides {

    private static final long FADE_MS = 1500L;
    /** How far a picture zooms in over its time on screen; it drifts at most half the extra width. */
    private static final float ZOOM = 1.12f;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    /** The month and year of the picture on screen, or null when the option is off. */
    private final TextView caption;
    private final View empty;
    private ThreadPoolExecutor decoder;
    private volatile long requestGeneration;
    /** Last requested position, including requests still being decoded. Main thread only. */
    private int requestedIndex;
    private Runnable fadeCleanup;
    private long pendingClipGeneration;
    private ImageView front;
    private ImageView back;
    private final TextureView video;
    private MediaPlayer player;
    private Surface surface;
    /** A clip waiting for the TextureView's surface. */
    private File pendingClip;
    /** Bumped for every clip so callbacks of a released player are ignored. */
    private int clipGeneration;
    private final List<File> images;
    private final SharedPreferences preferences;
    private final long interval;
    private final int width;
    private final int height;
    private boolean running;

    private final Runnable advance = this::showNext;

    Slides(Context context, FrameLayout stage, TextView caption, View empty) {
        this.context = context;
        this.caption = caption;
        this.empty = empty;
        video = new TextureView(context);
        video.setAlpha(0f);
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
                surface = new Surface(texture);
                File clip = pendingClip;
                pendingClip = null;
                if (clip != null && running) playVideo(clip, pendingClipGeneration);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                stopVideo();
                if (surface != null) surface.release();
                surface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });
        stage.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        back = image(stage);
        front = image(stage);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        preferences = context.getSharedPreferences("photo_dream", Context.MODE_PRIVATE);
        File dir = context.getExternalFilesDir(null);
        interval = Media.seconds(Photos.read(dir == null ? null : new File(dir, Media.SECONDS))) * 1000L;
        images = dir == null ? new ArrayList<>() : Media.list(new File(dir, Media.PICTURES));
        requestedIndex = Media.indexOf(images, preferences.getString("image", ""));
        if (caption != null) caption.setAlpha(0f);
    }

    private ImageView image(FrameLayout stage) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setAlpha(0f);
        stage.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return view;
    }

    void start() {
        running = true;
        empty.setVisibility(images.isEmpty() ? View.VISIBLE : View.GONE);
        if (decoder == null) decoder = new ThreadPoolExecutor(1, 1, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        showNext();
    }

    void stop() {
        running = false;
        requestGeneration++;
        handler.removeCallbacks(advance);
        if (fadeCleanup != null) handler.removeCallbacks(fadeCleanup);
        fadeCleanup = null;
        // Let posted decode results run their generation check and recycle their bitmaps.
        if (caption != null) caption.animate().withEndAction(null).cancel();
        if (decoder != null) decoder.shutdownNow();
        decoder = null;
        if (front != null) { cancel(front); release(front); }
        if (back != null) { cancel(back); release(back); }
        pendingClip = null;
        stopVideo();
    }

    /** Consumes directional keys while the slideshow is active, including pending decodes. */
    boolean navigate(int direction) {
        if (!running || images.isEmpty() || decoder == null) return false;
        pendingClip = null;
        stopVideo();
        request(direction < 0 ? -1 : 1);
        return true;
    }

    private void showNext() {
        request(1);
    }

    /** Each request supersedes both queued work and callbacks from the previous transition. */
    private void request(int direction) {
        if (!running || images.isEmpty() || decoder == null) return;
        final long token = ++requestGeneration;
        handler.removeCallbacks(advance);
        if (fadeCleanup != null) handler.removeCallbacks(fadeCleanup);
        fadeCleanup = null;
        pendingClip = null;
        if (caption != null) caption.animate().withEndAction(null).cancel();
        cancel(front);
        cancel(back);
        // Keep the current picture intact while the replacement is decoded. The other view is
        // free for reuse, so an interrupted crossfade cannot later recycle the new picture.
        if (front.getTag() instanceof Bitmap) front.setAlpha(1f);
        back.setAlpha(0f);
        release(back);
        requestedIndex = Media.step(requestedIndex, images.size(), direction);
        final int firstIndex = requestedIndex;
        decoder.getQueue().clear();
        decoder.execute(() -> {
            if (token != requestGeneration) return;
            Bitmap bitmap = null;
            File clip = null;
            String date = "";
            int index = firstIndex;
            for (int tries = 0; tries < images.size(); tries++) {
                if (token != requestGeneration) return;
                File file = images.get(index);
                if (Media.video(file.getName())) clip = file;
                else {
                    bitmap = decode(file);
                    if (bitmap != null && caption != null) date = Photos.dateOf(file);
                }
                if (token != requestGeneration) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap != null || clip != null) break;
                index = Media.step(index, images.size(), direction);
            }
            Bitmap shown = bitmap;
            File playing = clip;
            String shownDate = date;
            int shownIndex = index;
            handler.post(() -> {
                if (!running || token != requestGeneration) {
                    if (shown != null) shown.recycle();
                    return;
                }
                if (playing != null) {
                    requestedIndex = shownIndex;
                    showCaption("", token);
                    playVideo(playing, token);
                    return;
                }
                if (shown != null) {
                    requestedIndex = shownIndex;
                    crossfade(shown, token);
                    preferences.edit().putString("image", images.get(shownIndex).getName()).apply();
                    showCaption(shownDate, token);
                }
                // Restart a full interval after the latest picture, or retry a broken folder later.
                scheduleAdvance(interval, token);
            });
        });
    }

    private void scheduleAdvance(long delay, long token) {
        if (!running || token != requestGeneration) return;
        handler.removeCallbacks(advance);
        handler.postDelayed(advance, delay);
    }

    private void showCaption(String date, long token) {
        if (caption == null) return;
        caption.animate().cancel();
        if (date.isEmpty()) {
            caption.animate().alpha(0f).setDuration(FADE_MS / 2).start();
            return;
        }
        caption.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> {
            if (!running || token != requestGeneration) return;
            caption.setText(date);
            caption.animate().alpha(1f).setDuration(FADE_MS / 2).start();
        }).start();
    }

    private void crossfade(Bitmap bitmap, long token) {
        ImageView incoming = back;
        ImageView outgoing = front;
        back = outgoing;
        front = incoming;
        cancel(incoming);
        release(incoming);
        incoming.setImageBitmap(bitmap);
        incoming.setTag(bitmap);
        incoming.bringToFront();
        // Start zoomed in or out at random and drift toward a random spot, over the whole slide.
        boolean zoomIn = random.nextBoolean();
        float from = zoomIn ? 1f : ZOOM;
        float to = zoomIn ? ZOOM : 1f;
        float dx = (random.nextFloat() - 0.5f) * width * (ZOOM - 1f);
        float dy = (random.nextFloat() - 0.5f) * height * (ZOOM - 1f);
        incoming.setAlpha(0f);
        ObjectAnimator fade = ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f).setDuration(FADE_MS);
        ObjectAnimator drift = ObjectAnimator.ofPropertyValuesHolder(incoming,
                PropertyValuesHolder.ofFloat(View.SCALE_X, from, to),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, from, to),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, zoomIn ? 0f : dx, zoomIn ? dx : 0f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, zoomIn ? 0f : dy, zoomIn ? dy : 0f))
                .setDuration(interval + 2 * FADE_MS);
        drift.setInterpolator(new LinearInterpolator());
        AnimatorSet set = new AnimatorSet();
        set.playTogether(fade, drift);
        incoming.setTag(R.id.animation, set);
        set.start();
        boolean afterClip = player != null;
        fadeCleanup = () -> {
            if (!running || token != requestGeneration) return;
            fadeCleanup = null;
            cancel(outgoing);
            outgoing.setAlpha(0f);
            release(outgoing);
            if (afterClip) stopVideo();
        };
        handler.postDelayed(fadeCleanup, FADE_MS + 100);
    }

    /** Fades the clip in over whatever shows now and plays it to the end (or loops a lone clip). */
    private void playVideo(File clip, long token) {
        if (!running || token != requestGeneration) return;
        if (surface == null) {
            pendingClip = clip;
            pendingClipGeneration = token;
            return;
        }
        boolean clipShowing = player != null;
        stopVideo();
        int generation = ++clipGeneration;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(clip.getPath());
            mp.setSurface(surface);
            mp.setVolume(0f, 0f);
            mp.setLooping(images.size() == 1);
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                if (generation == clipGeneration && token == requestGeneration) cover(w, h);
            });
            mp.setOnPreparedListener(p -> {
                if (generation != clipGeneration || token != requestGeneration || !running) return;
                p.start();
                preferences.edit().putString("image", clip.getName()).apply();
                video.bringToFront();
                if (clipShowing) video.setAlpha(1f);
                else video.animate().alpha(1f).setDuration(FADE_MS).start();
                fadeCleanup = () -> {
                    if (generation != clipGeneration || token != requestGeneration || !running) return;
                    fadeCleanup = null;
                    for (ImageView view : new ImageView[] {front, back}) {
                        cancel(view);
                        view.setAlpha(0f);
                        release(view);
                    }
                };
                handler.postDelayed(fadeCleanup, FADE_MS + 100);
            });
            mp.setOnCompletionListener(p -> {
                if (generation == clipGeneration && token == requestGeneration && running) showNext();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (generation == clipGeneration && token == requestGeneration && running) {
                    stopVideo();
                    scheduleAdvance(1000, token);
                }
                return true;
            });
            mp.prepareAsync();
        } catch (Exception failure) {
            stopVideo();
            scheduleAdvance(1000, token);
        }
    }

    /** Scales the clip to fill the screen, cropping the overflow evenly (like CENTER_CROP). */
    private void cover(int videoWidth, int videoHeight) {
        int w = video.getWidth(), h = video.getHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || w <= 0 || h <= 0) return;
        float scale = Math.max((float) w / videoWidth, (float) h / videoHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(videoWidth * scale / w, videoHeight * scale / h, w / 2f, h / 2f);
        video.setTransform(matrix);
    }

    private void stopVideo() {
        clipGeneration++;
        video.animate().cancel();
        video.setAlpha(0f);
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) { }
            player.release();
            player = null;
        }
    }

    private static void cancel(ImageView view) {
        Object set = view.getTag(R.id.animation);
        if (set instanceof AnimatorSet) ((AnimatorSet) set).cancel();
        view.setTag(R.id.animation, null);
    }

    /** Frees the view's picture now instead of waiting for the garbage collector. */
    private static void release(ImageView view) {
        Object old = view.getTag();
        view.setImageDrawable(null);
        view.setTag(null);
        if (old instanceof Bitmap) ((Bitmap) old).recycle();
    }

    private static int orientation(File file) {
        try {
            return new ExifInterface(file.getPath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private Bitmap decode(File file) {
        Bitmap decoded = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int w = options.outWidth, h = options.outHeight;
            if (w <= 0 || h <= 0) return null;
            int orientation = orientation(file);
            int[] oriented = Media.orientationDimensions(w, h, orientation);
            int sample = Media.sampleSize(oriented[0], oriented[1], width, height);
            if (!Media.accepted(w / sample, h / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = Media.coverScale(oriented[0] / sample, oriented[1] / sample, width, height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            Matrix transform = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: transform.setScale(-1f, 1f); break;
                case ExifInterface.ORIENTATION_ROTATE_180: transform.setRotate(180f); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: transform.setScale(1f, -1f); break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    transform.setRotate(90f); transform.postScale(-1f, 1f); break;
                case ExifInterface.ORIENTATION_ROTATE_90: transform.setRotate(90f); break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    transform.setRotate(-90f); transform.postScale(-1f, 1f); break;
                case ExifInterface.ORIENTATION_ROTATE_270: transform.setRotate(270f); break;
                default: break;
            }
            if (!transform.isIdentity()) {
                Bitmap upright = Bitmap.createBitmap(decoded, 0, 0,
                        decoded.getWidth(), decoded.getHeight(), transform, true);
                if (upright != decoded) decoded.recycle();
                decoded = upright;
            }
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
            return null;
        }
    }
}
