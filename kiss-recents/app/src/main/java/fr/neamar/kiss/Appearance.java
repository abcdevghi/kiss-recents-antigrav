package fr.neamar.kiss;

import static androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
import static androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;
import static androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import java.lang.ref.WeakReference;

import fr.neamar.kiss.utils.DrawableUtils;
import fr.neamar.kiss.utils.WallpaperBlurCache;

/**
 * Applies the appearance preferences (theme, colors, shapes, sizes). They are imported from KISS,
 * see {@link fr.neamar.kiss.settings.AppearanceImport}.
 */
public final class Appearance {

    /**
     * Preferences that make up the appearance. Only these are read from an import.
     */
    public static final String[] KEYS = {
            "theme", "night-mode",
            "primary-color", "theme-bar-color", "notification-bar-color", "black-notification-icons",
            "theme-result-color", "theme-shadow", "theme-separator", "theme-wallpaper",
            "pref-rounded-list", "pref-rounded-bars",
            "results-size",
            "icons-pack", "themed-icons", "adaptive-shape", "force-adaptive", "force-shape", "icons-hide"
    };

    private Appearance() {
    }

    /**
     * Must be called before super.onCreate() / setContentView().
     */
    public static void applyTheme(Activity act, SharedPreferences prefs) {
        switch (getTheme(prefs)) {
            case "transparent":
                act.setTheme(R.style.AppThemeTransparent);
                break;
            case "semi-transparent":
                act.setTheme(R.style.AppThemeSemiTransparent);
                break;
            case "amoled-dark":
                act.setTheme(R.style.AppThemeAmoledDark);
                break;
            case "opaque":
            default:
                act.setTheme(R.style.AppTheme);
        }

        UIColors.applyOverlay(act, prefs);

        switch (prefs.getString("results-size", "")) {
            case "smallest":
                act.getTheme().applyStyle(R.style.OverlayResultSizeSmallest, true);
                break;
            case "small":
                act.getTheme().applyStyle(R.style.OverlayResultSizeSmall, true);
                break;
            case "medium":
                act.getTheme().applyStyle(R.style.OverlayResultSizeMedium, true);
                break;
            case "large":
                act.getTheme().applyStyle(R.style.OverlayResultSizeLarge, true);
                break;
            case "largest":
                act.getTheme().applyStyle(R.style.OverlayResultSizeLargest, true);
                break;
            case "default":
            default:
                act.getTheme().applyStyle(R.style.OverlayResultSizeStandard, true);
                break;
        }

        if (prefs.getBoolean("icons-hide", false)) {
            act.getTheme().applyStyle(R.style.OverlayResultSizeNoIcons, true);
        }
    }

    public static boolean isWallpaperEnabled(SharedPreferences prefs) {
        String wallpaperStyle = prefs.getString("theme-wallpaper", "default");
        if ("enabled".equals(wallpaperStyle)) {
            return true;
        }
        if ("disabled".equals(wallpaperStyle) || "disabled-light".equals(wallpaperStyle)) {
            return false;
        }
        return !"amoled-dark".equals(getTheme(prefs));
    }

    public static boolean isBlurEnabled(SharedPreferences prefs) {
        return prefs.getBoolean("wallpaper-blur", false);
    }

    public static boolean isCrossWindowBlurSupported(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        WindowManager wm = context.getSystemService(WindowManager.class);
        return wm != null && wm.isCrossWindowBlurEnabled();
    }

