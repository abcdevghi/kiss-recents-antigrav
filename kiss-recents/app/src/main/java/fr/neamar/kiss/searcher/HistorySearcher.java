package fr.neamar.kiss.searcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.RootHandler;
import fr.neamar.kiss.db.HistoryMode;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.RecentTasks;
import fr.neamar.kiss.utils.RecentsBridgeClient;
import fr.neamar.kiss.utils.RunningApps;
import fr.neamar.kiss.utils.UserHandle;

/**
 * Builds the list of the main screen. When the Recents Binder bridge or root mode is on: the apps
 * open in the system's task list (most recent first), then every other running app, biggest memory
 * use first. Otherwise, or when the system gives nothing, it falls back to the launch history.
 */
public class HistorySearcher extends Searcher {
    /** Relevance of open tasks, above running apps. A higher relevance is displayed nearer the bar. */
    private static final int TASKS_RELEVANCE_BASE = 200000;
    /** Relevance of the other running apps, above any history entry */
    private static final int RUNNING_RELEVANCE_BASE = 100000;
    private static final int MAX_RESULTS = 300;

    private static volatile String cachedHomePackage;
    private static volatile boolean homePackageResolved = false;

    /**
     * Packages recently dismissed/force-stopped by the user. Android may retain dead Task entries
     * in {@code dumpsys activity recents} after {@code am force-stop}; suppress those ghosts until
     * the package runs again.
     */
    private static final Set<String> dismissedPackages = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> dismissedTaskIds = ConcurrentHashMap.newKeySet();
    private static final Object RUNNING_LOCK = new Object();

    private static volatile List<AppPojo> lastTasks = Collections.emptyList();
    private static volatile long lastFetchUptimeMs = 0L;

    public HistorySearcher(MainActivity activity, boolean isRefresh) {
        super(activity, null, isRefresh);
    }

    public static void markDismissed(Collection<String> packages) {
        if (packages != null) {
            dismissedPackages.addAll(packages);
        }
    }

    public static void markDismissedTasks(Collection<Integer> taskIds) {
        if (taskIds != null) {
            dismissedTaskIds.addAll(taskIds);
        }
    }

    public static void clearDismissed(String packageName) {
        if (packageName != null) {
            dismissedPackages.remove(packageName);
        }
    }

    public static void clearDismissedTask(int taskId) {
        if (taskId > 0) {
            dismissedTaskIds.remove(taskId);
        }
    }

    public static List<AppPojo> getRecentCachedTasks(long maxAgeMs) {
        return getRecentCachedTasks(maxAgeMs, 0L);
    }

    public static List<AppPojo> getRecentCachedTasks(long maxAgeMs, long minUptimeMs) {
        long fetchTime = lastFetchUptimeMs;
        if (fetchTime >= minUptimeMs && SystemClock.uptimeMillis() - fetchTime <= maxAgeMs) {
            return lastTasks;
        }
        return Collections.emptyList();
    }

    @Override
    protected int getMaxResultCount() {
        return MAX_RESULTS;
    }

    @Override
    protected Void doInBackground(Void... voids) {
        MainActivity activity = activityWeakReference.get();
        if (activity == null)
            return null;

        DataHandler dataHandler = KissApplication.getApplication(activity).getDataHandler();
        List<AppPojo> apps = dataHandler.getApplications();
        Set<String> excludedPojoById = getExcludedIds(activity, dataHandler, apps);

        RunningList running = loadRunning(activity, apps, excludedPojoById);
        if (!running.isEmpty()) {
            List<AppPojo> all = new ArrayList<>(running.tasks.size() + running.others.size());
            all.addAll(running.tasks);
            all.addAll(running.others);
            this.addResults(all);
            return null;
        }

        // Root is off or unavailable: show what was launched recently
        List<Pojo> history = dataHandler.getHistory(activity, getMaxResultCount(), excludedPojoById);
        this.addResults(history);
        return null;
    }

    /**
     * The apps that are open now. Empty if root mode is off or the system gave nothing.
     */
    public static final class RunningList {
        /** Open tasks, most recent first */
        public final List<AppPojo> tasks = new ArrayList<>();
        /** Other running apps, biggest memory use first */
        public final List<AppPojo> others = new ArrayList<>();

        public boolean isEmpty() {
            return tasks.isEmpty() && others.isEmpty();
        }
    }

