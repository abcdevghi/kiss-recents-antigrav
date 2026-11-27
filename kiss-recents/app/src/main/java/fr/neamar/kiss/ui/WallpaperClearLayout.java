package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import fr.neamar.kiss.Appearance;
import fr.neamar.kiss.utils.WallpaperBlurCache;

/**
 * Root container that explicitly clears the translucent window buffer before drawing children when
 * wallpaper-enabled / transparent themes are active, preventing ghosting and stale shadow trails.
 */
public class WallpaperClearLayout extends RelativeLayout {

    private static final int DARK_OVERLAY_COLOR = 0x4D000000;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect srcRect = new Rect();
    private final Rect dstRect = new Rect();

    private boolean clearTransparentViewport = false;
    private boolean darkWallpaperOverlay = true;
    private boolean wallpaperBlurEnabled = false;
    private boolean useNativeCrossWindowBlur = false;

    public WallpaperClearLayout(@NonNull Context context) {
        super(context);
    }

    public WallpaperClearLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public WallpaperClearLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setClearTransparentViewport(boolean enabled) {
        if (clearTransparentViewport != enabled) {
            clearTransparentViewport = enabled;
            setWillNotDraw(!enabled);
            invalidate();
        }
    }

    public void setDarkWallpaperOverlay(boolean enabled) {
        if (darkWallpaperOverlay != enabled) {
            darkWallpaperOverlay = enabled;
            invalidate();
        }
    }

    public void setWallpaperBlurEnabled(boolean enabled, boolean nativeCrossWindowBlur) {
        if (wallpaperBlurEnabled != enabled || useNativeCrossWindowBlur != nativeCrossWindowBlur) {
            wallpaperBlurEnabled = enabled;
            useNativeCrossWindowBlur = nativeCrossWindowBlur;
            invalidate();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (clearTransparentViewport) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            if (wallpaperBlurEnabled && !useNativeCrossWindowBlur) {
                Bitmap blurred = WallpaperBlurCache.get(getContext());
                if (blurred != null && !blurred.isRecycled()) {
                    Appearance.drawCenterCrop(canvas, blurred, bitmapPaint, srcRect, dstRect);
                }
            }
            if (darkWallpaperOverlay) {
                canvas.drawColor(DARK_OVERLAY_COLOR, PorterDuff.Mode.SRC_OVER);
            }
        }
        super.onDraw(canvas);
    }
}