    /**
     * Explicitly synchronize wallpaper window flags, blur flags, pixel format, and window background
     * so wallpaper-enabled and opaque themes switch cleanly without transparent frame glitches.
     */
    public static void applyWindowWallpaper(Activity act, SharedPreferences prefs) {
        Window window = act.getWindow();
        if (window == null) {
            return;
        }
        if (isWallpaperEnabled(prefs)) {
            final boolean dimOverlay = prefs.getBoolean("wallpaper-dim-overlay", false);
            final boolean blurEnabled = isBlurEnabled(prefs);
            final boolean hasCachedBlur = blurEnabled && WallpaperBlurCache.get(act) != null;
            final boolean nativeBlur = blurEnabled && !hasCachedBlur && isCrossWindowBlurSupported(act);

            window.setFormat(PixelFormat.TRANSLUCENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            applyWindowBlurState(act, window, nativeBlur);

            final WeakReference<Activity> actRef = new WeakReference<>(act);
            window.setBackgroundDrawable(new Drawable() {
                private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
                private final Rect srcRect = new Rect();
                private final Rect dstRect = new Rect();

                @Override
                public void draw(@NonNull Canvas canvas) {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    Activity host = actRef.get();
                    if (blurEnabled && host != null) {
                        Bitmap blurred = WallpaperBlurCache.get(host);
                        if (blurred != null && !blurred.isRecycled()) {
                            drawCenterCrop(canvas, blurred, bitmapPaint, srcRect, dstRect);
                        }
                    }
                    if (dimOverlay) {
                        canvas.drawColor(0x4D000000, PorterDuff.Mode.SRC_OVER);
                    }
                }

                @Override
                public void setAlpha(int alpha) {
                }

                @Override
                public void setColorFilter(@Nullable ColorFilter colorFilter) {
                }

                @Override
                public int getOpacity() {
                    return PixelFormat.TRANSLUCENT;
                }
            });
        } else {
            applyWindowBlurState(act, window, false);
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            window.setFormat(PixelFormat.OPAQUE);
            try (TypedArray ta = act.obtainStyledAttributes(new int[]{android.R.attr.windowBackground})) {
                Drawable bg = ta.getDrawable(0);
                window.setBackgroundDrawable(bg != null ? bg : new ColorDrawable(Color.BLACK));
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void applyWindowBlurState(Activity act, Window window, boolean nativeBlur) {
        WindowManager.LayoutParams lp = window.getAttributes();
        if (nativeBlur) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lp.setBlurBehindRadius(DrawableUtils.dpToPx(act, 26f));
            }
            lp.dimAmount = 0f;
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lp.setBlurBehindRadius(0);
            }
        }
        window.setAttributes(lp);
    }

    public static void drawCenterCrop(
            @NonNull Canvas canvas,
            @NonNull Bitmap bitmap,
            @NonNull Paint paint,
            @NonNull Rect srcRect,
            @NonNull Rect dstRect) {
        int canvasW = canvas.getWidth();
        int canvasH = canvas.getHeight();
        int bmpW = bitmap.getWidth();
        int bmpH = bitmap.getHeight();
        if (canvasW <= 0 || canvasH <= 0 || bmpW <= 0 || bmpH <= 0) {
            return;
        }
        float canvasRatio = (float) canvasW / canvasH;
        float bmpRatio = (float) bmpW / bmpH;
        if (bmpRatio > canvasRatio) {
            int cropW = Math.max(1, Math.round(bmpH * canvasRatio));
            int left = (bmpW - cropW) / 2;
            srcRect.set(left, 0, left + cropW, bmpH);
        } else {
            int cropH = Math.max(1, Math.round(bmpW / canvasRatio));
            int top = (bmpH - cropH) / 2;
            srcRect.set(0, top, bmpW, top + cropH);
        }
        dstRect.set(0, 0, canvasW, canvasH);
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint);
    }

    /**
     * Must be called before super.onCreate() / setContentView().
     */
    public static void applySettingsTheme(Activity act, SharedPreferences prefs) {
        if ("amoled-dark".equals(getTheme(prefs))) {
            act.setTheme(R.style.SettingThemeAmoledDark);
        } else {
            act.setTheme(R.style.SettingTheme);
        }
        UIColors.applyAccentColor(act);
        UIColors.updateThemePrimaryColor(act);
    }

    public static void setDefaultNightMode(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if ("amoled-dark".equals(getTheme(prefs))) {
            AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES);
            return;
        }

        switch (prefs.getString("night-mode", "follow-system")) {
            case "follow-system":
                AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            case "yes":
                AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES);
                break;
            case "no":
            default:
                AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_NO);
                break;
        }
    }

    /**
     * Rounded bars and list, as in KISS, without casting shadow artifacts on transparent containers.
     */
    public static void applyRoundedCorners(SharedPreferences prefs, View searchBar, View resultLayout) {
        Context context = resultLayout.getContext();
        float opaqueElevation = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 2f, context.getResources().getDisplayMetrics());

        int listBgColor = resolveAttrColor(context, R.attr.listBackgroundColor, Color.WHITE);

        if (searchBar != null) {
            searchBar.setBackground(null);
            searchBar.setElevation(0f);
        }

        if (prefs.getBoolean("pref-rounded-list", false) && Color.alpha(listBgColor) > 0) {
            resultLayout.setBackgroundResource(R.drawable.rounded_result_layout);
            resultLayout.setClipToOutline(true);
        } else {
            resultLayout.setClipToOutline(false);
        }
        resultLayout.setElevation(Color.alpha(listBgColor) == 255 ? opaqueElevation : 0f);
    }

    private static int resolveAttrColor(Context context, @AttrRes int attr, int fallback) {
        try (TypedArray ta = context.obtainStyledAttributes(new int[]{attr})) {
            return ta.getColor(0, fallback);
        }
    }

    /**
     * A value that changes whenever one of the appearance preferences changes,
     * so that an open screen knows it has to be rebuilt.
     */
    public static String signature(SharedPreferences prefs) {
        StringBuilder signature = new StringBuilder();
        for (String key : KEYS) {
            signature.append(key).append('=').append(prefs.getAll().get(key)).append(';');
        }
        return signature.toString();
    }

    private static String getTheme(SharedPreferences prefs) {
        return prefs.getString("theme", "transparent");
    }
}