    /**
     * Same as the main screen would show right now, read from the system. Blocks: do not call it on the main thread.
     */
    public static RunningList loadRunning(MainActivity activity) {
        DataHandler dataHandler = KissApplication.getApplication(activity).getDataHandler();
        List<AppPojo> apps = dataHandler.getApplications();
        return loadRunning(activity, apps, getExcludedIds(activity, dataHandler, apps));
    }

    private static Set<String> getExcludedIds(MainActivity activity, DataHandler dataHandler, @Nullable List<AppPojo> apps) {
        // Gather excluded
        Set<String> excludedPojoById = new HashSet<>(dataHandler.getExcludedFromHistory());

        // Excluded apps are hidden here too, unless they are shown in search
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (apps != null && !prefs.getBoolean("enable-excluded-apps", false)) {
            for (AppPojo app : apps) {
                if (app.isExcluded()) {
                    excludedPojoById.add(app.id);
                }
            }
        }
        return excludedPojoById;
    }

    private static RunningList loadRunning(MainActivity activity, @Nullable List<AppPojo> apps, Set<String> excludedPojoById) {
        synchronized (RUNNING_LOCK) {
            RunningList result = new RunningList();
            RootHandler rootHandler = KissApplication.getApplication(activity).getRootHandler();
            boolean bridgeAvailable = RecentsBridgeClient.isAvailable();
            boolean rootActivated = rootHandler.isRootActivated();
            if (apps == null || (!bridgeAvailable && !rootActivated)) {
                return result;
            }

            RunningApps.Snapshot snapshot = RunningApps.fetch(rootHandler);
            if (snapshot.isEmpty()) {
                return result;
            }

            // Build O(1) lookup by package name and reset previous task/memory figures
            Map<String, List<AppPojo>> appsByPackage = new HashMap<>(apps.size());
            for (AppPojo app : apps) {
                app.rssKb = 0;
                app.taskId = -1;
                app.hasTaskIcon = false;
                if (app.getUserHandle().isCurrentUser()) {
                    List<AppPojo> list = appsByPackage.get(app.packageName);
                    if (list == null) {
                        list = new ArrayList<>(2);
                        appsByPackage.put(app.packageName, list);
                    }
                    list.add(app);
                }
            }

            // This app, and the home screen app, are not worth switching to
            Set<String> hidden = new HashSet<>(4);
            hidden.add(activity.getPackageName());
            String homePackage = getHomePackage(activity);
            if (homePackage != null) {
                hidden.add(homePackage);
            }

            // Any dismissed app that now has a live process was launched again externally
            if (!dismissedPackages.isEmpty() && !snapshot.rssKbByPackage.isEmpty()) {
                dismissedPackages.removeIf(pkg -> snapshot.rssKbByPackage.containsKey(pkg));
            }
            // Drop dismissed taskIds once the system no longer reports them
            if (!dismissedTaskIds.isEmpty()) {
                Set<Integer> liveTaskIds = new HashSet<>(snapshot.tasks.size());
                for (RecentTasks.Task task : snapshot.tasks) {
                    if (task.taskId > 0) {
                        liveTaskIds.add(task.taskId);
                    }
                }
                dismissedTaskIds.retainAll(liveTaskIds);
            }

            // 1. Open tasks, most recent first
            Set<String> taskPackages = new HashSet<>();
            Set<String> seenPojoIds = new HashSet<>();
            int relevance = TASKS_RELEVANCE_BASE;
            for (RecentTasks.Task task : snapshot.tasks) {
                if (task.userId != 0 || hidden.contains(task.packageName)) {
                    // Tasks of other users (e.g. work profile) are not supported yet
                    continue;
                }
                if (task.taskId > 0 && dismissedTaskIds.contains(task.taskId)) {
                    continue;
                }

                AppPojo launcherMatch = findApp(appsByPackage, task.packageName, task.activityName);
                boolean isParasiticOrDifferentActivity = launcherMatch != null
                        && task.label != null
                        && !task.label.isEmpty()
                        && !task.activityName.equals(launcherMatch.activityName)
                        && !task.label.equalsIgnoreCase(launcherMatch.getName());

                AppPojo entry;
                if (launcherMatch == null || isParasiticOrDifferentActivity) {
                    // Non-launcher root apps (e.g. standalone stubs) or parasitic hosts (e.g. LSPosed inside Shell/Settings)
                    if (!isParasiticOrDifferentActivity && !bridgeAvailable && dismissedPackages.contains(task.packageName)) {
                        continue;
                    }
                    entry = createTaskPojo(activity, task);
                } else {
                    if (dismissedPackages.contains(task.packageName)) {
                        continue;
                    }
                    entry = launcherMatch;
                }

                if (excludedPojoById.contains(entry.id) || !seenPojoIds.add(entry.id)) {
                    continue;
                }
                entry.taskId = task.taskId;
                entry.hasTaskIcon = task.hasTaskIcon;
                entry.rssKb = getRssKb(snapshot.rssKbByPackage, task.packageName);
                entry.relevance = relevance--;
                result.tasks.add(entry);
                taskPackages.add(task.packageName);
            }

            // 2. Other running apps, biggest first
            Set<AppPojo> seenOthers = new HashSet<>();
            for (Map.Entry<String, Long> process : snapshot.rssKbByPackage.entrySet()) {
                String packageName = process.getKey();
                if (hidden.contains(packageName) || taskPackages.contains(packageName)) {
                    continue;
                }
                AppPojo match = findApp(appsByPackage, packageName, null);
                if (match == null || excludedPojoById.contains(match.id) || !seenOthers.add(match)) {
                    continue;
                }
                match.rssKb = process.getValue();
                result.others.add(match);
            }
            Collections.sort(result.others, new Comparator<AppPojo>() {
                @Override
                public int compare(AppPojo a, AppPojo b) {
                    return Long.compare(b.rssKb, a.rssKb);
                }
            });
            relevance = RUNNING_RELEVANCE_BASE;
            for (AppPojo app : result.others) {
                app.relevance = relevance--;
            }

            lastTasks = Collections.unmodifiableList(new ArrayList<>(result.tasks));
            lastFetchUptimeMs = SystemClock.uptimeMillis();
            return result;
        }
    }

