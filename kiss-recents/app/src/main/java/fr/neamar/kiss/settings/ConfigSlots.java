package fr.neamar.kiss.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages user-saved configuration slots and "empty config" resets.
 * An empty config has every toggleable visual/behavior switch set to false.
 */
public final class ConfigSlots {

    public static final int SLOT_COUNT = 4;
    private static final String PREFS_SLOTS = "kiss_recents_config_slots";

    public static final String[] SLOT_PREF_KEYS = {
            "config-slot-1",
            "config-slot-2",
            "config-slot-3",
            "config-slot-4"
    };

    private static final String[] SLOT_STORAGE_KEYS = {
            "slot_1",
            "slot_2",
            "slot_3",
            "slot_4"
    };

    /**
     * All toggleable feature/visual preferences.
     * In an empty config, every key here is false.
     */
    public static final String[] CONFIG_KEYS = {
            // Layout & sorting
            "focus-current-app",
            "reverse-order",
            "ram-history-sort",
            "ram-history-separators",
            "clear-all-keep-history",
            // Cards & previews
            "selected-banner",
            "more-banners",
            "more-banners-all",
            "more-banners-calculated-size",
            "selected-banner-icon",
            "selected-banner-outline",
            "snapshot-greyscale",
            "thumbnail-parallax",
            "clear-dead-thumbnails",
            // Motion & effects
            "carousel-arc",
            "carousel-depth-effects",
            "tilt-sway-overshoot",
            "tactile-scroll-snapping",
            "launch-entrance-anim",
            "open-sesame",
            "curved-context-menu",
            "show-scrollbar",
            // Memory & wallpaper
            "show-entry-ram",
            "show-total-ram",
            "wallpaper-dim-overlay",
            "wallpaper-blur"
    };

    public static final class SlotInfo {
        public final int index;
        public final boolean empty;
        @Nullable
        public final String name;
        public final long savedAtMs;
        public final int enabledCount;
        public final Map<String, Boolean> values;

        SlotInfo(int index, boolean empty, @Nullable String name, long savedAtMs,
                 int enabledCount, Map<String, Boolean> values) {
            this.index = index;
            this.empty = empty;
            this.name = name;
            this.savedAtMs = savedAtMs;
            this.enabledCount = enabledCount;
            this.values = values;
        }
    }

    private ConfigSlots() {
    }

    /**
     * One-time migration for legacy inverted preference keys so every toggle uses positive polarity.
     */
    public static void migrateLegacyPrefsIfNeeded(@NonNull SharedPreferences prefs) {
        boolean hasNoOutline = prefs.contains("selected-banner-no-outline");
        boolean hasFreeScroll = prefs.contains("free-scroll-banners");
        if (!hasNoOutline && !hasFreeScroll) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (hasNoOutline) {
            if (!prefs.contains("selected-banner-outline")) {
                editor.putBoolean("selected-banner-outline", !prefs.getBoolean("selected-banner-no-outline", false));
            }
            editor.remove("selected-banner-no-outline");
        }
        if (hasFreeScroll) {
            if (!prefs.contains("tactile-scroll-snapping")) {
                editor.putBoolean("tactile-scroll-snapping", !prefs.getBoolean("free-scroll-banners", false));
            }
            editor.remove("free-scroll-banners");
        }
        editor.apply();
    }

