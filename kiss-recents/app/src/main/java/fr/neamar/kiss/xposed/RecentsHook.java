package fr.neamar.kiss.xposed;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import fr.neamar.kiss.BuildConfig;

/**
 * LSPosed module: the system's recents button opens KISS recents.
 * <p>
 * The recents request goes SystemUI (Recents, OverviewProxyRecentsImpl) -> launcher
 * (TouchInteractionService) -> RecentsActivity. Each step is hooked separately, as it is not known
 * which of them exist on a given ROM; every hook that attaches is written to the LSPosed log.
 * A request is only swallowed when KISS recents was started, otherwise the system's recents opens.
 * <p>
 * Scope: System UI and the launcher that hosts RecentsActivity (e.g. One UI Home).
 */
public class RecentsHook implements IXposedHookZygoteInit, IXposedHookLoadPackage {

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String[] TARGET_PACKAGES = {"claude.recents.kiss", "claude.recents.kiss.debug"};
    private static final String TARGET_ACTIVITY = "fr.neamar.kiss.MainActivity";
    /** Tells the screen that recents was pressed: a second press goes to the previous app. Same as MainActivity.EXTRA_RECENTS_PRESSED. */
    private static final String EXTRA_RECENTS_PRESSED = "fr.neamar.kiss.RECENTS_PRESSED";

    /** Set to true temporarily when diagnosing unknown ROM recents classes in debug builds. */
    private static final boolean ENABLE_DISCOVERY = false;

    private static final String[] SYSTEM_UI_CLASSES = {
            "com.android.systemui.recents.OverviewProxyRecentsImpl",
            "com.android.systemui.recents.Recents"
    };
    private static final String TOUCH_INTERACTION_SERVICE = "com.android.quickstep.TouchInteractionService";
    private static final String RECENTS_ACTIVITY = "com.android.quickstep.RecentsActivity";

    /** Diagnostics: which classes of SystemUI and of the launcher are looked at */
    private static final Pattern SYSTEM_UI_CLASS_PATTERN =
            Pattern.compile("recents|overview|navigationbar|navbar|keybutton|commandqueue", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAUNCHER_CLASS_PATTERN =
            Pattern.compile("quickstep.*(overview|recents|touchinteraction)", Pattern.CASE_INSENSITIVE);

    private final Set<String> hookedBinderClasses = new HashSet<>();

    /** Cached across presses in this process to avoid reflection and failed package probes. */
    private static volatile Context cachedAppContext;
    private static volatile ComponentName cachedTargetComponent;
    private static volatile Method currentApplicationMethod;

    @Override
    public void initZygote(StartupParam startupParam) {
        HookLog.debug("initZygote: module " + startupParam.modulePath + ", starts system server: " + startupParam.startsSystemServer);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        HookLog.log("loaded in " + lpparam.packageName + " (process " + lpparam.processName + ")");
        try {
            if (SYSTEM_UI.equals(lpparam.packageName)) {
                hookSystemUi(lpparam.classLoader);
            } else {
                hookLauncher(lpparam.packageName, lpparam.classLoader);
            }
        } catch (Throwable t) {
            HookLog.log("failed in " + lpparam.packageName + ": " + t);
        }
    }

    /**
     * Layer 1: the toggle/show request inside SystemUI. Covers the navigation bar button and the
     * hardware key, and stops the request before the launcher hears of it.
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
            HookLog.log("SystemUI: nothing hooked, the launcher hooks have to do it");
        }
        if (BuildConfig.DEBUG && ENABLE_DISCOVERY) {
            RecentsDiscovery.run("SystemUI", classLoader, SYSTEM_UI_CLASS_PATTERN);
        }
    }

    /**
     * Layers 2 and 3, only where the launcher's recents classes exist (they are ignored in any other app).
     */
    private void hookLauncher(String packageName, ClassLoader classLoader) {
        // Layer 2: the call SystemUI makes into the launcher
        Class<?> service = XposedHelpers.findClassIfExists(TOUCH_INTERACTION_SERVICE, classLoader);
        if (service != null) {
            try {
                XposedHelpers.findAndHookMethod(service, "onBind", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object binder = param.getResult();
                        if (binder != null) {
                            hookBinder(binder.getClass());
                        }
                    }
                });
                HookLog.log(packageName + ": hooked " + TOUCH_INTERACTION_SERVICE + ".onBind");
            } catch (Throwable t) {
                HookLog.log(packageName + ": cannot hook onBind: " + t);
            }
        }

