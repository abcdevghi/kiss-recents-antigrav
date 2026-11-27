package fr.neamar.kiss.xposed;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

import java.io.ByteArrayOutputStream;
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
    static final int TX_START_TASK_POPUP = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int TX_START_TASK_SPLIT = IBinder.FIRST_CALL_TRANSACTION + 5;
    static final int TX_GET_TASK_SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION + 6;
    static final int TX_GET_WALLPAPER_BITMAP = IBinder.FIRST_CALL_TRANSACTION + 7;

    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int WINDOWING_MODE_MULTI_WINDOW = 6;
    private static final int MAX_ICON_PX = 256;
    private static final int MAX_SNAPSHOT_PX = 1440;
    private static final int MAX_SNAPSHOT_BINDER_BYTES = 480 * 1024;
    private static final int MAX_WALLPAPER_PX = 540;

    private static final RecentsBridgeService INSTANCE = new RecentsBridgeService();

    private static volatile boolean reflectionInitialized = false;
    private static volatile Object splitScreenController;
    private static volatile Context hostContext;
    private static Object activityTaskManager;
    private static Method getRecentTasksMethod;
    private static Method startActivityFromRecentsMethod;
    private static Method removeTaskMethod;
    private static Method getTaskSnapshotMethod;
    private static Method takeTaskSnapshotMethod;
    private static Method setTaskWindowingModeMethod;
    private static Method resizeTaskMethod;
    private static Method setLaunchWindowingModeMethod;
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

    static void setSplitScreenController(Object controller) {
        if (controller != null) {
            splitScreenController = controller;
        }
    }

    static void setHostContext(Context context) {
        if (context != null) {
            Context app = context.getApplicationContext();
            hostContext = app != null ? app : context;
        }
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
            case TX_START_TASK_POPUP: {
                data.enforceInterface(DESCRIPTOR);
                int taskId = data.readInt();
                Rect bounds = data.readInt() != 0 ? Rect.CREATOR.createFromParcel(data) : null;
                Bundle options = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                boolean ok = startTaskPopup(taskId, bounds, options);
                reply.writeNoException();
                reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case TX_START_TASK_SPLIT: {
                data.enforceInterface(DESCRIPTOR);
                int taskId = data.readInt();
                Bundle options = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                boolean ok = startTaskSplit(taskId, options);
                reply.writeNoException();
                reply.writeInt(ok ? 1 : 0);
                return true;
            }
            case TX_GET_TASK_SNAPSHOT: {
                data.enforceInterface(DESCRIPTOR);
                int taskId = data.readInt();
                byte[] jpeg = getTaskSnapshotBytes(taskId);
                reply.writeNoException();
                reply.writeByteArray(jpeg);
                return true;
            }
            case TX_GET_WALLPAPER_BITMAP: {
                data.enforceInterface(DESCRIPTOR);
                byte[] jpeg = getWallpaperBitmapBytes();
                reply.writeNoException();
                reply.writeByteArray(jpeg);
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
                    getTaskSnapshotMethod = findMethod(iatmClass, "getTaskSnapshot", int.class, boolean.class, boolean.class);
                    if (getTaskSnapshotMethod == null) {
                        getTaskSnapshotMethod = findMethod(iatmClass, "getTaskSnapshot", int.class, boolean.class);
                    }
                    takeTaskSnapshotMethod = findMethod(iatmClass, "takeTaskSnapshot", int.class, boolean.class);
                    if (takeTaskSnapshotMethod == null) {
                        takeTaskSnapshotMethod = findMethod(iatmClass, "takeTaskSnapshot", int.class);
                    }
                    setTaskWindowingModeMethod = findMethod(iatmClass, "setTaskWindowingMode", int.class, int.class, boolean.class);
                    resizeTaskMethod = findMethod(iatmClass, "resizeTask", int.class, Rect.class, int.class);
                }
                setLaunchWindowingModeMethod = findMethod(ActivityOptions.class, "setLaunchWindowingMode", int.class);
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

    private static boolean startTaskPopup(int taskId, Rect bounds, Bundle baseOptions) {
        ensureReflection();
        if (activityTaskManager == null || startActivityFromRecentsMethod == null || taskId <= 0) {
            return false;
        }
        Bundle mergedOptions = mergeWindowingOptions(baseOptions, WINDOWING_MODE_FREEFORM, bounds);
        long token = Binder.clearCallingIdentity();
        try {
            if (setTaskWindowingModeMethod != null) {
                try {
                    setTaskWindowingModeMethod.invoke(activityTaskManager, taskId, WINDOWING_MODE_FREEFORM, true);
                } catch (Throwable ignored) {
                }
            }
            if (resizeTaskMethod != null && bounds != null) {
                try {
                    resizeTaskMethod.invoke(activityTaskManager, taskId, bounds, 0);
                } catch (Throwable ignored) {
                }
            }
            Object res = startActivityFromRecentsMethod.invoke(activityTaskManager, taskId, mergedOptions);
            boolean started = !(res instanceof Integer) || (Integer) res >= 0;
            if (started && resizeTaskMethod != null && bounds != null) {
                try {
                    resizeTaskMethod.invoke(activityTaskManager, taskId, bounds, 0);
                } catch (Throwable ignored) {
                }
            }
            return started;
        } catch (Throwable t) {
            HookLog.log("startTaskPopup(" + taskId + ") failed: " + t);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static boolean startTaskSplit(int taskId, Bundle baseOptions) {
        ensureReflection();
        if (taskId <= 0) {
            return false;
        }
        Object controller = splitScreenController;
        if (controller != null && invokeSplitControllerStartTask(controller, taskId, baseOptions)) {
            return true;
        }
        if (activityTaskManager == null || startActivityFromRecentsMethod == null) {
            return false;
        }
        Bundle mergedOptions = mergeWindowingOptions(baseOptions, WINDOWING_MODE_MULTI_WINDOW, null);
        long token = Binder.clearCallingIdentity();
        try {
            if (setTaskWindowingModeMethod != null) {
                try {
                    setTaskWindowingModeMethod.invoke(activityTaskManager, taskId, WINDOWING_MODE_MULTI_WINDOW, true);
                } catch (Throwable ignored) {
                }
            }
            Object res = startActivityFromRecentsMethod.invoke(activityTaskManager, taskId, mergedOptions);
            return !(res instanceof Integer) || (Integer) res >= 0;
        } catch (Throwable t) {
            HookLog.log("startTaskSplit(" + taskId + ") failed: " + t);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static boolean invokeSplitControllerStartTask(Object controller, int taskId, Bundle options) {
        long token = Binder.clearCallingIdentity();
        try {
            for (Method method : controller.getClass().getMethods()) {
                if (!"startTask".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 3 && params[0] == int.class && params[1] == int.class && params[2] == Bundle.class) {
                    method.invoke(controller, taskId, 0, options);
                    return true;
                }
                if (params.length == 4 && params[0] == int.class && params[1] == int.class && params[2] == Bundle.class) {
                    Object fourth = params[3] == int.class ? Integer.valueOf(0) : null;
                    method.invoke(controller, taskId, 0, options, fourth);
                    return true;
                }
            }
        } catch (Throwable t) {
            HookLog.log("SplitScreenController.startTask(" + taskId + ") failed: " + t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return false;
    }

    private static Bundle mergeWindowingOptions(Bundle baseOptions, int windowingMode, Rect bounds) {
        Bundle merged = baseOptions != null ? new Bundle(baseOptions) : new Bundle();
        try {
            ActivityOptions ao = ActivityOptions.makeBasic();
            if (setLaunchWindowingModeMethod != null) {
                setLaunchWindowingModeMethod.invoke(ao, windowingMode);
            }
            if (bounds != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ao.setLaunchBounds(bounds);
            }
            Bundle modeBundle = ao.toBundle();
            if (modeBundle != null) {
                merged.putAll(modeBundle);
            }
        } catch (Throwable ignored) {
        }
        return merged;
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

    private static byte[] getTaskSnapshotBytes(int taskId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || taskId <= 0) {
            return null;
        }
        ensureReflection();
        if (activityTaskManager == null) {
            return null;
        }
        Object snapshot = null;
        long token = Binder.clearCallingIdentity();
        try {
            if (getTaskSnapshotMethod != null) {
                if (getTaskSnapshotMethod.getParameterCount() == 3) {
                    snapshot = getTaskSnapshotMethod.invoke(activityTaskManager, taskId, false, true);
                } else {
                    snapshot = getTaskSnapshotMethod.invoke(activityTaskManager, taskId, false);
                }
            }
            if (snapshot == null && takeTaskSnapshotMethod != null) {
                if (takeTaskSnapshotMethod.getParameterCount() == 2) {
                    snapshot = takeTaskSnapshotMethod.invoke(activityTaskManager, taskId, true);
                } else {
                    snapshot = takeTaskSnapshotMethod.invoke(activityTaskManager, taskId);
                }
            }
        } catch (Throwable t) {
            HookLog.debug("getTaskSnapshot(" + taskId + ") failed: " + t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (snapshot == null) {
            return null;
        }

        HardwareBuffer hardwareBuffer = null;
        Bitmap hwBitmap = null;
        Bitmap swBitmap = null;
        Bitmap scaledBitmap = null;
        try {
            Method getHwBuffer = snapshot.getClass().getMethod("getHardwareBuffer");
            Object hbObj = getHwBuffer.invoke(snapshot);
            if (!(hbObj instanceof HardwareBuffer)) {
                return null;
            }
            hardwareBuffer = (HardwareBuffer) hbObj;
            ColorSpace colorSpace = null;
            try {
                Method getCs = snapshot.getClass().getMethod("getColorSpace");
                Object csObj = getCs.invoke(snapshot);
                if (csObj instanceof ColorSpace) {
                    colorSpace = (ColorSpace) csObj;
                }
            } catch (Throwable ignored) {
            }
            hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace);
            if (hwBitmap == null) {
                return null;
            }
            swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (swBitmap == null) {
                return null;
            }
            Bitmap outBitmap = swBitmap;
            int w = swBitmap.getWidth();
            int h = swBitmap.getHeight();
            if (w > MAX_SNAPSHOT_PX || h > MAX_SNAPSHOT_PX) {
                float scale = Math.min((float) MAX_SNAPSHOT_PX / w, (float) MAX_SNAPSHOT_PX / h);
                scaledBitmap = Bitmap.createScaledBitmap(
                        swBitmap,
                        Math.max(1, Math.round(w * scale)),
                        Math.max(1, Math.round(h * scale)),
                        true);
                outBitmap = scaledBitmap;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            outBitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
            if (baos.size() > MAX_SNAPSHOT_BINDER_BYTES) {
                baos.reset();
                outBitmap.compress(Bitmap.CompressFormat.JPEG, 78, baos);
            }
            return baos.toByteArray();
        } catch (Throwable t) {
            HookLog.debug("TaskSnapshot decode failed for task " + taskId + ": " + t);
            return null;
        } finally {
            if (scaledBitmap != null && scaledBitmap != swBitmap && !scaledBitmap.isRecycled()) {
                scaledBitmap.recycle();
            }
            if (swBitmap != null && !swBitmap.isRecycled()) {
                swBitmap.recycle();
            }
            if (hwBitmap != null && !hwBitmap.isRecycled()) {
                hwBitmap.recycle();
            }
            if (hardwareBuffer != null) {
                try {
                    hardwareBuffer.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static byte[] getWallpaperBitmapBytes() {
        Context ctx = resolveHostContext();
        if (ctx == null) {
            return null;
        }
        long token = Binder.clearCallingIdentity();
        Bitmap rendered = null;
        Bitmap scaled = null;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            if (wm == null) {
                return null;
            }
            Drawable drawable = wm.getDrawable();
            if (drawable == null) {
                return null;
            }
            Bitmap outBitmap;
            if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null) {
                outBitmap = ((BitmapDrawable) drawable).getBitmap();
            } else {
                int dw = drawable.getIntrinsicWidth() > 1 ? drawable.getIntrinsicWidth() : MAX_WALLPAPER_PX;
                int dh = drawable.getIntrinsicHeight() > 1 ? drawable.getIntrinsicHeight() : MAX_WALLPAPER_PX;
                float scale = Math.min(1f, (float) MAX_WALLPAPER_PX / Math.max(dw, dh));
                int tw = Math.max(16, Math.round(dw * scale));
                int th = Math.max(16, Math.round(dh * scale));
                rendered = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(rendered);
                drawable.setBounds(0, 0, tw, th);
                drawable.draw(canvas);
                outBitmap = rendered;
            }
            int w = outBitmap.getWidth();
            int h = outBitmap.getHeight();
            if (w > MAX_WALLPAPER_PX || h > MAX_WALLPAPER_PX) {
                float scale = Math.min((float) MAX_WALLPAPER_PX / w, (float) MAX_WALLPAPER_PX / h);
                scaled = Bitmap.createScaledBitmap(
                        outBitmap,
                        Math.max(16, Math.round(w * scale)),
                        Math.max(16, Math.round(h * scale)),
                        true);
                outBitmap = scaled;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32 * 1024);
            outBitmap.compress(Bitmap.CompressFormat.JPEG, 86, baos);
            return baos.toByteArray();
        } catch (Throwable t) {
            HookLog.debug("getWallpaperBitmap failed: " + t);
            return null;
        } finally {
            if (scaled != null && !scaled.isRecycled()) {
                scaled.recycle();
            }
            if (rendered != null && !rendered.isRecycled()) {
                rendered.recycle();
            }
            Binder.restoreCallingIdentity(token);
        }
    }

    private static Context resolveHostContext() {
        Context cached = hostContext;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentApp = atClass.getMethod("currentApplication");
            Object app = currentApp.invoke(null);
            if (app instanceof Context) {
                hostContext = (Context) app;
                return hostContext;
            }
        } catch (Throwable ignored) {
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

    private static Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        try {
            Method m = clazz.getMethod(name, parameterTypes);
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
