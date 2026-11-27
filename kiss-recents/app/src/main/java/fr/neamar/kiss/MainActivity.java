package fr.neamar.kiss;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.DataSetObserver;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.result.AppResult;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.HistorySearcher;
import fr.neamar.kiss.searcher.QueryInterface;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.ui.RecentsCarouselView;
import fr.neamar.kiss.ui.WallpaperClearLayout;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.RecentsBridgeClient;
import fr.neamar.kiss.utils.SystemBars;
import fr.neamar.kiss.utils.Utilities;
import fr.neamar.kiss.utils.WallpaperBlurCache;

/**
 * The only screen: a list of recently used apps, a bar that sends the user Home when tapped,
 * a button to clear all recents and a button opening the settings.
 */
public class MainActivity extends AppCompatActivity implements QueryInterface {

    public static final String START_LOAD = "fr.neamar.summon.START_LOAD";
    public static final String LOAD_OVER = "fr.neamar.summon.LOAD_OVER";
    public static final String REFRESH_FAVORITES = "fr.neamar.summon.REFRESH_FAVORITES";

    /** Put by the LSPosed hook on the intent it opens this screen with when the recents button is pressed */
    public static final String EXTRA_RECENTS_PRESSED = "fr.neamar.kiss.RECENTS_PRESSED";
    public static final String EXTRA_HOOK_SOURCE = "fr.neamar.kiss.HOOK_SOURCE";
    /** Minimum gap between two human taps; anything faster is a duplicate hook callback. */
    private static final long DUPLICATE_BOUNCE_WINDOW_MS = 150;
    /** Cross-process duplicate window when SystemUI and One UI Home both report a single physical press. */
    private static final long CROSS_SOURCE_DEDUPE_WINDOW_MS = 250;
    /** A second press of recents within this window while Recents is open switches to the previous app. */
    private static final long DOUBLE_PRESS_WINDOW_MS = 1500;
    private static final long CACHED_TASKS_MAX_AGE_MS = 2500;

    private static final String TAG = "MainActivity";

    public RecordAdapter adapter;
    public SharedPreferences prefs;
    public RecentsCarouselView carousel;

    private WallpaperClearLayout rootLayout;
    private View listContainer;
    private View emptyListView;
    private View launcherButton;
    private View loaderSpinner;
    private ListPopup mPopup;
    private String appearanceSignature;
    private boolean started;
    private boolean lastWallpaperEnabled;
    private boolean lastDimOverlay;
    private boolean lastBlurEnabled;
    private boolean lastNativeBlur;
    private long lastRecentsPress;
    private long sessionStartUptimeMs;
    @Nullable
    private String lastHookSource;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        boolean isKissApp = getApplication() instanceof KissApplication;
        if (isKissApp) {
            prefs = PreferenceManager.getDefaultSharedPreferences(this);
            // Theme must be applied before super.onCreate() so window attributes initialize cleanly
            Appearance.applyTheme(this, prefs);
            appearanceSignature = Appearance.signature(prefs);
            UIColors.clearColorCache();
        }

        super.onCreate(savedInstanceState);

        // During a full backup restore, Android may start the process in restricted mode with a plain Application.
        if (!isKissApp) {
            Log.w(TAG, "Not running in KissApplication, process is likely in restricted mode (backup/restore)");
            finish();
            return;
        }

        disableActivityTransition();
        UIColors.updateThemePrimaryColor(this);

        KissApplication.getApplication(this).getDataHandler().setMainActivity(this);

        setContentView(R.layout.main);
        View contentView = findViewById(android.R.id.content);
        SystemBars.applyInsets(contentView, contentView);

        boolean wallpaperEnabled = Appearance.isWallpaperEnabled(prefs);
        rootLayout = findViewById(R.id.mainRoot);
        listContainer = findViewById(R.id.resultLayout);
        carousel = findViewById(R.id.recentsCarousel);
        carousel.setTransparentViewport(wallpaperEnabled);
        syncWallpaperEffects(true);
        Appearance.applyRoundedCorners(prefs, findViewById(R.id.searchEditLayout), listContainer);
        emptyListView = findViewById(android.R.id.empty);
        launcherButton = findViewById(R.id.launcherButton);
        loaderSpinner = findViewById(R.id.loaderBar);
        ImageView clearButton = findViewById(R.id.clearButton);

