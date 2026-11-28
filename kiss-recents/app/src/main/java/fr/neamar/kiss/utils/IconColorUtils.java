package fr.neamar.kiss.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

/**
 * Extracts a vivid dominant color from an app's icon drawable for the selected banner outline.
 */
public final class IconColorUtils {

    private static final int SAMPLE_SIZE = 24;
    private static final int HUE_BUCKETS = 36;

    private IconColorUtils() {
    }

    @ColorInt
    public static int extractDominantColor(@Nullable Drawable drawable, @ColorInt int fallbackColor) {
        if (drawable == null) {
            return fallbackColor;
        }
        Bitmap sample = null;
        Drawable sampleDrawable = drawable;
        Rect restoredBounds = null;
        try {
            Drawable.ConstantState cs = drawable.getConstantState();
            if (cs != null) {
                Drawable clone = cs.newDrawable();
                if (clone != null) {
                    sampleDrawable = clone;
                }
            }
            if (sampleDrawable == drawable) {
                restoredBounds = drawable.copyBounds();
            }
            sample = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(sample);
            sampleDrawable.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
            sampleDrawable.draw(canvas);

            int[] pixels = new int[SAMPLE_SIZE * SAMPLE_SIZE];
            sample.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE);

            float[] bucketWeight = new float[HUE_BUCKETS];
            float[] bucketR = new float[HUE_BUCKETS];
            float[] bucketG = new float[HUE_BUCKETS];
            float[] bucketB = new float[HUE_BUCKETS];
            float[] hsv = new float[3];

            long neutralR = 0;
            long neutralG = 0;
            long neutralB = 0;
            int neutralCount = 0;

            for (int pixel : pixels) {
                int alpha = Color.alpha(pixel);
                if (alpha < 128) {
                    continue;
                }
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                neutralR += r;
                neutralG += g;
                neutralB += b;
                neutralCount++;

                Color.RGBToHSV(r, g, b, hsv);
                float sat = hsv[1];
                float val = hsv[2];
                // Ignore near-grayscale / near-black / near-white icon backgrounds when picking chromatic accent
                if (sat < 0.18f || val < 0.14f) {
                    continue;
                }
                if (val > 0.96f && sat < 0.25f) {
                    continue;
                }
                int bucket = Math.min(HUE_BUCKETS - 1, Math.max(0, (int) (hsv[0] / (360f / HUE_BUCKETS))));
                float weight = (alpha / 255f) * (0.4f + sat * 1.4f) * (0.35f + val * 0.65f);
                bucketWeight[bucket] += weight;
                bucketR[bucket] += r * weight;
                bucketG[bucket] += g * weight;
                bucketB[bucket] += b * weight;
            }

            int bestBucket = -1;
            float bestWeight = 0f;
            for (int i = 0; i < HUE_BUCKETS; i++) {
                if (bucketWeight[i] > bestWeight) {
                    bestWeight = bucketWeight[i];
                    bestBucket = i;
                }
            }

            if (bestBucket >= 0 && bestWeight > 0f) {
                int r = Math.min(255, Math.round(bucketR[bestBucket] / bestWeight));
                int g = Math.min(255, Math.round(bucketG[bestBucket] / bestWeight));
                int b = Math.min(255, Math.round(bucketB[bestBucket] / bestWeight));
                return ensureVisibleOutline(Color.rgb(r, g, b), true);
            }

            if (neutralCount > 0) {
                int r = (int) (neutralR / neutralCount);
                int g = (int) (neutralG / neutralCount);
                int b = (int) (neutralB / neutralCount);
                return ensureVisibleOutline(Color.rgb(r, g, b), false);
            }
        } catch (Throwable ignored) {
        } finally {
            if (restoredBounds != null) {
                drawable.setBounds(restoredBounds);
            }
            if (sample != null && !sample.isRecycled()) {
                sample.recycle();
            }
        }
        return fallbackColor;
    }

    @ColorInt
    private static int ensureVisibleOutline(@ColorInt int color, boolean chromatic) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (chromatic) {
            hsv[1] = Math.max(hsv[1], 0.48f);
            hsv[2] = Math.max(hsv[2], 0.62f);
        } else {
            hsv[2] = Math.max(hsv[2], 0.58f);
        }
        return Color.HSVToColor(hsv);
    }
}
