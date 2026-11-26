package fr.neamar.kiss.xposed;

import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import dalvik.system.DexFile;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Diagnostics: finds out which classes and methods handle the recents button on this ROM.
 * <p>
 * It lists the matching classes, and hooks the methods that look like "open recents" so that they only
 * write a log line with a stack trace when called. Nothing is changed or blocked here.
 */
final class RecentsDiscovery {

    private static final int MAX_LISTED_CLASSES = 150;
    private static final int CLASSES_PER_LINE = 4;
    private static final int MAX_TRACE_HOOKS = 80;
    private static final int STACK_DEPTH = 14;
    private static final long TRACE_THROTTLE_MS = 1000;

    private static final Pattern PRIORITY_CLASS = Pattern.compile("recents|overview|touchinteraction", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACED_METHOD = Pattern.compile(
            "(toggle|show|hide|start|open|launch|on|handle|send|dispatch)\\w*(recent|overview|appswitch)\\w*",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Long> LAST_TRACE = new ConcurrentHashMap<>();
    private static final Set<String> DONE = Collections.synchronizedSet(new HashSet<String>());

    private RecentsDiscovery() {
    }

    static void run(String label, ClassLoader classLoader, Pattern classPattern) {
        if (!DONE.add(label)) {
            return;
        }
        try {
            List<String> matches = new ArrayList<>();
            for (String name : listClassNames(classLoader)) {
                if (classPattern.matcher(name).find()) {
                    matches.add(name);
                }
            }
            Collections.sort(matches, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    int priorityA = PRIORITY_CLASS.matcher(a).find() ? 0 : 1;
                    int priorityB = PRIORITY_CLASS.matcher(b).find() ? 0 : 1;
                    return priorityA != priorityB ? priorityA - priorityB : a.compareTo(b);
                }
            });
            HookLog.log(label + ": " + matches.size() + " matching classes");

            StringBuilder line = new StringBuilder();
            int inLine = 0;
            for (int i = 0; i < matches.size() && i < MAX_LISTED_CLASSES; i++) {
                line.append("\n    ").append(matches.get(i));
                if (++inLine == CLASSES_PER_LINE) {
                    HookLog.log(label + " classes:" + line);
                    line.setLength(0);
                    inLine = 0;
                }
            }
            if (inLine > 0) {
                HookLog.log(label + " classes:" + line);
            }

            traceMethods(label, classLoader, matches);
        } catch (Throwable t) {
            HookLog.log(label + ": discovery failed: " + t);
        }
    }

    private static void traceMethods(String label, ClassLoader classLoader, List<String> classNames) {
        int hooked = 0;
        for (String className : classNames) {
            if (hooked >= MAX_TRACE_HOOKS) {
                break;
            }
            Class<?> clazz;
            Method[] methods;
            try {
                // false: do not run static initializers of SystemUI classes from here
                clazz = Class.forName(className, false, classLoader);
                methods = clazz.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method method : methods) {
                if (hooked >= MAX_TRACE_HOOKS) {
                    break;
                }
                if (Modifier.isAbstract(method.getModifiers()) || !TRACED_METHOD.matcher(method.getName()).matches()) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, new TraceHook(label, clazz.getName() + "." + method.getName()));
                    hooked++;
                    HookLog.log(label + ": tracing " + clazz.getName() + "." + method.getName());
                } catch (Throwable t) {
                    HookLog.log(label + ": cannot trace " + clazz.getName() + "." + method.getName() + ": " + t);
                }
            }
        }
        HookLog.log(label + ": " + hooked + " methods traced. Press the recents button now.");
    }

    /**
     * Names of all classes of a class loader. Reflection on the loader's dex list: there is no public API.
     */
    @SuppressWarnings("deprecation")
    private static List<String> listClassNames(ClassLoader classLoader) {
        List<String> names = new ArrayList<>();
        try {
            Class<?> baseLoader = Class.forName("dalvik.system.BaseDexClassLoader");
            if (!baseLoader.isInstance(classLoader)) {
                HookLog.log("class loader is not a BaseDexClassLoader: " + classLoader);
                return names;
            }
            Field pathListField = baseLoader.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);
            Field elementsField = pathList.getClass().getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(pathList);
            for (Object element : elements) {
                Field dexFileField = element.getClass().getDeclaredField("dexFile");
                dexFileField.setAccessible(true);
                DexFile dexFile = (DexFile) dexFileField.get(element);
                if (dexFile == null) {
                    continue;
                }
                Enumeration<String> entries = dexFile.entries();
                while (entries.hasMoreElements()) {
                    names.add(entries.nextElement());
                }
            }
        } catch (Throwable t) {
            HookLog.log("cannot list classes: " + t);
        }
        return names;
    }

    private static final class TraceHook extends XC_MethodHook {
        private final String label;
        private final String key;

        TraceHook(String label, String key) {
            this.label = label;
            this.key = key;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            long now = SystemClock.uptimeMillis();
            Long last = LAST_TRACE.get(key);
            if (last != null && now - last < TRACE_THROTTLE_MS) {
                return;
            }
            LAST_TRACE.put(key, now);

            StringBuilder text = new StringBuilder(label + " CALLED " + key);
            StackTraceElement[] stack = new Throwable().getStackTrace();
            for (int i = 0; i < Math.min(stack.length, STACK_DEPTH); i++) {
                text.append("\n    at ").append(stack[i]);
            }
            HookLog.log(text.toString());
        }
    }
}
