package fr.neamar.kiss.xposed;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import fr.neamar.kiss.BuildConfig;

/**
 * LSPosed module: intercepts the 3-button recents action inside SystemUI and opens KISS recents.
 * <p>
 * Stopping the request inside SystemUI (Recents, OverviewProxyRecentsImpl) prevents it from
 * reaching any launcher's RecentsActivity. A request is only swallowed when KISS recents was
 * started; otherwise the system's recents opens.
 * <p>
 * Scope: System UI ({@code com.android.systemui}) only.
 */
public class RecentsHook implements IXposedHookZygoteInit, IXposedHookLoadPackage {

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String[] TARGET_PACKAGES = {"claude.recents.kiss", "claude.recents.kiss.debug"};
    private static final String TARGET_ACTIVITY = "fr.neamar.kiss.MainActivity";
    /** Tells the screen that recents was pressed: a second press goes to the previous/current app. Same as MainActivity.EXTRA_RECENTS_PRESSED. */
    private static final String EXTRA_RECENTS_PRESSED = "fr.neamar.kiss.RECENTS_PRESSED";
    private static final String EXTRA_OPENED_FROM_HOME = "fr.neamar.kiss.OPENED_FROM_HOME";
    private static final String EXTRA_BRIDGE_BUNDLE = "fr.neamar.kiss.RECENTS_BRIDGE_BUNDLE";
    private static final String KEY_BRIDGE_BINDER = "bridge_binder";

    /** Suppresses duplicate hook callbacks inside the same process for a single physical press. */
    private static final long SAME_PROCESS_DEBOUNCE_MS = 100;

    /** Set to true temporarily when diagnosing unknown ROM recents classes in debug builds. */
    private static final boolean ENABLE_DISCOVERY = false;

    private static final String[] SYSTEM_UI_CLASSES = {
            "com.android.systemui.recents.OverviewProxyRecentsImpl",
            "com.android.systemui.recents.Recents"
    };
    private static final String SPLIT_SCREEN_CONTROLLER = "com.android.wm.shell.splitscreen.SplitScreenController";

    /** Diagnostics: which classes of SystemUI are looked at */
    private static final Pattern SYSTEM_UI_CLASS_PATTERN =
            Pattern.compile("recents|overview|navigationbar|navbar|keybutton|commandqueue", Pattern.CASE_INSENSITIVE);

    /** Cached across presses in this process to avoid reflection and failed package probes. */
    private static volatile Context cachedAppContext;
    private static volatile ComponentName cachedTargetComponent;
    private static volatile Method currentApplicationMethod;
    private static volatile long lastLaunchUptimeMs = 0L;

