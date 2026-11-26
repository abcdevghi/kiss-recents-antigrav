package fr.neamar.kiss;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import fr.neamar.kiss.utils.Log;

public class RootHandler {

    private static final String TAG = RootHandler.class.getSimpleName();
    private static final Pattern VALID_PACKAGE_NAME = Pattern.compile("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$");

    private final Object shellLock = new Object();
    private final AtomicLong commandSeq = new AtomicLong();

    private Process shellProcess;
    private BufferedWriter shellInput;
    private BufferedReader shellOutput;

    private volatile Boolean isRootAvailable = null;
    private volatile boolean isRootActivated = false;

    RootHandler(Context ctx) {
        resetRootHandler(ctx);
    }

    public boolean isRootActivated() {
        return this.isRootActivated;
    }

    void resetRootHandler(Context ctx) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        isRootActivated = prefs.getBoolean("root-mode", false);
        if (!isRootActivated) {
            closeShell();
        }
    }

    public boolean isRootAvailable() {
        Boolean cached = isRootAvailable;
        if (cached == null) {
            boolean available = executeRootShell("true");
            isRootAvailable = available;
            return available;
        }
        return cached;
    }

    public boolean hibernateApp(String packageName) {
        if (!isValidPackageName(packageName)) {
            return false;
        }
        return executeRootShell("am force-stop " + packageName);
    }

    /**
     * Force-stop multiple packages in a single root shell round-trip.
     */
    public boolean hibernateApps(List<String> packageNames) {
        if (packageNames == null || packageNames.isEmpty()) {
            return true;
        }
        StringBuilder command = new StringBuilder();
        for (String packageName : packageNames) {
            if (!isValidPackageName(packageName)) {
                continue;
            }
            if (command.length() > 0) {
                command.append("; ");
            }
            command.append("am force-stop ").append(packageName);
        }
        if (command.length() == 0) {
            return false;
        }
        return executeRootShell(command.toString());
    }

    /**
     * Run a command as root using a persistent shell and return its standard output, one entry per line.
     * Returns an empty list if root is unavailable or the command fails.
     */
    public List<String> runRootCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return Collections.emptyList();
        }
        ShellResult result = execInPersistentShell(command, true);
        return result.success ? result.lines : Collections.emptyList();
    }

    private boolean executeRootShell(String command) {
        String cmd = (command == null || command.trim().isEmpty()) ? "true" : command;
        ShellResult result = execInPersistentShell(cmd, false);
        return result.success && result.exitCode == 0;
    }

    private ShellResult execInPersistentShell(String command, boolean collectOutput) {
        synchronized (shellLock) {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (!ensureShellLocked()) {
                    return ShellResult.FAILED;
                }
                long seq = commandSeq.incrementAndGet();
                String markerPrefix = "__KISS_DONE_" + seq + "__:";
                try {
                    // Discard stderr so unread error output never blocks the pipe
                    shellInput.write("(" + command + ") 2>/dev/null\n");
                    shellInput.write("echo " + markerPrefix + "$?\n");
                    shellInput.flush();

                    List<String> lines = collectOutput ? new ArrayList<>() : null;
                    String line;
                    while ((line = shellOutput.readLine()) != null) {
                        if (line.startsWith(markerPrefix)) {
                            int exitCode = parseExitCode(line.substring(markerPrefix.length()));
                            isRootAvailable = true;
                            return new ShellResult(true, exitCode, lines != null ? lines : Collections.emptyList());
                        }
                        if (lines != null) {
                            lines.add(line);
                        }
                    }
                    // EOF: su exited or was killed; reset and retry once
                    resetShellLocked();
                } catch (IOException e) {
                    Log.d(TAG, "Root shell I/O error on attempt " + attempt, e);
                    resetShellLocked();
                }
            }
            isRootAvailable = false;
            return ShellResult.FAILED;
        }
    }

    private boolean ensureShellLocked() {
        if (shellProcess != null) {
            if (isProcessAlive(shellProcess)) {
                return true;
            }
            resetShellLocked();
        }
        try {
            Process process = Runtime.getRuntime().exec("su");
            shellProcess = process;
            shellInput = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            shellOutput = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            Log.d(TAG, "Unable to start persistent root shell", e);
            resetShellLocked();
            return false;
        }
    }

    public void closeShell() {
        synchronized (shellLock) {
            resetShellLocked();
        }
    }

    private void resetShellLocked() {
        if (shellInput != null) {
            try {
                shellInput.write("exit\n");
                shellInput.flush();
            } catch (IOException ignored) {
            }
            try {
                shellInput.close();
            } catch (IOException ignored) {
            }
            shellInput = null;
        }
        if (shellOutput != null) {
            try {
                shellOutput.close();
            } catch (IOException ignored) {
            }
            shellOutput = null;
        }
        if (shellProcess != null) {
            shellProcess.destroy();
            shellProcess = null;
        }
    }

    private static boolean isProcessAlive(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    private static int parseExitCode(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static boolean isValidPackageName(String packageName) {
        return packageName != null && VALID_PACKAGE_NAME.matcher(packageName).matches();
    }

    private static final class ShellResult {
        static final ShellResult FAILED = new ShellResult(false, 1, Collections.emptyList());

        final boolean success;
        final int exitCode;
        final List<String> lines;

        ShellResult(boolean success, int exitCode, List<String> lines) {
            this.success = success;
            this.exitCode = exitCode;
            this.lines = lines;
        }
    }
}
