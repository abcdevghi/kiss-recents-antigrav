package fr.neamar.kiss.utils;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Pure-Java Dual Kawase pyramid blur operating on a downscaled ARGB_8888 buffer.
 * Uses mirrored-edge clamping and classic Kawase downsample (center*4 + 4 half-pixel diagonals)/8
 * followed by symmetric upsample (4 cardinals*2 + 4 diagonals)/12 passes for smooth bokeh without blocking.
 */
public final class DualKawaseBlur {

    private DualKawaseBlur() {
    }

    @Nullable
    public static Bitmap blur(@Nullable Bitmap source, int passes, float offsetScale) {
        if (source == null || source.isRecycled() || passes <= 0) {
            return null;
        }

        int srcW = source.getWidth();
        int srcH = source.getHeight();
        if (srcW < 4 || srcH < 4) {
            return null;
        }

        // Start from a compact working copy (~360px max) for fast < 4ms CPU pyramid filtering
        float initialScale = Math.min(1f, 360f / Math.max(srcW, srcH));
        int w0 = Math.max(16, Math.round(srcW * initialScale));
        int h0 = Math.max(16, Math.round(srcH * initialScale));

        Bitmap work = Bitmap.createScaledBitmap(source, w0, h0, true);
        if (work.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap converted = work.copy(Bitmap.Config.ARGB_8888, true);
            if (work != source) {
                work.recycle();
            }
            if (converted == null) {
                return null;
            }
            work = converted;
        }

        int clampedPasses = Math.max(1, Math.min(4, passes));
        int[] curPixels = new int[w0 * h0];
        work.getPixels(curPixels, 0, w0, 0, 0, w0, h0);
        if (work != source) {
            work.recycle();
        }

        int curW = w0;
        int curH = h0;
        int[][] pyramidPixels = new int[clampedPasses + 1][];
        int[] pyramidW = new int[clampedPasses + 1];
        int[] pyramidH = new int[clampedPasses + 1];
        pyramidPixels[0] = curPixels;
        pyramidW[0] = curW;
        pyramidH[0] = curH;

        float step = Math.max(0.75f, offsetScale);

        // Downsample passes
        for (int p = 1; p <= clampedPasses; p++) {
            int nextW = Math.max(8, (curW + 1) / 2);
            int nextH = Math.max(8, (curH + 1) / 2);
            int[] next = kawaseDownsample(curPixels, curW, curH, nextW, nextH, step);
            pyramidPixels[p] = next;
            pyramidW[p] = nextW;
            pyramidH[p] = nextH;
            curPixels = next;
            curW = nextW;
            curH = nextH;
        }

        // Upsample passes
        for (int p = clampedPasses - 1; p >= 0; p--) {
            int targetW = pyramidW[p];
            int targetH = pyramidH[p];
            curPixels = kawaseUpsample(curPixels, curW, curH, targetW, targetH, step);
            curW = targetW;
            curH = targetH;
        }

        Bitmap out = Bitmap.createBitmap(curW, curH, Bitmap.Config.ARGB_8888);
        out.setPixels(curPixels, 0, curW, 0, 0, curW, curH);
        return out;
    }