    private static AppPojo createTaskPojo(MainActivity activity, RecentTasks.Task task) {
        String id = "task://" + task.taskId + "/" + task.packageName + "/" + task.activityName;
        String label = (task.label != null && !task.label.isEmpty()) ? task.label : null;
        if (label == null) {
            label = PackageManagerUtils.getLabel(activity, new ComponentName(task.packageName, task.activityName), UserHandle.OWNER);
        }
        if (label == null) {
            label = PackageManagerUtils.getLabel(activity, task.packageName, UserHandle.OWNER);
        }
        if (label == null || label.isEmpty()) {
            label = task.packageName;
        }
        AppPojo pojo = new AppPojo(id, task.packageName, task.activityName, UserHandle.OWNER, false, true);
        pojo.setName(label);
        return pojo;
    }

    private static long getRssKb(Map<String, Long> rssKbByPackage, String packageName) {
        Long rssKb = rssKbByPackage.get(packageName);
        return rssKb == null ? 0 : rssKb;
    }

    /**
     * The app that is the home screen, which is always running
     */
    @Nullable
    private static String getHomePackage(MainActivity activity) {
        if (homePackageResolved) {
            return cachedHomePackage;
        }
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = activity.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        String resolved = info == null || info.activityInfo == null ? null : info.activityInfo.packageName;
        if (resolved != null && !"android".equals(resolved)) {
            cachedHomePackage = resolved;
            homePackageResolved = true;
        }
        return resolved;
    }

    /**
     * Prefer the exact launcher activity when one is given, otherwise any launcher entry of the package.
     */
    @Nullable
    private static AppPojo findApp(Map<String, List<AppPojo>> appsByPackage, String packageName, @Nullable String activityName) {
        List<AppPojo> candidates = appsByPackage.get(packageName);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (activityName != null) {
            for (AppPojo app : candidates) {
                if (app.activityName.equals(activityName)) {
                    return app;
                }
            }
        }
        return candidates.get(0);
    }

    @Override
    public boolean addResults(List<? extends Pojo> pojos) {
        MainActivity activity = activityWeakReference.get();
        if (activity == null) {
            return false;
        }

        DataHandler dataHandler = KissApplication.getApplication(activity).getDataHandler();
        if (dataHandler.getHistoryMode() != HistoryMode.ALPHABETICALLY) {
            for (Pojo pojo : pojos) {
                if (pojo.isDisabled()) {
                    // Give penalty for disabled items, these should not be preferred
                    pojo.relevance -= 200;
                }
            }
        }

        return super.addResults(pojos);
    }
}
