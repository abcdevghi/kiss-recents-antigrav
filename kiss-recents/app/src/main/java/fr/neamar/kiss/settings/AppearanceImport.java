package fr.neamar.kiss.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

import fr.neamar.kiss.Appearance;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.R;
import fr.neamar.kiss.UIColors;
import fr.neamar.kiss.utils.Log;

/**
 * Imports the appearance from the settings exported by KISS (Settings > Import/Export > Export settings),
 * which KISS puts on the clipboard as a JSON object. Everything but the appearance keys is ignored.
 */
public final class AppearanceImport {

    private static final String TAG = AppearanceImport.class.getSimpleName();

    private AppearanceImport() {
    }

    /**
     * @return the message to show to the user
     */
    @StringRes
    public static int importFromClipboard(Context context) {
        ClipboardManager clipboard = ContextCompat.getSystemService(context, ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return R.string.import_appearance_empty;
        }

        JSONObject json;
        try {
            json = new JSONObject(clip.getItemAt(0).coerceToText(context).toString());
        } catch (JSONException e) {
            Log.w(TAG, "Clipboard does not hold JSON", e);
            return R.string.import_appearance_invalid;
        }
        if (!json.has("__v")) {
            // Marker written by the KISS export
            return R.string.import_appearance_invalid;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : Appearance.KEYS) {
            if (!json.has(key)) {
                // The export only holds values that differ from the default: a missing key means default
                editor.remove(key);
                continue;
            }
            Object value = json.opt(key);
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            } else {
                editor.remove(key);
            }
        }
        if (!editor.commit()) {
            return R.string.import_appearance_save_error;
        }

        UIColors.clearColorCache();
        Appearance.setDefaultNightMode(context);
        // Any icon key triggers the same full reload of the icon pack and shape
        KissApplication.getApplication(context).getIconsHandler().onPrefChanged(prefs, "icons-pack");
        return R.string.import_appearance_done;
    }
}
