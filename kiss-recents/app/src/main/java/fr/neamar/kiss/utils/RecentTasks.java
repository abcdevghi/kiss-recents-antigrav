package fr.neamar.kiss.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.neamar.kiss.RootHandler;

/**
 * Reads the list of recent tasks from {@code dumpsys activity recents} (requires root)
 * and parses it into simple objects, most recent first.
 */
public final class RecentTasks {

    /** Start of one task block, e.g. "* Recent #0: Task{abe6543 #16865 type=standard A=10507:com.rk.terminal U=0}" */
    private static final Pattern TASK_HEADER = Pattern.compile("^\\s*\\* Recent #\\d+: Task\\{\\S+ #(\\d+) type=(\\w+)(?:.*\\bU=(\\d+))?");
    /** e.g. "mActivityComponent=com.rk.terminal/.ui.activities.terminal.MainActivity" */
    private static final Pattern ACTIVITY_COMPONENT = Pattern.compile("^\\s*(?:mActivityComponent|realActivity)=([^/\\s]+)/(\\S+)");
    /** Fallback for cold/persisted tasks where mActivityComponent is absent: "baseIntent={... cmp=pkg/.MainActivity ...}" */
    private static final Pattern BASE_INTENT_COMPONENT = Pattern.compile("\\bcmp=([^/\\s}]+)/([^\\s}]+)");
    /** e.g. "userId=0 effectiveUid=..." */
    private static final Pattern USER_ID = Pattern.compile("^\\s*(?:userId|mUserId)=(\\d+)");

    private RecentTasks() {
    }

    public static final class Task {
        public final int taskId;
        public final int userId;
        public final String packageName;
        public final String activityName;

        Task(int taskId, int userId, String packageName, String activityName) {
            this.taskId = taskId;
            this.userId = userId;
            this.packageName = packageName;
            this.activityName = activityName;
        }
    }

    /**
     * Run the dump as root and parse it. Returns an empty list if root is unavailable.
     */
    public static List<Task> fetch(RootHandler rootHandler) {
        return parse(rootHandler.runRootCommand("dumpsys activity recents"));
    }

    /**
     * Parse dump lines. Only tasks of type "standard" are kept, which drops the launcher's
     * own recents screen and the home task. Order is preserved (newest first).
     */
    public static List<Task> parse(List<String> lines) {
        List<Task> tasks = new ArrayList<>();

        boolean inTask = false;
        int taskId = 0;
        boolean standard = false;
        int userId = 0;
        String packageName = null;
        String activityName = null;

        for (String line : lines) {
            if (line.indexOf("* Recent #") >= 0) {
                Matcher header = TASK_HEADER.matcher(line);
                if (header.find()) {
                    if (inTask) {
                        addIfValid(tasks, standard, taskId, userId, packageName, activityName);
                    }
                    inTask = true;
                    taskId = Integer.parseInt(header.group(1));
                    standard = "standard".equals(header.group(2));
                    String headerUser = header.group(3);
                    userId = headerUser != null ? Integer.parseInt(headerUser) : 0;
                    packageName = null;
                    activityName = null;
                    continue;
                }
            }
            if (!inTask || !standard) {
                continue;
            }
            if (line.indexOf("userId=") >= 0 || line.indexOf("mUserId=") >= 0) {
                Matcher user = USER_ID.matcher(line);
                if (user.find()) {
                    userId = Integer.parseInt(user.group(1));
                    continue;
                }
            }
            if (packageName == null) {
                if (line.indexOf("mActivityComponent=") >= 0 || line.indexOf("realActivity=") >= 0) {
                    Matcher component = ACTIVITY_COMPONENT.matcher(line);
                    if (component.find()) {
                        packageName = component.group(1);
                        activityName = resolveActivityName(packageName, component.group(2));
                    }
                } else if (line.indexOf("baseIntent=") >= 0 && line.indexOf("cmp=") >= 0) {
                    Matcher baseCmp = BASE_INTENT_COMPONENT.matcher(line);
                    if (baseCmp.find()) {
                        packageName = baseCmp.group(1);
                        activityName = resolveActivityName(packageName, baseCmp.group(2));
                    }
                }
            }
        }
        if (inTask) {
            addIfValid(tasks, standard, taskId, userId, packageName, activityName);
        }
        return tasks;
    }

    private static String resolveActivityName(String packageName, String activity) {
        return activity.startsWith(".") ? packageName + activity : activity;
    }

    private static void addIfValid(List<Task> tasks, boolean standard, int taskId, int userId, String packageName, String activityName) {
        if (standard && packageName != null && activityName != null) {
            tasks.add(new Task(taskId, userId, packageName, activityName));
        }
    }
}
