package fr.neamar.kiss.utils;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Caches a Dual-Kawase-blurred wallpaper bitmap keyed by system wallpaper ID for devices where
 * native SurfaceFlinger cross-window blur is unavailable or disabled.
 */
public final class WallpaperBlurCache {

    private static volatile Bitmap cachedBlurredWallpaper;
    private static volatile int cachedWallpaperId = Integer.MIN_VALUE;
    private static final AtomicBoolean FETCH_IN_FLIGHT = new AtomicBoolean(false);

    private WallpaperBlurCache() {
    }

    @Nullable
    public static Bitmap get(@NonNull Context context) {
        Bitmap bmp = cachedBlurredWallpaper;
        if (bmp == null || bmp.isRecycled()) {
            return null;
        }
        int currentId = readWallpaperId(context);
        if (currentId != Integer.MIN_VALUE && cachedWallpaperId != currentId) {
            return null;
        }
        return bmp;
    }

    public static void requestAsyncIfNeeded(@NonNull Context context, @NonNull Runnable onReady) {
        if (get(context) != null || !RecentsBridgeClient.isAvailable()) {
            return;
        }
        if (!FETCH_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final int targetWallpaperId = readWallpaperId(appCtx);
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
