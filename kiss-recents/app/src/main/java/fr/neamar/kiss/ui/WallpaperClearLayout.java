package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Root container that explicitly clears the translucent window buffer before drawing children when
 * wallpaper-enabled / transparent themes are active, preventing ghosting and stale shadow trails.
 */
public class WallpaperClearLayout extends RelativeLayout {

    private static final int DARK_OVERLAY_COLOR = 0x4D000000;

    private boolean clearTransparentViewport = false;
    private boolean darkWallpaperOverlay = true;

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

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (clearTransparentViewport) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            if (darkWallpaperOverlay) {
                canvas.drawColor(DARK_OVERLAY_COLOR, PorterDuff.Mode.SRC_OVER);
            }
        }
        super.onDraw(canvas);
    }
}
