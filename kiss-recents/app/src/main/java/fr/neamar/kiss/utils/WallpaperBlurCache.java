package fr.neamar.kiss.utils;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Caches a Dual-Kawase-blurred wallpaper bitmap keyed by system wallpaper ID for devices where
 * native SurfaceFlinger cross-window blur is unavailable or disabled.
 */
public final class WallpaperBlurCache {

    private static final long WALLPAPER_ID_CHECK_INTERVAL_MS = 2000L;

    private static volatile Bitmap cachedBlurredWallpaper;
    private static volatile int cachedWallpaperId = Integer.MIN_VALUE;
    private static volatile int lastObservedWallpaperId = Integer.MIN_VALUE;
    private static volatile long lastWallpaperIdCheckMs = 0L;
    private static final AtomicBoolean FETCH_IN_FLIGHT = new AtomicBoolean(false);

    private WallpaperBlurCache() {
    }

    @Nullable
    public static Bitmap get(@NonNull Context context) {
        Bitmap bmp = cachedBlurredWallpaper;
        if (bmp == null || bmp.isRecycled()) {
            return null;
        }
        int currentId = readWallpaperIdCached(context, false);
        if (currentId != Integer.MIN_VALUE && cachedWallpaperId != currentId) {
            return null;
        }
        return bmp;
    }

    public static void requestAsyncIfNeeded(@NonNull Context context, @NonNull Runnable onReady) {
        // Force a fresh wallpaper ID check on explicit sync requests (e.g. onResume), without
        // blocking every frame's onDraw() with a Binder IPC call.
        int freshId = readWallpaperIdCached(context, true);
        Bitmap existing = cachedBlurredWallpaper;
        if (existing != null && !existing.isRecycled()
                && (freshId == Integer.MIN_VALUE || cachedWallpaperId == freshId)) {
            return;
        }
        if (!RecentsBridgeClient.isAvailable()) {
            return;
        }
        if (!FETCH_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final int targetWallpaperId = freshId != Integer.MIN_VALUE ? freshId : readWallpaperId(appCtx);
        Utilities.getDefaultExecutor().execute(() -> {
            try {
                Bitmap raw = RecentsBridgeClient.getWallpaperBitmap();
                if (raw == null) {
                    return;
                }
                Bitmap blurred = DualKawaseBlur.blur(raw, 4, 1.75f);
                if (raw != blurred && !raw.isRecycled()) {
                    raw.recycle();
                }
                if (blurred != null) {
                    cachedBlurredWallpaper = blurred;
                    cachedWallpaperId = targetWallpaperId;
                    lastObservedWallpaperId = targetWallpaperId;
                    lastWallpaperIdCheckMs = SystemClock.uptimeMillis();
                    onReady.run();
                }
            } finally {
                FETCH_IN_FLIGHT.set(false);
            }
        });
    }

    public static void clear() {
        cachedBlurredWallpaper = null;
        cachedWallpaperId = Integer.MIN_VALUE;
        lastObservedWallpaperId = Integer.MIN_VALUE;
        lastWallpaperIdCheckMs = 0L;
    }

    private static int readWallpaperIdCached(@NonNull Context context, boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && lastWallpaperIdCheckMs > 0L
                && (now - lastWallpaperIdCheckMs) < WALLPAPER_ID_CHECK_INTERVAL_MS) {
            return lastObservedWallpaperId;
        }
        int id = readWallpaperId(context);
        lastObservedWallpaperId = id;
        lastWallpaperIdCheckMs = now;
        return id;
    }

    private static int readWallpaperId(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return Integer.MIN_VALUE;
        }
        try {
            WallpaperManager wm = WallpaperManager.getInstance(context);
            return wm != null ? wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) : Integer.MIN_VALUE;
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }
}