    @NonNull
    public static SlotInfo getSlotInfo(@NonNull Context context, int index) {
        if (index < 0 || index >= SLOT_COUNT) {
            return new SlotInfo(0, true, null, 0L, 0, Collections.emptyMap());
        }
        SharedPreferences slotPrefs = context.getSharedPreferences(PREFS_SLOTS, Context.MODE_PRIVATE);
        String raw = slotPrefs.getString(SLOT_STORAGE_KEYS[index], null);
        if (raw == null || raw.isEmpty()) {
            return new SlotInfo(index, true, null, 0L, 0, Collections.emptyMap());
        }
        try {
            JSONObject root = new JSONObject(raw);
            String name = root.optString("name", "").trim();
            if (name.isEmpty()) {
                name = null;
            }
            long savedAt = root.optLong("savedAt", 0L);
            JSONObject valuesObj = root.optJSONObject("values");
            Map<String, Boolean> values = new HashMap<>(CONFIG_KEYS.length);
            int enabledCount = 0;
            for (String key : CONFIG_KEYS) {
                boolean enabled = valuesObj != null && valuesObj.optBoolean(key, false);
                values.put(key, enabled);
                if (enabled) {
                    enabledCount++;
                }
            }
            return new SlotInfo(index, false, name, savedAt, enabledCount, Collections.unmodifiableMap(values));
        } catch (JSONException ignored) {
            return new SlotInfo(index, true, null, 0L, 0, Collections.emptyMap());
        }
    }

    public static boolean saveCurrentToSlot(@NonNull Context context, int index, @Nullable String customName) {
        if (index < 0 || index >= SLOT_COUNT) {
            return false;
        }
        SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        migrateLegacyPrefsIfNeeded(defaultPrefs);

        JSONObject root = new JSONObject();
        JSONObject valuesObj = new JSONObject();
        try {
            String trimmedName = customName == null ? "" : customName.trim();
            if (!trimmedName.isEmpty()) {
                root.put("name", trimmedName);
            }
            root.put("savedAt", System.currentTimeMillis());
            for (String key : CONFIG_KEYS) {
                valuesObj.put(key, defaultPrefs.getBoolean(key, false));
            }
            root.put("values", valuesObj);
        } catch (JSONException e) {
            return false;
        }

        SharedPreferences slotPrefs = context.getSharedPreferences(PREFS_SLOTS, Context.MODE_PRIVATE);
        return slotPrefs.edit().putString(SLOT_STORAGE_KEYS[index], root.toString()).commit();
    }

    public static boolean loadSlot(@NonNull Context context, int index) {
        SlotInfo info = getSlotInfo(context, index);
        SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = defaultPrefs.edit();
        editor.remove("selected-banner-no-outline");
        editor.remove("free-scroll-banners");
        for (String key : CONFIG_KEYS) {
            boolean val = !info.empty && Boolean.TRUE.equals(info.values.get(key));
            editor.putBoolean(key, val);
        }
        return editor.commit();
    }

    public static boolean resetToEmptyConfig(@NonNull Context context) {
        SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = defaultPrefs.edit();
        editor.remove("selected-banner-no-outline");
        editor.remove("free-scroll-banners");
        for (String key : CONFIG_KEYS) {
            editor.putBoolean(key, false);
        }
        return editor.commit();
    }

    public static boolean renameSlot(@NonNull Context context, int index, @Nullable String newName) {
        if (index < 0 || index >= SLOT_COUNT) {
            return false;
        }
        SharedPreferences slotPrefs = context.getSharedPreferences(PREFS_SLOTS, Context.MODE_PRIVATE);
        String raw = slotPrefs.getString(SLOT_STORAGE_KEYS[index], null);
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(raw);
            String trimmed = newName == null ? "" : newName.trim();
            if (trimmed.isEmpty()) {
                root.remove("name");
            } else {
                root.put("name", trimmed);
            }
            return slotPrefs.edit().putString(SLOT_STORAGE_KEYS[index], root.toString()).commit();
        } catch (JSONException e) {
            return false;
        }
    }

    public static boolean clearSlot(@NonNull Context context, int index) {
        if (index < 0 || index >= SLOT_COUNT) {
            return false;
        }
        SharedPreferences slotPrefs = context.getSharedPreferences(PREFS_SLOTS, Context.MODE_PRIVATE);
        return slotPrefs.edit().remove(SLOT_STORAGE_KEYS[index]).commit();
    }
}