        // Layer 3: the recents screen itself. It is closed as soon as it opens.
        final Class<?> recentsActivity = XposedHelpers.findClassIfExists(RECENTS_ACTIVITY, classLoader);
        if ((service != null || recentsActivity != null) && BuildConfig.DEBUG && ENABLE_DISCOVERY) {
            RecentsDiscovery.run(packageName, classLoader, LAUNCHER_CLASS_PATTERN);
        }
        if (recentsActivity != null) {
            try {
                XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!recentsActivity.isInstance(param.thisObject)) {
                            return;
                        }
                        Activity activity = (Activity) param.thisObject;
                        if (openKissRecents(activity)) {
                            activity.overridePendingTransition(0, 0);
                            activity.finish();
                            HookLog.debug(packageName + ": RecentsActivity replaced onCreate");
                        }
                    }
                });
                XposedHelpers.findAndHookMethod(Activity.class, "onNewIntent", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!recentsActivity.isInstance(param.thisObject)) {
                            return;
                        }
                        Activity activity = (Activity) param.thisObject;
                        if (openKissRecents(activity)) {
                            activity.overridePendingTransition(0, 0);
                            activity.finish();
                            HookLog.debug(packageName + ": RecentsActivity replaced onNewIntent");
                        }
                    }
                });
                HookLog.log(packageName + ": hooked " + RECENTS_ACTIVITY + " lifecycle");
            } catch (Throwable t) {
                HookLog.log(packageName + ": cannot hook RecentsActivity: " + t);
            }
        }
    }

    /**
     * The binder that SystemUI calls is only known once the service has been bound: hook its class then.
     */
    private void hookBinder(Class<?> binderClass) {
        synchronized (hookedBinderClasses) {
            if (!hookedBinderClasses.add(binderClass.getName())) {
                return;
            }
        }
        final String label = "launcher " + binderClass.getSimpleName();
        if (hookMethodIfPresent(binderClass, "onOverviewToggle", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                intercept(param, null, label + ".onOverviewToggle");
            }
        })) {
            HookLog.log("launcher: hooked " + binderClass.getName() + ".onOverviewToggle");
        } else {
            HookLog.log("launcher: cannot hook " + binderClass.getName() + ".onOverviewToggle");
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
    private static void intercept(XC_MethodHook.MethodHookParam param, Context context, String source) {
        if (openKissRecents(context)) {
            param.setResult(null);
            HookLog.debug(source + ": replaced");
        } else {
            HookLog.log(source + ": KISS recents could not be opened, leaving the system's recents");
        }
    }

    private static boolean openKissRecents(Context context) {
        Context starter = context != null ? context : currentApplication();
        if (starter == null) {
            HookLog.log("no context to start KISS recents from");
            return false;
        }

        Bundle noAnimOptions = createNoAnimOptions(starter);
        ComponentName target = cachedTargetComponent;
        if (target != null) {
            if (tryStart(starter, target, noAnimOptions)) {
                return true;
            }
            // App may have been uninstalled or switched between release and debug
            cachedTargetComponent = null;
        }

        for (String packageName : TARGET_PACKAGES) {
            ComponentName candidate = new ComponentName(packageName, TARGET_ACTIVITY);
            if (tryStart(starter, candidate, noAnimOptions)) {
                cachedTargetComponent = candidate;
                return true;
            }
        }
        return false;
    }

    private static boolean tryStart(Context starter, ComponentName component, Bundle options) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(component);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(EXTRA_RECENTS_PRESSED, true);
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