    @Override
    public void initZygote(StartupParam startupParam) {
        HookLog.debug("initZygote: module " + startupParam.modulePath + ", starts system server: " + startupParam.startsSystemServer);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEM_UI.equals(lpparam.packageName)) {
            return;
        }
        HookLog.log("loaded in " + lpparam.packageName + " (process " + lpparam.processName + ")");
        try {
            hookSystemUi(lpparam.classLoader);
        } catch (Throwable t) {
            HookLog.log("failed in " + lpparam.packageName + ": " + t);
        }
    }

    /**
     * Intercept toggle/show recents requests inside SystemUI before they reach any launcher.
     */
    private void hookSystemUi(ClassLoader classLoader) {
        boolean hooked = false;
        for (String className : SYSTEM_UI_CLASSES) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz == null) {
                HookLog.debug("SystemUI: no class " + className);
                continue;
            }
            final Field contextField = findContextField(clazz);
            final String label = "SystemUI " + clazz.getSimpleName();

            if (hookMethodIfPresent(clazz, "toggleRecentApps", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Context ctx = readContextField(contextField, param.thisObject);
                    intercept(param, ctx, label + ".toggleRecentApps");
                }
            })) {
                HookLog.log("SystemUI: hooked " + className + ".toggleRecentApps");
                hooked = true;
            }

            if (hookMethodIfPresent(clazz, "showRecentApps", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Context ctx = readContextField(contextField, param.thisObject);
                    intercept(param, ctx, label + ".showRecentApps");
                }
            }, boolean.class)) {
                HookLog.log("SystemUI: hooked " + className + ".showRecentApps(boolean)");
                hooked = true;
            }
        }
        if (!hooked) {
            HookLog.log("SystemUI: nothing hooked");
        }
        hookSplitScreenController(classLoader);
        if (BuildConfig.DEBUG && ENABLE_DISCOVERY) {
            RecentsDiscovery.run("SystemUI", classLoader, SYSTEM_UI_CLASS_PATTERN);
        }
    }

    private static void hookSplitScreenController(ClassLoader classLoader) {
        Class<?> splitClass = XposedHelpers.findClassIfExists(SPLIT_SCREEN_CONTROLLER, classLoader);
        if (splitClass == null) {
            HookLog.debug("SystemUI: no class " + SPLIT_SCREEN_CONTROLLER);
            return;
        }
        try {
            for (Constructor<?> ctor : splitClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        RecentsBridgeService.setSplitScreenController(param.thisObject);
                    }
                });
            }
            HookLog.debug("SystemUI: hooked " + SPLIT_SCREEN_CONTROLLER + " constructors");
        } catch (Throwable t) {
            HookLog.log("SystemUI: cannot hook SplitScreenController: " + t);
        }
    }

    private static boolean hookMethodIfPresent(Class<?> clazz, String methodName, XC_MethodHook callback, Class<?>... parameterTypes) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                XposedBridge.hookMethod(method, callback);
                return true;
            } catch (NoSuchMethodException ignored) {
                // Keep walking superclass chain
            } catch (Throwable t) {
                HookLog.log("cannot hook " + c.getName() + "." + methodName + ": " + t);
                return false;
            }
        }
        return false;
    }

    private static Field findContextField(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Context.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }

    private static Context readContextField(Field field, Object target) {
        if (field == null || target == null) {
            return null;
        }
        try {
            Object value = field.get(target);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Open KISS recents instead of the system's recents, if it can be opened.
     */
    private static void intercept(XC_MethodHook.MethodHookParam param, Context context, String logSource) {
        if (openKissRecents(context)) {
            param.setResult(null);
            HookLog.debug(logSource + ": replaced");
        } else {
            HookLog.log(logSource + ": KISS recents could not be opened, leaving the system's recents");
        }
    }

    private static boolean openKissRecents(Context context) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunchUptimeMs < SAME_PROCESS_DEBOUNCE_MS) {
            // Another hook in this same process already launched KISS recents for this press
            return true;
        }

        Context starter = context != null ? context : currentApplication();
        if (starter == null) {
            HookLog.log("no context to start KISS recents from");
            return false;
        }
        RecentsBridgeService.setHostContext(starter);
        boolean openedFromHome = RecentsBridgeService.isHomeOnTop();

        Bundle noAnimOptions = createNoAnimOptions(starter);
        ComponentName target = cachedTargetComponent;
        if (target != null) {
            if (tryStart(starter, target, noAnimOptions, openedFromHome)) {
                lastLaunchUptimeMs = now;
                return true;
            }
            // App may have been uninstalled or switched between release and debug
            cachedTargetComponent = null;
        }

        for (String packageName : TARGET_PACKAGES) {
            ComponentName candidate = new ComponentName(packageName, TARGET_ACTIVITY);
            if (tryStart(starter, candidate, noAnimOptions, openedFromHome)) {
                cachedTargetComponent = candidate;
                lastLaunchUptimeMs = now;
                return true;
            }
        }
        return false;
    }

    private static boolean tryStart(Context starter, ComponentName component, Bundle options, boolean openedFromHome) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(component);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(EXTRA_RECENTS_PRESSED, true);
        intent.putExtra(EXTRA_OPENED_FROM_HOME, openedFromHome);

        Bundle bridgeBundle = new Bundle();
        bridgeBundle.putBinder(KEY_BRIDGE_BINDER, RecentsBridgeService.getInstance());
        intent.putExtra(EXTRA_BRIDGE_BUNDLE, bridgeBundle);

        try {
            if (options != null) {
                starter.startActivity(intent, options);
            } else {
                starter.startActivity(intent);
            }
            return true;
        } catch (ActivityNotFoundException e) {
            HookLog.debug(component.getPackageName() + " is not installed");
        } catch (Throwable t) {
            HookLog.log("cannot start " + component.getPackageName() + ": " + t);
        }
        return false;
    }

    private static Bundle createNoAnimOptions(Context starter) {
        try {
            return ActivityOptions.makeCustomAnimation(starter, 0, 0).toBundle();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context currentApplication() {
        Context cached = cachedAppContext;
        if (cached != null) {
            return cached;
        }
        try {
            Method method = currentApplicationMethod;
            if (method == null) {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                method = activityThread.getMethod("currentApplication");
                currentApplicationMethod = method;
            }
            Context app = (Context) method.invoke(null);
            if (app != null) {
                cachedAppContext = app;
            }
            return app;
        } catch (Throwable t) {
            return null;
        }
    }
}
