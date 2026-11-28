package fr.neamar.kiss.utils;

import android.os.Build;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public final class SystemBars {
    private SystemBars() {
    }

    /**
     * Pad {@code view} for the system bars, giving the top inset to {@code topView}.
     * Needed from Android 15 on, where apps are drawn edge-to-edge.
     */
    public static void applyInsets(View view, View topView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom);
                topView.setPadding(topView.getPaddingLeft(), systemBars.top, topView.getPaddingRight(), topView.getPaddingBottom());
                return WindowInsetsCompat.CONSUMED;
            });
        }
    }
}