    @NonNull
    private static int[] kawaseDownsample(int[] src, int sw, int sh, int dw, int dh, float offset) {
        int[] dst = new int[dw * dh];
        float scaleX = (float) sw / dw;
        float scaleY = (float) sh / dh;
        int d = Math.max(1, Math.round(offset));

        for (int y = 0; y < dh; y++) {
            int sy = Math.min(sh - 1, Math.round((y + 0.5f) * scaleY - 0.5f));
            int syUp = clampCoord(sy - d, sh);
            int syDown = clampCoord(sy + d, sh);
            int rowCenter = sy * sw;
            int rowUp = syUp * sw;
            int rowDown = syDown * sw;
            int dstRow = y * dw;

            for (int x = 0; x < dw; x++) {
                int sx = Math.min(sw - 1, Math.round((x + 0.5f) * scaleX - 0.5f));
                int sxLeft = clampCoord(sx - d, sw);
                int sxRight = clampCoord(sx + d, sw);

                int c0 = src[rowCenter + sx];
                int c1 = src[rowUp + sxLeft];
                int c2 = src[rowUp + sxRight];
                int c3 = src[rowDown + sxLeft];
                int c4 = src[rowDown + sxRight];

                int r = (((c0 >> 16) & 0xFF) * 4 + ((c1 >> 16) & 0xFF) + ((c2 >> 16) & 0xFF)
                        + ((c3 >> 16) & 0xFF) + ((c4 >> 16) & 0xFF) + 4) >> 3;
                int g = (((c0 >> 8) & 0xFF) * 4 + ((c1 >> 8) & 0xFF) + ((c2 >> 8) & 0xFF)
                        + ((c3 >> 8) & 0xFF) + ((c4 >> 8) & 0xFF) + 4) >> 3;
                int b = ((c0 & 0xFF) * 4 + (c1 & 0xFF) + (c2 & 0xFF)
                        + (c3 & 0xFF) + (c4 & 0xFF) + 4) >> 3;

                dst[dstRow + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return dst;
    }

    @NonNull
    private static int[] kawaseUpsample(int[] src, int sw, int sh, int dw, int dh, float offset) {
        int[] dst = new int[dw * dh];
        float scaleX = (float) sw / dw;
        float scaleY = (float) sh / dh;
        int d = Math.max(1, Math.round(offset));
        int dHalf = Math.max(1, d);
        int dCard = d * 2;

        for (int y = 0; y < dh; y++) {
            float fy = (y + 0.5f) * scaleY - 0.5f;
            int sy = clampCoord(Math.round(fy), sh);
            int syN = clampCoord(sy - dCard, sh);
            int syS = clampCoord(sy + dCard, sh);
            int syUp = clampCoord(sy - dHalf, sh);
            int syDown = clampCoord(sy + dHalf, sh);

            int rowC = sy * sw;
            int rowN = syN * sw;
            int rowS = syS * sw;
            int rowUp = syUp * sw;
            int rowDown = syDown * sw;
            int dstRow = y * dw;

            for (int x = 0; x < dw; x++) {
                float fx = (x + 0.5f) * scaleX - 0.5f;
                int sx = clampCoord(Math.round(fx), sw);
                int sxW = clampCoord(sx - dCard, sw);
                int sxE = clampCoord(sx + dCard, sw);
                int sxL = clampCoord(sx - dHalf, sw);
                int sxR = clampCoord(sx + dHalf, sw);

                int cN = src[rowN + sx];
                int cS = src[rowS + sx];
                int cW = src[rowC + sxW];
                int cE = src[rowC + sxE];

                int cNW = src[rowUp + sxL];
                int cNE = src[rowUp + sxR];
                int cSW = src[rowDown + sxL];
                int cSE = src[rowDown + sxR];

                int r = ((((cN >> 16) & 0xFF) + ((cS >> 16) & 0xFF) + ((cW >> 16) & 0xFF) + ((cE >> 16) & 0xFF)) * 2
                        + ((cNW >> 16) & 0xFF) + ((cNE >> 16) & 0xFF) + ((cSW >> 16) & 0xFF) + ((cSE >> 16) & 0xFF) + 6) / 12;
                int g = ((((cN >> 8) & 0xFF) + ((cS >> 8) & 0xFF) + ((cW >> 8) & 0xFF) + ((cE >> 8) & 0xFF)) * 2
                        + ((cNW >> 8) & 0xFF) + ((cNE >> 8) & 0xFF) + ((cSW >> 8) & 0xFF) + ((cSE >> 8) & 0xFF) + 6) / 12;
                int b = (((cN & 0xFF) + (cS & 0xFF) + (cW & 0xFF) + (cE & 0xFF)) * 2
                        + (cNW & 0xFF) + (cNE & 0xFF) + (cSW & 0xFF) + (cSE & 0xFF) + 6) / 12;

                dst[dstRow + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return dst;
    }

    private static int clampCoord(int v, int maxExclusive) {
        if (v < 0) {
            return 0;
        }
        if (v >= maxExclusive) {
            return maxExclusive - 1;
        }
        return v;
    }
}
