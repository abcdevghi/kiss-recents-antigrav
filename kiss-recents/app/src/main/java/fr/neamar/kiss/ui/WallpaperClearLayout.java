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

    private boolean clearTransparentViewport = false;

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

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (clearTransparentViewport) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        }
        super.onDraw(canvas);
    }
}
