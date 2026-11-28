package fr.neamar.kiss;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.utils.Utilities;

/**
 * Apps marked "Don't clear": Clear all leaves them running, and when root mode is active,
 * system RAM optimization protections (Doze allowlist, active standby bucket, background appops,
 * and low oom_score_adj) are enforced to resist background RAM sweeps.
 */
public final class KeepAlive {

    private static final String KEY = "keep-alive-apps";
    private static final long REINFORCE_INTERVAL_MS = 10_000L;

    private static volatile Set<String> cachedKeptPackages;
    private static volatile long lastReinforceUptimeMs = 0L;

    private KeepAlive() {
    }

    public static boolean isKept(Context context, String packageName) {
        return getCached(context).contains(packageName);
    }

    public static void setKept(Context context, String packageName, boolean kept) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        Set<String> packages;
        synchronized (KeepAlive.class) {
            // The set returned by SharedPreferences must not be modified: work on a copy
            packages = new HashSet<>(getCached(context));
            if (kept) {
                packages.add(packageName);
            } else {
                packages.remove(packageName);
            }
            cachedKeptPackages = Collections.unmodifiableSet(packages);
        }
        prefs.edit().putStringSet(KEY, packages).apply();

        final Context appContext = context.getApplicationContext();
        final RootHandler rootHandler = KissApplication.getApplication(appContext).getRootHandler();
        if (rootHandler.isRootActivated()) {
            Utilities.getDefaultExecutor().execute(() -> {
                if (kept) {
                    rootHandler.protectKeepAlivePackage(packageName);
                } else {
                    rootHandler.unprotectKeepAlivePackage(packageName);
                }
            });
        }
    }

    /**
     * Re-pin {@code oom_score_adj} for kept apps that are currently running so Android's
     * OomAdjuster cannot leave them queued for LMKD sweeping.
     */
    public static void reinforceRunning(Context context, Collection<String> runningPackages) {
        if (runningPackages == null || runningPackages.isEmpty()) {
            return;
        }
        Set<String> kept = getCached(context);
        if (kept.isEmpty()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastReinforceUptimeMs < REINFORCE_INTERVAL_MS) {
            return;
        }
        final List<String> targets = new ArrayList<>();
        for (String pkg : runningPackages) {
            if (kept.contains(pkg)) {
                targets.add(pkg);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        final RootHandler rootHandler = KissApplication.getApplication(context).getRootHandler();
        if (!rootHandler.isRootActivated()) {
            return;
        }
        lastReinforceUptimeMs = now;
        Utilities.getDefaultExecutor().execute(() -> rootHandler.reinforceKeepAliveOom(targets));
    }

    private static Set<String> getCached(Context context) {
        Set<String> cached = cachedKeptPackages;
        if (cached == null) {
            synchronized (KeepAlive.class) {
                cached = cachedKeptPackages;
                if (cached == null) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
                    Set<String> stored = prefs.getStringSet(KEY, Collections.<String>emptySet());
                    cached = Collections.unmodifiableSet(new HashSet<>(stored));
                    cachedKeptPackages = cached;
                }
            }
        }
        return cached;
    }
}