        boolean providersLoaded = KissApplication.getApplication(this).getDataHandler().isAllProvidersLoaded();
        displayLoader(!providersLoaded);

        adapter = new RecordAdapter(this, new ArrayList<>());
        carousel.setGreyscaleSnapshots(prefs.getBoolean("snapshot-greyscale", true));
        carousel.setCallback(new RecentsCarouselView.Callback() {
            @Override
            public boolean canDismiss(Result<?> result) {
                return result instanceof AppResult && canControlRecents();
            }

            @Override
            public boolean includeInClearAll(Result<?> result) {
                // Apps marked "Don't clear" keep running
                return result instanceof AppResult
                        && !KeepAlive.isKept(MainActivity.this, ((AppResult) result).getPackageName());
            }

            @Override
            public void onDismissed(List<Result<?>> results) {
                stopApps(results);
                adapter.removeResults(results);
            }

            @Override
            public void onLaunchSelected(Result<?> result, @Nullable View sourceView) {
                resetRecentsPressState();
                result.launch(MainActivity.this, sourceView, MainActivity.this);
            }

            @Override
            public void onKeepAliveToggled() {
                carousel.refreshAllBoundItems();
            }
        });
        carousel.setAdapter(adapter);

        // Show a message when nothing is open
        adapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                super.onChanged();
                boolean empty = adapter.isEmpty();
                listContainer.setVisibility(empty ? View.GONE : View.VISIBLE);
                emptyListView.setVisibility(empty ? View.VISIBLE : View.GONE);
                carousel.onDatasetChanged(false);
                if (rootLayout != null) {
                    rootLayout.invalidate();
                }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (carousel != null && carousel.isContextMenuOpen()) {
                    carousel.closeContextMenu();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });

        // The search bar only sends the user Home
        findViewById(R.id.searchEditLayout).setOnClickListener(v -> goHome());
        findViewById(R.id.homeBar).setOnClickListener(v -> goHome());

        // Left button: settings
        launcherButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Right button: clear all recents
        clearButton.setOnClickListener(v -> clearAllRecents());

        Intent launchIntent = getIntent();
        RecentsBridgeClient.attachFromIntent(launchIntent);
        handleRecentsPress(launchIntent, true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (sessionStartUptimeMs == 0L) {
            sessionStartUptimeMs = SystemClock.uptimeMillis();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!Appearance.signature(prefs).equals(appearanceSignature)) {
            // The appearance was imported while this screen was open: rebuild it with the new theme
            recreate();
            return;
        }

        dismissPopup();
        syncWallpaperEffects(false);
        carousel.setGreyscaleSnapshots(prefs.getBoolean("snapshot-greyscale", true));

        if (KissApplication.getApplication(this).getDataHandler().isAllProvidersLoaded()) {
            displayLoader(false);
        }

        // Open apps change while we are in the background: always rebuild the list
        updateSearchRecords();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        resetRecentsPressState();
        if (carousel != null) {
            carousel.closeContextMenu();
            carousel.resetSelection();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (prefs != null) {
            UIColors.clearColorCache();
            recreate();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        disableActivityTransition();
        setIntent(intent);
        RecentsBridgeClient.attachFromIntent(intent);
        handleRecentsPress(intent, false);
    }

    private void resetRecentsPressState() {
        lastRecentsPress = 0L;
        lastHookSource = null;
        sessionStartUptimeMs = 0L;
    }

    /**
     * Single tap opens KISS Recents; a second intentional tap within {@link #DOUBLE_PRESS_WINDOW_MS}
     * while Recents is open jumps to the previous app. Filters out duplicate callbacks fired for
     * one physical press across SystemUI and One UI Home.
     */
    private void handleRecentsPress(@Nullable Intent intent, boolean created) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_RECENTS_PRESSED, false)) {
            return;
        }
        intent.removeExtra(EXTRA_RECENTS_PRESSED);

        String hookSource = intent.getStringExtra(EXTRA_HOOK_SOURCE);
        long now = SystemClock.uptimeMillis();

        if (created || !started) {
            sessionStartUptimeMs = now;
            lastRecentsPress = now;
            lastHookSource = hookSource;
            return;
        }

        long delta = now - lastRecentsPress;
        if (delta < DUPLICATE_BOUNCE_WINDOW_MS) {
            return;
        }
        if (hookSource != null && lastHookSource != null
                && !hookSource.equals(lastHookSource)
                && delta < CROSS_SOURCE_DEDUPE_WINDOW_MS) {
            return;
        }

        boolean doublePress = delta < DOUBLE_PRESS_WINDOW_MS;
        lastRecentsPress = now;
        lastHookSource = hookSource;
        if (doublePress) {
            switchToPreviousApp();
        }
    }

    /**
     * Launch the second most recent app: the most recent one is the app that was open when recents was pressed.
     * With a single open app, that app.
     */
    private void switchToPreviousApp() {
        if (!canControlRecents()) {
            return;
        }
        List<AppPojo> cached = HistorySearcher.getRecentCachedTasks(CACHED_TASKS_MAX_AGE_MS, sessionStartUptimeMs);
        if (!cached.isEmpty()) {
            launchTask(cached.get(Math.min(1, cached.size() - 1)));
            return;
        }
        Searcher.SEARCH_THREAD.execute(() -> {
            List<AppPojo> tasks = HistorySearcher.loadRunning(this).tasks;
            if (tasks.isEmpty()) {
                return;
            }
            final AppPojo target = tasks.get(Math.min(1, tasks.size() - 1));
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    launchTask(target);
                }
            });
        });
    }

    private void launchTask(AppPojo target) {
        resetRecentsPressState();
        HistorySearcher.clearDismissed(target.packageName);
        if (target.taskId > 0) {
            HistorySearcher.clearDismissedTask(target.taskId);
        }
        Result.fromPojo(this, target).launch(this, null, this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adapter == null) {
            // onCreate() bailed out early, nothing to clean up
            return;
        }
        KissApplication.getApplication(this).getDataHandler().removeMainActivity(this);
    }

    /**
     * Receive events from DataHandler and providers, see {@link DataHandler#notifyMainActivity(String)}
     */
    public void onDataEvent(String action) {
        if (REFRESH_FAVORITES.equals(action)) {
            updateSearchRecords();
        } else if (LOAD_OVER.equals(action)) {
            updateSearchRecords();
            displayLoader(!KissApplication.getApplication(this).getDataHandler().isAllProvidersLoaded());
        } else if (START_LOAD.equals(action)) {
            displayLoader(true);
        }
    }

    private void goHome() {
        resetRecentsPressState();
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        disableActivityTransition();
    }

    private void syncWallpaperEffects(boolean forceApplyWindow) {
        boolean wallpaperEnabled = Appearance.isWallpaperEnabled(prefs);
        boolean dimOverlay = prefs.getBoolean("wallpaper-dim-overlay", true);
        boolean blurEnabled = Appearance.isBlurEnabled(prefs);
        boolean nativeBlur = blurEnabled && Appearance.isCrossWindowBlurSupported(this);

        if (forceApplyWindow
                || wallpaperEnabled != lastWallpaperEnabled
                || dimOverlay != lastDimOverlay
                || blurEnabled != lastBlurEnabled
                || nativeBlur != lastNativeBlur) {
            lastWallpaperEnabled = wallpaperEnabled;
            lastDimOverlay = dimOverlay;
            lastBlurEnabled = blurEnabled;
            lastNativeBlur = nativeBlur;
            Appearance.applyWindowWallpaper(this, prefs);
        }

        if (rootLayout != null) {
            rootLayout.setClearTransparentViewport(wallpaperEnabled);
            rootLayout.setDarkWallpaperOverlay(dimOverlay);
            rootLayout.setWallpaperBlurEnabled(wallpaperEnabled && blurEnabled, nativeBlur);
        }
        if (carousel != null) {
            carousel.setTransparentViewport(wallpaperEnabled);
        }

        if (wallpaperEnabled && blurEnabled && !nativeBlur) {
            WallpaperBlurCache.requestAsyncIfNeeded(this, () -> runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (rootLayout != null) {
                    rootLayout.invalidate();
                }
                if (getWindow() != null) {
                    getWindow().getDecorView().invalidate();
                }
            }));
        }
    }

    private void disableActivityTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        } else {
            overridePendingTransitionCompat();
        }
    }

    @SuppressWarnings("deprecation")
    private void overridePendingTransitionCompat() {
        overridePendingTransition(0, 0);
    }

    private boolean isRootActivated() {
        return KissApplication.getApplication(this).getRootHandler().isRootActivated();
    }

    private boolean canControlRecents() {
        return isRootActivated() || RecentsBridgeClient.isAvailable();
    }

    /**
     * Slide every row out, then remove their tasks / stop the apps listed.
     */
    private void clearAllRecents() {
        if (canControlRecents()) {
            carousel.dismissAll();
        }
    }

    /**
     * Remove system tasks by taskId via the Recents bridge and force-stop eligible apps in a single batch shell call.
     */
    private void stopApps(List<Result<?>> results) {
        final List<Integer> taskIds = new ArrayList<>();
        final List<String> forceStopPackages = new ArrayList<>();
        for (Result<?> result : results) {
            if (result instanceof AppResult) {
                AppPojo app = ((AppResult) result).getPojo();
                if (app.taskId > 0) {
                    taskIds.add(app.taskId);
                }
                if (!app.isTaskOnly && !AppResult.isProtectedSystemPackage(app.packageName)) {
                    forceStopPackages.add(app.packageName);
                }
            }
        }
        if (taskIds.isEmpty() && forceStopPackages.isEmpty()) {
            return;
        }
        HistorySearcher.markDismissedTasks(taskIds);
        if (!forceStopPackages.isEmpty()) {
            HistorySearcher.markDismissed(forceStopPackages);
        }
        final RootHandler rootHandler = KissApplication.getApplication(this).getRootHandler();
        final boolean rootActive = rootHandler.isRootActivated();
        Utilities.getDefaultExecutor().execute(() -> {
            for (Integer taskId : taskIds) {
                RecentsBridgeClient.removeTask(taskId);
            }
            if (rootActive && !forceStopPackages.isEmpty()) {
                rootHandler.hibernateApps(forceStopPackages);
            }
        });
    }

    public void updateSearchRecords() {
        if (carousel != null && carousel.isAnimating()) {
            // A row is sliding out and could be reused for another item: the removal updates the list anyway
            return;
        }
        SearchHandler.getInstance().search(Searcher.Type.HISTORY, this, "", false);
    }

    public void displayLoader(boolean display) {
        if (!display) {
            // Do not display animation if launcher button is already visible
            if (launcherButton.getVisibility() != View.VISIBLE) {
                launcherButton.setVisibility(View.VISIBLE);

                int animationDuration = getResources().getInteger(android.R.integer.config_longAnimTime);

                // Animate transition from loader to launch button
                launcherButton.animate()
                        .alpha(1f)
                        .setDuration(animationDuration)
                        .setListener(null);
                loaderSpinner.animate()
                        .alpha(0f)
                        .setDuration(animationDuration)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                loaderSpinner.setVisibility(View.GONE);
                            }
                        });
            }
        } else {
            launcherButton.animate().cancel();
            launcherButton.setAlpha(0);
            launcherButton.setVisibility(View.INVISIBLE);

            loaderSpinner.animate().cancel();
            loaderSpinner.setAlpha(1);
            loaderSpinner.setVisibility(View.VISIBLE);
        }
    }

    private void dismissPopup() {
        if (mPopup != null) {
            mPopup.dismiss();
            mPopup = null;
        }
    }

    /*
     * QueryInterface
     */

    @Override
    public void temporarilyDisableTranscriptMode() {
    }

    @Override
    public void updateTranscriptMode(int transcriptMode) {
    }

    @Override
    public void beforeListChange() {
    }

    @Override
    public void afterListChange() {
    }

    @Override
    public void launchOccurred() {
        resetRecentsPressState();
    }

    @Override
    public void registerPopup(ListPopup popup) {
        if (mPopup == popup) {
            return;
        }
        dismissPopup();
        mPopup = popup;
        popup.setOnDismissListener(() -> MainActivity.this.mPopup = null);
    }

    @Override
    public void showDialog(@NonNull DialogFragment dialog) {
        if (dialog instanceof CustomIconDialog) {
            // force icon reload by searching again once the dialog is closed
            ((CustomIconDialog) dialog).setOnDismissListener(dlg -> updateSearchRecords());
        }
        dialog.show(getSupportFragmentManager(), "dialog");
    }
}
