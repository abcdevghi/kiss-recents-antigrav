package fr.neamar.kiss;

import static androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
import static androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;
import static androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

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
            "theme-result-color", "theme-shadow", "theme-wallpaper",
            "pref-rounded-list", "pref-rounded-bars",
            "results-size",
            "icons-pack", "themed-icons", "adaptive-shape", "force-adaptive", "force-shape", "icons-hide"
    };

    private Appearance() {
    }

    /**
     * Must be called before setContentView().
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

    /**
     * Must be called before setContentView().
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
     * Rounded bars and list, as in KISS.
     */
    public static void applyRoundedCorners(SharedPreferences prefs, View searchBar, View resultLayout) {
        if (prefs.getBoolean("pref-rounded-bars", true)) {
            searchBar.setBackgroundResource(R.drawable.rounded_search_bar);
        }
        if (prefs.getBoolean("pref-rounded-list", false)) {
            resultLayout.setBackgroundResource(R.drawable.rounded_result_layout);
            resultLayout.setClipToOutline(true);
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
