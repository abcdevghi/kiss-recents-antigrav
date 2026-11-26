package fr.neamar.kiss.xposed;

import de.robv.android.xposed.XposedBridge;
import fr.neamar.kiss.BuildConfig;

/**
 * Writes to the LSPosed module log and to logcat (tag KissRecents).
 */
final class HookLog {
    private static final String TAG = "KissRecents";

    private HookLog() {
    }

    static void log(String message) {
        XposedBridge.log("[" + TAG + "] " + message);
        android.util.Log.i(TAG, message);
    }

    static void debug(String message) {
        if (BuildConfig.DEBUG) {
            log(message);
        }
    }
}
