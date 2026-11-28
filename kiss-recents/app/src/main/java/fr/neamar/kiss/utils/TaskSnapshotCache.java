package fr.neamar.kiss.utils;

import android.graphics.Bitmap;
import android.util.LruCache;

import androidx.annotation.Nullable;

/**
 * Bounded two-tier in-memory LRU cache for recent task preview bitmaps fetched via
 * {@link RecentsBridgeClient}: full/selected-tier captures and compact unfocused-tier thumbs.
 */
public final class TaskSnapshotCache {

    private static final int MAX_HIRES_CACHE_BYTES = 28 * 1024 * 1024;
    private static final int MAX_THUMB_CACHE_BYTES = 12 * 1024 * 1024;

    private static final LruCache<Integer, Bitmap> CACHE = new LruCache<Integer, Bitmap>(MAX_HIRES_CACHE_BYTES) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return Math.max(1, value.getByteCount());
        }
    };

    private static final LruCache<Integer, Bitmap> THUMB_CACHE = new LruCache<Integer, Bitmap>(MAX_THUMB_CACHE_BYTES) {
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

    /**
     * Return any immediately available snapshot for instant display (hi-res preferred, compact thumb fallback).
     */
    @Nullable
    public static Bitmap getBest(int taskId) {
        Bitmap hiRes = get(taskId);
        return hiRes != null ? hiRes : getThumb(taskId);
    }

    @Nullable
    public static Bitmap getThumb(int taskId) {
        if (taskId <= 0) {
            return null;
        }
        Bitmap thumb = THUMB_CACHE.get(taskId);
        if (thumb != null && thumb.isRecycled()) {
            THUMB_CACHE.remove(taskId);
            thumb = null;
        }
        return thumb != null ? thumb : get(taskId);
    }

    public static void put(int taskId, @Nullable Bitmap bitmap) {
        if (taskId <= 0 || bitmap == null || bitmap.isRecycled()) {
            return;
        }
        CACHE.put(taskId, bitmap);
    }

    public static void putThumb(int taskId, @Nullable Bitmap bitmap) {
        if (taskId <= 0 || bitmap == null || bitmap.isRecycled()) {
            return;
        }
        THUMB_CACHE.put(taskId, bitmap);
    }

    public static void invalidate(int taskId) {
        if (taskId > 0) {
            CACHE.remove(taskId);
            THUMB_CACHE.remove(taskId);
        }
    }

    public static void clear() {
        CACHE.evictAll();
        THUMB_CACHE.evictAll();
    }
}
