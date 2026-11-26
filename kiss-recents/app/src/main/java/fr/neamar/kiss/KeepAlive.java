package fr.neamar.kiss;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Apps marked "Don't clear": Clear all leaves them running. Swiping one of them away still stops it.
 */
public final class KeepAlive {

    private static final String KEY = "keep-alive-apps";
    private static volatile Set<String> cachedKeptPackages;

    private KeepAlive() {
    }

    public static boolean isKept(Context context, String packageName) {
        return getCached(context).contains(packageName);
    }

    public static void setKept(Context context, String packageName, boolean kept) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        // The set returned by SharedPreferences must not be modified: work on a copy
        Set<String> packages = new HashSet<>(getCached(context));
        if (kept) {
            packages.add(packageName);
        } else {
            packages.remove(packageName);
        }
        cachedKeptPackages = Collections.unmodifiableSet(packages);
        prefs.edit().putStringSet(KEY, packages).apply();
    }

    private static Set<String> getCached(Context context) {
        Set<String> cached = cachedKeptPackages;
        if (cached == null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            Set<String> stored = prefs.getStringSet(KEY, Collections.<String>emptySet());
            cached = Collections.unmodifiableSet(new HashSet<>(stored));
            cachedKeptPackages = cached;
        }
        return cached;
    }
}
