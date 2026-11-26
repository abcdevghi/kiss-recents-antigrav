package fr.neamar.kiss.xposed;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Binder IPC service hosted inside SystemUI (or One UI Home) by the Xposed hook.
 * Allows KISS Recents to query real system recent tasks, resume tasks by taskId via
 * {@code IActivityTaskManager.startActivityFromRecents}, and remove tasks via {@code removeTask}.
 */
final class RecentsBridgeService extends Binder {

    static final String DESCRIPTOR = "fr.neamar.kiss.xposed.IRecentsBridge";
    static final int TX_GET_RECENT_TASKS = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_START_TASK = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_REMOVE_TASK = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_GET_TASK_ICON = IBinder.FIRST_CALL_TRANSACTION + 3;

    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final int MAX_ICON_PX = 192;

    private static final RecentsBridgeService INSTANCE = new RecentsBridgeService();

    private static volatile boolean reflectionInitialized = false;
    private static Object activityTaskManager;
    private static Method getRecentTasksMethod;
    private static Method startActivityFromRecentsMethod;
    private static Method removeTaskMethod;
    private static Method parceledListGetListMethod;

    private static Field realActivityField;
    private static Field userIdField;
    private static Field lastActiveTimeField;
    private static Field configurationField;
    private static Field windowConfigurationField;
    private static Method getActivityTypeMethod;
    private static Method taskDescGetInMemoryIconMethod;
    private static Method taskDescGetIconMethod;

    private RecentsBridgeService() {
    }

