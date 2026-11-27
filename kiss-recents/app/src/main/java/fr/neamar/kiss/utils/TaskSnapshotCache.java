package fr.neamar.kiss.utils;

import android.graphics.Bitmap;
import android.util.LruCache;

import androidx.annotation.Nullable;

/**
 * Bounded in-memory LRU cache for recent task preview bitmaps fetched via {@link RecentsBridgeClient}.
 */
public final class TaskSnapshotCache {

    private static final int MAX_CACHE_BYTES = 24 * 1024 * 1024;

    private static final LruCache<Integer, Bitmap> CACHE = new LruCache<Integer, Bitmap>(MAX_CACHE_BYTES) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return Math.max(1, value.getByteCount());
        }
    };

    private TaskSnapshotCache() {
    }

    @Nullable
    public static Bitmap get(int taskId) {
        if (taskId <= 0) {
            return null;
        }
        Bitmap bitmap = CACHE.get(taskId);
        if (bitmap != null && bitmap.isRecycled()) {
            CACHE.remove(taskId);
            return null;
        }
        return bitmap;
    }

    public static void put(int taskId, @Nullable Bitmap bitmap) {
        if (taskId <= 0 || bitmap == null || bitmap.isRecycled()) {
            return;
        }
        CACHE.put(taskId, bitmap);
    }

    public static void invalidate(int taskId) {
        if (taskId > 0) {
            CACHE.remove(taskId);
        }
    }

    public static void clear() {
        CACHE.evictAll();
    }
}
