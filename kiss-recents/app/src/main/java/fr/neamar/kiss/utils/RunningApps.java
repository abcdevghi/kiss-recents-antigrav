package fr.neamar.kiss.utils;

import android.os.SystemClock;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import fr.neamar.kiss.RootHandler;

/**
 * What is running on the device, read from the Recents Binder bridge or root ({@code dumpsys} /
 * {@code ps}).
 */
public final class RunningApps {

    private static final String SEPARATOR = "@@KISS_PS@@";
    private static final long MAX_RSS_CACHE_AGE_MS = 4000L;

    /** An Android process name looks like a package name, optionally followed by ":service". */
    private static final Pattern PACKAGE_LIKE = Pattern.compile("^[A-Za-z][\\w]*(\\.[\\w]+)+$");

    private static volatile Map<String, Long> cachedRssKbByPackage = Collections.emptyMap();
    private static volatile long cachedRssUptimeMs = 0L;
    private static final AtomicBoolean RSS_REFRESH_IN_FLIGHT = new AtomicBoolean(false);

    private RunningApps() {
    }

    public static final class Snapshot {
        /** Recent tasks, most recent first */
        public final List<RecentTasks.Task> tasks;
        /** Resident memory in kB per package, all of its processes added together */
        public final Map<String, Long> rssKbByPackage;

        Snapshot(List<RecentTasks.Task> tasks, Map<String, Long> rssKbByPackage) {
            this.tasks = tasks;
            this.rssKbByPackage = rssKbByPackage;
        }

        public boolean isEmpty() {
            return tasks.isEmpty() && rssKbByPackage.isEmpty();
        }
    }

    /**
     * Fetch recent tasks (via the Xposed Recents Binder bridge when available, falling back to root
     * {@code dumpsys activity recents}) and return immediately with cached process RSS while
     * refreshing {@code ps} asynchronously when needed.
     */
    public static Snapshot fetch(RootHandler rootHandler) {
        List<RecentTasks.Task> bridgeTasks = RecentsBridgeClient.getRecentTasks(64, 0);
        if (bridgeTasks != null) {
            Map<String, Long> rssKbByPackage;
            if (rootHandler.isRootActivated()) {
                rssKbByPackage = cachedRssKbByPackage;
                refreshRssAsyncIfStale(rootHandler);
            } else {
                rssKbByPackage = Collections.emptyMap();
            }
            return new Snapshot(bridgeTasks, rssKbByPackage);
        }
        if (!rootHandler.isRootActivated()) {
            return new Snapshot(Collections.emptyList(), Collections.emptyMap());
        }
        Snapshot parsed = parse(rootHandler.runRootCommand("dumpsys activity recents; echo " + SEPARATOR + "; ps -A -o RSS,NAME"));
        if (!parsed.rssKbByPackage.isEmpty()) {
            cachedRssKbByPackage = parsed.rssKbByPackage;
            cachedRssUptimeMs = SystemClock.uptimeMillis();
        }
        return parsed;
    }

    private static void refreshRssAsyncIfStale(final RootHandler rootHandler) {
        long age = SystemClock.uptimeMillis() - cachedRssUptimeMs;
        if (cachedRssUptimeMs > 0L && age <= MAX_RSS_CACHE_AGE_MS) {
            return;
        }
        if (!RSS_REFRESH_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        Utilities.getDefaultExecutor().execute(() -> {
            try {
                Map<String, Long> fresh = parseProcesses(rootHandler.runRootCommand("ps -A -o RSS,NAME"));
                cachedRssKbByPackage = fresh;
                cachedRssUptimeMs = SystemClock.uptimeMillis();
            } finally {
                RSS_REFRESH_IN_FLIGHT.set(false);
            }
        });
    }

    public static Snapshot parse(List<String> lines) {
        int separator = lines.indexOf(SEPARATOR);
        List<String> taskLines = separator < 0 ? lines : lines.subList(0, separator);
        List<String> processLines = separator < 0 ? Collections.<String>emptyList() : lines.subList(separator + 1, lines.size());
        return new Snapshot(RecentTasks.parse(taskLines), parseProcesses(processLines));
    }

    /**
     * Parse the output of {@code ps}. The header line, when there is one, says where the RSS and NAME
     * columns are; otherwise the first number is taken as RSS and the last word as the name.
     */
    static Map<String, Long> parseProcesses(List<String> lines) {
        Map<String, Long> rssKbByPackage = new LinkedHashMap<>();
        int rssIndex = -1;
        int nameIndex = -1;
        boolean headerSeen = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length < 2) {
                continue;
            }

            if (!headerSeen) {
                int rss = indexOfIgnoreCase(tokens, "RSS");
                int name = indexOfIgnoreCase(tokens, "NAME");
                if (name < 0) {
                    name = indexOfIgnoreCase(tokens, "CMD");
                }
                if (name < 0) {
                    name = indexOfIgnoreCase(tokens, "COMMAND");
                }
                if (rss >= 0 && name >= 0) {
                    rssIndex = rss;
                    nameIndex = name;
                    headerSeen = true;
                    continue;
                }
            }

            String name = null;
            long rssKb = -1;
            if (headerSeen && tokens.length > Math.max(rssIndex, nameIndex)) {
                name = tokens[nameIndex];
                rssKb = parseNumber(tokens[rssIndex]);
            } else if (!headerSeen) {
                for (String token : tokens) {
                    long number = parseNumber(token);
                    if (number >= 0) {
                        rssKb = number;
                        break;
                    }
                }
                name = tokens[tokens.length - 1];
            }
            if (name == null || rssKb < 0) {
                continue;
            }

            // "com.example.app:service" belongs to "com.example.app"
            int colon = name.indexOf(':');
            String packageName = colon < 0 ? name : name.substring(0, colon);
            if (!PACKAGE_LIKE.matcher(packageName).matches()) {
                // Kernel threads and native daemons
                continue;
            }
            Long previous = rssKbByPackage.get(packageName);
            rssKbByPackage.put(packageName, (previous == null ? 0 : previous) + rssKb);
        }
        return rssKbByPackage;
    }

    private static int indexOfIgnoreCase(String[] tokens, String wanted) {
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].toUpperCase(Locale.ROOT).equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    private static long parseNumber(String token) {
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