    static RecentsBridgeService getInstance() {
        return INSTANCE;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }
        switch (code) {
            case TX_GET_RECENT_TASKS: {
                data.enforceInterface(DESCRIPTOR);
                int maxNum = data.readInt();
                int userId = data.readInt();
                handleGetRecentTasks(maxNum, userId, reply);
                return true;
            }
            case TX_START_TASK: {
                data.enforceInterface(DESCRIPTOR);
                int taskId = data.readInt();
                Bundle options = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                boolean ok = startTask(taskId, options);
                reply.writeNoException();
                reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case TX_REMOVE_TASK: {
                data.enforceInterface(DESCRIPTOR);
                int taskId = data.readInt();
                boolean ok = removeTask(taskId);
                reply.writeNoException();
                reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case TX_GET_TASK_ICON: {
                data.enforceInterface(DESCRIPTOR);
                int taskId = data.readInt();
                Bitmap icon = getTaskIcon(taskId);
                reply.writeNoException();
                if (icon != null) {
                    reply.writeInt(1);
                    icon.writeToParcel(reply, 0);
                } else {
                    reply.writeInt(0);
                }
                return true;
            }
            default:
                try {
                    return super.onTransact(code, data, reply, flags);
                } catch (Throwable t) {
                    return false;
                }
        }
    }

    private static void ensureReflection() {
        if (reflectionInitialized) {
            return;
        }
        synchronized (RecentsBridgeService.class) {
            if (reflectionInitialized) {
                return;
            }
            try {
                Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
                Method getService = atmClass.getMethod("getService");
                activityTaskManager = getService.invoke(null);
                if (activityTaskManager != null) {
                    Class<?> iatmClass = activityTaskManager.getClass();
                    getRecentTasksMethod = iatmClass.getMethod("getRecentTasks", int.class, int.class, int.class);
                    startActivityFromRecentsMethod = iatmClass.getMethod("startActivityFromRecents", int.class, Bundle.class);
                    removeTaskMethod = iatmClass.getMethod("removeTask", int.class);
                }
                Class<?> sliceClass = Class.forName("android.content.pm.ParceledListSlice");
                parceledListGetListMethod = sliceClass.getMethod("getList");

                Class<?> taskInfoClass = ActivityManager.RecentTaskInfo.class;
                realActivityField = findField(taskInfoClass, "realActivity");
                userIdField = findField(taskInfoClass, "userId");
                lastActiveTimeField = findField(taskInfoClass, "lastActiveTime");
                configurationField = findField(taskInfoClass, "configuration");
                if (configurationField != null) {
                    windowConfigurationField = findField(Configuration.class, "windowConfiguration");
                    if (windowConfigurationField != null) {
                        getActivityTypeMethod = findMethod(windowConfigurationField.getType(), "getActivityType");
                    }
                }
                Class<?> tdClass = ActivityManager.TaskDescription.class;
                taskDescGetInMemoryIconMethod = findMethod(tdClass, "getInMemoryIcon");
                taskDescGetIconMethod = findMethod(tdClass, "getIcon");
            } catch (Throwable t) {
                HookLog.log("RecentsBridgeService init failed: " + t);
            } finally {
                reflectionInitialized = true;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ActivityManager.RecentTaskInfo> queryRecentTasks(int maxNum, int userId) {
        ensureReflection();
        if (activityTaskManager == null || getRecentTasksMethod == null) {
            return null;
        }
        long token = Binder.clearCallingIdentity();
        try {
            Object slice = getRecentTasksMethod.invoke(activityTaskManager, maxNum, 0, userId);
            if (slice == null) {
                return Collections.emptyList();
            }
            Object list = parceledListGetListMethod.invoke(slice);
            if (list instanceof List<?>) {
                return (List<ActivityManager.RecentTaskInfo>) list;
            }
        } catch (Throwable t) {
            HookLog.log("getRecentTasks failed: " + t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return null;
    }

    private static void handleGetRecentTasks(int maxNum, int userId, Parcel reply) {
        List<ActivityManager.RecentTaskInfo> tasks = queryRecentTasks(maxNum, userId);
        reply.writeNoException();
        if (tasks == null) {
            reply.writeInt(-1);
            return;
        }

        // Write into a temporary buffer count first by filtering valid standard tasks
        int validCount = 0;
        for (ActivityManager.RecentTaskInfo info : tasks) {
            if (isIncludeableTask(info)) {
                validCount++;
            }
        }
        reply.writeInt(validCount);
        for (ActivityManager.RecentTaskInfo info : tasks) {
            if (!isIncludeableTask(info)) {
                continue;
            }
            ComponentName component = resolveComponent(info);
            String pkg = component != null ? component.getPackageName() : "";
            String cls = component != null ? component.getClassName() : "";
            CharSequence labelSeq = info.taskDescription != null ? info.taskDescription.getLabel() : null;
            String label = labelSeq != null ? labelSeq.toString() : "";
            boolean hasIcon = extractTaskIcon(info.taskDescription) != null;

            reply.writeInt(info.taskId);
            reply.writeInt(readIntField(userIdField, info, 0));
            reply.writeString(pkg);
            reply.writeString(cls);
            reply.writeString(label);
            reply.writeLong(readLongField(lastActiveTimeField, info, 0L));
            reply.writeInt(hasIcon ? 1 : 0);
        }
    }

    private static boolean startTask(int taskId, Bundle options) {
        ensureReflection();
        if (activityTaskManager == null || startActivityFromRecentsMethod == null || taskId <= 0) {
            return false;
        }
        long token = Binder.clearCallingIdentity();
        try {
            Object res = startActivityFromRecentsMethod.invoke(activityTaskManager, taskId, options);
            return !(res instanceof Integer) || (Integer) res >= 0;
        } catch (Throwable t) {
            HookLog.log("startActivityFromRecents(" + taskId + ") failed: " + t);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static boolean removeTask(int taskId) {
        ensureReflection();
        if (activityTaskManager == null || removeTaskMethod == null || taskId <= 0) {
            return false;
        }
        long token = Binder.clearCallingIdentity();
        try {
            Object res = removeTaskMethod.invoke(activityTaskManager, taskId);
            return res instanceof Boolean && (Boolean) res;
        } catch (Throwable t) {
            HookLog.log("removeTask(" + taskId + ") failed: " + t);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static Bitmap getTaskIcon(int taskId) {
        List<ActivityManager.RecentTaskInfo> tasks = queryRecentTasks(64, 0);
        if (tasks == null) {
            return null;
        }
        for (ActivityManager.RecentTaskInfo info : tasks) {
            if (info.taskId == taskId) {
                Bitmap bitmap = extractTaskIcon(info.taskDescription);
                if (bitmap == null) {
                    return null;
                }
                int w = bitmap.getWidth();
                int h = bitmap.getHeight();
                if (w > MAX_ICON_PX || h > MAX_ICON_PX) {
                    float scale = Math.min((float) MAX_ICON_PX / w, (float) MAX_ICON_PX / h);
                    return Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
                }
                return bitmap;
            }
        }
        return null;
    }

    private static boolean isIncludeableTask(ActivityManager.RecentTaskInfo info) {
        if (info == null || info.taskId <= 0) {
            return false;
        }
        if (info.baseIntent != null && info.baseIntent.hasCategory(Intent.CATEGORY_HOME)) {
            return false;
        }
        int activityType = readActivityType(info);
        if (activityType != 0 && activityType != ACTIVITY_TYPE_STANDARD) {
            return false;
        }
        return resolveComponent(info) != null;
    }

    private static ComponentName resolveComponent(ActivityManager.RecentTaskInfo info) {
        ComponentName real = readComponentField(realActivityField, info);
        if (real != null) {
            return real;
        }
        if (info.baseActivity != null) {
            return info.baseActivity;
        }
        if (info.topActivity != null) {
            return info.topActivity;
        }
        if (info.origActivity != null) {
            return info.origActivity;
        }
        return info.baseIntent != null ? info.baseIntent.getComponent() : null;
    }

    private static int readActivityType(ActivityManager.RecentTaskInfo info) {
        if (configurationField == null || windowConfigurationField == null || getActivityTypeMethod == null) {
            return 0;
        }
        try {
            Object config = configurationField.get(info);
            if (config == null) {
                return 0;
            }
            Object winConfig = windowConfigurationField.get(config);
            if (winConfig == null) {
                return 0;
            }
            Object res = getActivityTypeMethod.invoke(winConfig);
            return res instanceof Integer ? (Integer) res : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @SuppressWarnings("deprecation")
    private static Bitmap extractTaskIcon(ActivityManager.TaskDescription td) {
        if (td == null) {
            return null;
        }
        try {
            if (taskDescGetInMemoryIconMethod != null) {
                Object bmp = taskDescGetInMemoryIconMethod.invoke(td);
                if (bmp instanceof Bitmap) {
                    return (Bitmap) bmp;
                }
            }
            if (taskDescGetIconMethod != null) {
                Object bmp = taskDescGetIconMethod.invoke(td);
                if (bmp instanceof Bitmap) {
                    return (Bitmap) bmp;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name) {
        try {
            Method m = clazz.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static ComponentName readComponentField(Field field, Object target) {
        if (field == null) {
            return null;
        }
        try {
            Object value = field.get(target);
            return value instanceof ComponentName ? (ComponentName) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int readIntField(Field field, Object target, int fallback) {
        if (field == null) {
            return fallback;
        }
        try {
            return field.getInt(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long readLongField(Field field, Object target, long fallback) {
        if (field == null) {
            return fallback;
        }
        try {
            return field.getLong(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
