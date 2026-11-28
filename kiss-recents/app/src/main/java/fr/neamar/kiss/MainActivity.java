package fr.neamar.kiss;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.DataSetObserver;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.window.SplashScreenView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
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
import fr.neamar.kiss.settings.ConfigSlots;
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
    public static final String EXTRA_OPENED_FROM_HOME = "fr.neamar.kiss.OPENED_FROM_HOME";
    /** Minimum gap between two human taps; anything faster is a duplicate hook callback. */
    private static final long DUPLICATE_BOUNCE_WINDOW_MS = 150;
    /** A second press of recents within this window while Recents is open switches to the previous app. */
    private static final long DOUBLE_PRESS_WINDOW_MS = 1500;
    private static final long CACHED_TASKS_MAX_AGE_MS = 2500;

    private static final String TAG = "MainActivity";

    public RecordAdapter adapter;
    public SharedPreferences prefs;
    public RecentsCarouselView carousel;

    private WallpaperClearLayout rootLayout;
    private View listContainer;
    private View searchBarView;
    private TextView homeBarView;
    private View emptyListView;
    private View launcherButton;
    private View loaderSpinner;
    private ListPopup mPopup;
    private String appearanceSignature;
    private boolean started;
    private boolean resumed;
    private boolean stoppedWhileUnlocked;
    private boolean explicitlyNavigatedAway;
    private boolean lockedWhileInRecents;
    private boolean homeSentForCurrentLock;
    private boolean sessionOpenedFromHome;
    private boolean userInteractedInRecents;
    private boolean resetCarouselOnNextResume;
    private boolean lastFocusCurrentApp;
    private boolean lastReverseOrder;
    private boolean lastSelectedBanner;
    private boolean lastCarouselArc;
    private boolean lastWallpaperEnabled;
    private boolean lastDimOverlay;
    private boolean lastBlurEnabled;
    private boolean lastNativeBlur;
    private boolean lastHasCachedBlur;
    private long lastRecentsPress;
    private long sessionStartUptimeMs;
    private long lastPausedUptimeMs;
    @Nullable
    private BroadcastReceiver screenLockReceiver;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        boolean isKissApp = getApplication() instanceof KissApplication;
        if (isKissApp) {
            prefs = PreferenceManager.getDefaultSharedPreferences(this);
            ConfigSlots.migrateLegacyPrefsIfNeeded(prefs);
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSplashScreen().setOnExitAnimationListener(SplashScreenView::remove);
        }

        disableActivityTransition();
        UIColors.updateThemePrimaryColor(this);

        KissApplication.getApplication(this).getDataHandler().setMainActivity(this);

        Intent launchIntent = getIntent();
        RecentsBridgeClient.attachFromIntent(launchIntent);

        setContentView(R.layout.main);
        View contentView = findViewById(android.R.id.content);
        SystemBars.applyInsets(contentView, contentView);

        boolean wallpaperEnabled = Appearance.isWallpaperEnabled(prefs);
        rootLayout = findViewById(R.id.mainRoot);
        listContainer = findViewById(R.id.resultLayout);
        searchBarView = findViewById(R.id.searchEditLayout);
        homeBarView = findViewById(R.id.homeBar);
        carousel = findViewById(R.id.recentsCarousel);
        carousel.setTransparentViewport(wallpaperEnabled);
        syncWallpaperEffects(true);
        Appearance.applyRoundedCorners(prefs, searchBarView, listContainer);
        emptyListView = findViewById(android.R.id.empty);
        launcherButton = findViewById(R.id.launcherButton);
        loaderSpinner = findViewById(R.id.loaderBar);
        ImageView clearButton = findViewById(R.id.clearButton);

        boolean providersLoaded = KissApplication.getApplication(this).getDataHandler().isAllProvidersLoaded();
        displayLoader(!providersLoaded);

        adapter = new RecordAdapter(this, new ArrayList<>());
        syncFeaturePrefs(true);
        carousel.setCallback(new RecentsCarouselView.Callback() {
            @Override
            public boolean canDismiss(Result<?> result) {
                return result instanceof AppResult && canControlRecents();
            }

            @Override
            public boolean includeInClearAll(Result<?> result) {
                if (!(result instanceof AppResult)) {
                    return false;
                }
                AppResult appResult = (AppResult) result;
                // Apps marked "Don't clear" keep running
                if (KeepAlive.isKept(MainActivity.this, appResult.getPackageName())) {
                    return false;
                }
                // Optionally protect top 5 history tasks from Clear All
                if (prefs != null
                        && prefs.getBoolean("clear-all-keep-history", false)
                        && appResult.getPojo().isTopHistory) {
                    return false;
                }
                return true;
            }

            @Override
            public void onDismissed(List<Result<?>> results) {
                userInteractedInRecents = true;
                stopApps(results);
                adapter.removeResults(results);
                if (homeBarView != null) {
                    homeBarView.postDelayed(MainActivity.this::updateTotalRamDisplay, 350L);
                }
            }

            @Override
            public void onLaunchSelected(Result<?> result, @Nullable View sourceView) {
                explicitlyNavigatedAway = true;
                resetCarouselOnNextResume = true;
                resetRecentsPressState();
                if (sourceView != null && Boolean.TRUE.equals(sourceView.getTag(R.id.banner_scrim))) {
                    disableActivityTransition();
                }
                result.launch(MainActivity.this, sourceView, MainActivity.this);
            }

            @Override
            public void onKeepAliveToggled() {
                userInteractedInRecents = true;
                carousel.refreshAllBoundItems();
            }

            @Override
            public void onExternalNavigation() {
                explicitlyNavigatedAway = true;
                resetCarouselOnNextResume = true;
                resetRecentsPressState();
            }

            @Override
            public void onUserInteracted() {
                userInteractedInRecents = true;
            }

            @Override
            public void onOpenSesameProgress(float progress) {
                if (searchBarView != null) {
                    searchBarView.setAlpha(Math.max(0f, 1f - progress * 2.2f));
                }
                if (listContainer != null) {
                    if (progress > 0f) {
                        listContainer.setClipToOutline(false);
                        listContainer.setTranslationZ(32f);
                    } else {
                        listContainer.setTranslationZ(0f);
                    }
                }
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
        searchBarView.setOnClickListener(v -> goHome());
        homeBarView.setOnClickListener(v -> goHome());

        // Left button: settings
        launcherButton.setOnClickListener(v -> {
            explicitlyNavigatedAway = true;
            resetRecentsPressState();
            startActivity(new Intent(this, SettingsActivity.class));
        });

        // Right button: clear all recents
        clearButton.setOnClickListener(v -> {
            userInteractedInRecents = true;
            clearAllRecents();
        });

        updateTotalRamDisplay();
        registerScreenLockReceiver();
        handleRecentsPress(launchIntent, true, false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        stoppedWhileUnlocked = false;
        if (sessionStartUptimeMs == 0L) {
            sessionStartUptimeMs = SystemClock.uptimeMillis();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (lockedWhileInRecents) {
            lockedWhileInRecents = false;
            homeSentForCurrentLock = false;
            goHome();
            return;
        }

        resumed = true;
        explicitlyNavigatedAway = false;
        stoppedWhileUnlocked = false;
        lastPausedUptimeMs = 0L;
        if (rootLayout != null && rootLayout.getVisibility() != View.VISIBLE) {
            rootLayout.setVisibility(View.VISIBLE);
        }

        if (!Appearance.signature(prefs).equals(appearanceSignature)) {
            // The appearance was imported while this screen was open: rebuild it with the new theme
            recreate();
            return;
        }

        dismissPopup();
        resetOpenSesameUi();
        syncWallpaperEffects(false);
        syncFeaturePrefs(false);
        updateTotalRamDisplay();

        if (KissApplication.getApplication(this).getDataHandler().isAllProvidersLoaded()) {
            displayLoader(false);
        }

        if (resetCarouselOnNextResume) {
            resetCarouselOnNextResume = false;
            if (carousel != null) {
                carousel.closeContextMenu();
                carousel.resetSelection();
            }
        }

        // Open apps change while we are in the background: always rebuild the list
        updateSearchRecords();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        lastPausedUptimeMs = SystemClock.uptimeMillis();
        if (!explicitlyNavigatedAway && isDeviceLockedOrScreenOff()) {
            triggerLockToHome();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        resumed = false;
        if (!explicitlyNavigatedAway && !lockedWhileInRecents && isDeviceLockedOrScreenOff()) {
            triggerLockToHome();
        } else if (!lockedWhileInRecents) {
            stoppedWhileUnlocked = true;
        }
        if (!lockedWhileInRecents && rootLayout != null && rootLayout.getVisibility() != View.VISIBLE) {
            rootLayout.setVisibility(View.VISIBLE);
        }
        resetCarouselOnNextResume = true;
        resetRecentsPressState();
        resetOpenSesameUi();
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
        boolean wasNavigatedAway = explicitlyNavigatedAway;
        lockedWhileInRecents = false;
        homeSentForCurrentLock = false;
        explicitlyNavigatedAway = false;
        stoppedWhileUnlocked = false;
        if (rootLayout != null && rootLayout.getVisibility() != View.VISIBLE) {
            rootLayout.setVisibility(View.VISIBLE);
        }
        disableActivityTransition();
        setIntent(intent);
        RecentsBridgeClient.attachFromIntent(intent);
        resetOpenSesameUi();
        syncWallpaperEffects(false);
        syncFeaturePrefs(false);
        updateTotalRamDisplay();
        handleRecentsPress(intent, false, wasNavigatedAway);
    }

    private void syncFeaturePrefs(boolean initial) {
        if (prefs == null || carousel == null) {
            return;
        }
        ConfigSlots.migrateLegacyPrefsIfNeeded(prefs);

        boolean focusCurrent = prefs.getBoolean("focus-current-app", false);
        boolean reverseOrder = prefs.getBoolean("reverse-order", false);
        boolean ramHistorySort = prefs.getBoolean("ram-history-sort", false);
        boolean ramHistorySeparators = prefs.getBoolean("ram-history-separators", false);

        boolean selectedBanner = prefs.getBoolean("selected-banner", false);
        boolean moreBanners = prefs.getBoolean("more-banners", false);
        boolean moreBannersAll = prefs.getBoolean("more-banners-all", false);
        boolean selectedBannerIcon = prefs.getBoolean("selected-banner-icon", false);
        boolean selectedBannerOutline = prefs.getBoolean("selected-banner-outline", false);
        boolean greyscale = prefs.getBoolean("snapshot-greyscale", false);
        boolean thumbnailParallax = prefs.getBoolean("thumbnail-parallax", false);
        boolean clearDeadThumbnails = prefs.getBoolean("clear-dead-thumbnails", false);

        boolean carouselArc = prefs.getBoolean("carousel-arc", false);
        boolean carouselDepthEffects = prefs.getBoolean("carousel-depth-effects", false);
        boolean tiltSwayOvershoot = prefs.getBoolean("tilt-sway-overshoot", false);
        boolean tactileScrollSnapping = prefs.getBoolean("tactile-scroll-snapping", false);
        boolean launchEntranceAnim = prefs.getBoolean("launch-entrance-anim", false);
        boolean openSesame = prefs.getBoolean("open-sesame", false);
        boolean curvedContextMenu = prefs.getBoolean("curved-context-menu", false);
        boolean showScrollbar = prefs.getBoolean("show-scrollbar", false);

        if (!initial && (focusCurrent != lastFocusCurrentApp
                || reverseOrder != lastReverseOrder
                || selectedBanner != lastSelectedBanner
                || carouselArc != lastCarouselArc)) {
            resetCarouselOnNextResume = true;
        }
        lastFocusCurrentApp = focusCurrent;
        lastReverseOrder = reverseOrder;
        lastSelectedBanner = selectedBanner;
        lastCarouselArc = carouselArc;

        carousel.setSelectedBanner(selectedBanner);
        carousel.setMoreBanners(moreBanners, moreBannersAll);
        carousel.setSelectedBannerIcon(selectedBannerIcon);
        carousel.setSelectedBannerOutline(selectedBannerOutline);
        carousel.setGreyscaleSnapshots(greyscale);
        carousel.setThumbnailParallax(thumbnailParallax);
        carousel.setClearDeadThumbnails(clearDeadThumbnails);

        carousel.setCarouselArc(carouselArc);
        carousel.setCarouselDepthEffects(carouselDepthEffects);
        carousel.setTiltSwayOvershoot(tiltSwayOvershoot);
        carousel.setTactileScrollSnapping(tactileScrollSnapping);
        carousel.setLaunchEntranceAnim(launchEntranceAnim);
        carousel.setOpenSesame(openSesame);
        carousel.setCurvedContextMenu(curvedContextMenu);
        carousel.setShowScrollbar(showScrollbar);

        carousel.setRamHistorySort(ramHistorySort, ramHistorySeparators);
        carousel.setFocusCurrentApp(focusCurrent);
        carousel.setReverseOrder(reverseOrder);
    }

    private void resetOpenSesameUi() {
        if (carousel != null) {
            carousel.resetOpenSesame();
        }
        if (searchBarView != null) {
            searchBarView.setAlpha(1f);
        }
        if (listContainer != null) {
            listContainer.setTranslationZ(0f);
            if (prefs != null) {
                Appearance.applyRoundedCorners(prefs, searchBarView, listContainer);
            }
        }
    }

    private void updateTotalRamDisplay() {
        if (homeBarView == null || prefs == null) {
            return;
        }
        if (!prefs.getBoolean("show-total-ram", false)) {
            homeBarView.setText("");
            return;
        }
        ActivityManager am = ContextCompat.getSystemService(this, ActivityManager.class);
        if (am == null) {
            homeBarView.setText("");
            return;
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.totalMem <= 0L) {
            homeBarView.setText("");
            return;
        }
        long usedBytes = Math.max(0L, mi.totalMem - mi.availMem);
        String usedStr = Formatter.formatShortFileSize(this, usedBytes);
        String totalStr = Formatter.formatShortFileSize(this, mi.totalMem);
        homeBarView.setGravity(Gravity.CENTER);
        homeBarView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        homeBarView.setAlpha(0.8f);
        homeBarView.setText(getString(R.string.total_ram_format, usedStr, totalStr));
    }

    private void resetRecentsPressState() {
        lastRecentsPress = 0L;
        sessionStartUptimeMs = 0L;
        sessionOpenedFromHome = false;
        userInteractedInRecents = false;
    }

    /**
     * Single tap opens KISS Recents; a clean quick second tap while Recents is open jumps to the
     * previous app (or the current/last app when opened from Home, or after scrolling/interacting).
     */
    private void handleRecentsPress(@Nullable Intent intent, boolean created, boolean wasNavigatedAway) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_RECENTS_PRESSED, false)) {
            return;
        }
        intent.removeExtra(EXTRA_RECENTS_PRESSED);

        boolean openedFromHome = intent.getBooleanExtra(EXTRA_OPENED_FROM_HOME, false);
        long now = SystemClock.uptimeMillis();
        boolean wasPausedInBackground = !resumed
                && lastPausedUptimeMs > 0L
                && (now - lastPausedUptimeMs) > 120L;

        if (created || !started || lastRecentsPress == 0L || wasNavigatedAway || wasPausedInBackground) {
            sessionStartUptimeMs = now;
            lastRecentsPress = now;
            sessionOpenedFromHome = openedFromHome;
            userInteractedInRecents = false;
            return;
        }

        long delta = now - lastRecentsPress;
        if (delta < DUPLICATE_BOUNCE_WINDOW_MS) {
            return;
        }

        boolean cleanDoubleTap = delta < DOUBLE_PRESS_WINDOW_MS
                && !userInteractedInRecents;
        boolean targetCurrentApp = sessionOpenedFromHome || !cleanDoubleTap;
        lastRecentsPress = now;
        switchToTaskAtOffset(targetCurrentApp ? 0 : 1);
    }

    /**
     * Launch the task at the given index in recents order (0 = current / most recent, 1 = previous).
     */
    private void switchToTaskAtOffset(final int desiredIndex) {
        if (!canControlRecents()) {
            return;
        }
        List<AppPojo> cached = HistorySearcher.getRecentCachedTasks(CACHED_TASKS_MAX_AGE_MS, sessionStartUptimeMs);
        if (!cached.isEmpty()) {
            launchTask(cached.get(Math.min(desiredIndex, cached.size() - 1)));
            return;
        }
        Searcher.SEARCH_THREAD.execute(() -> {
            List<AppPojo> tasks = HistorySearcher.loadRunning(this).tasks;
            if (tasks.isEmpty()) {
                return;
            }
            final AppPojo target = tasks.get(Math.min(desiredIndex, tasks.size() - 1));
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    launchTask(target);
                }
            });
        });
    }

    private void launchTask(AppPojo target) {
        explicitlyNavigatedAway = true;
        resetCarouselOnNextResume = true;
        resetRecentsPressState();
        HistorySearcher.clearDismissed(target.packageName);
        if (target.taskId > 0) {
            HistorySearcher.clearDismissedTask(target.taskId);
        }
        Result.fromPojo(this, target).launch(this, null, this);
    }

    @Override
    protected void onDestroy() {
        unregisterScreenLockReceiver();
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
        sendHomeIntent(true);
    }

    private void sendHomeIntent(boolean markExplicit) {
        if (markExplicit) {
            explicitlyNavigatedAway = true;
        }
        resetCarouselOnNextResume = true;
        resetRecentsPressState();
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            Bundle noAnim = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle();
            startActivity(home, noAnim);
        } catch (Throwable ignored) {
            try {
                startActivity(home);
            } catch (Throwable ignored2) {
            }
        }
        disableActivityTransition();
    }

    private boolean isDeviceLockedOrScreenOff() {
        PowerManager pm = ContextCompat.getSystemService(this, PowerManager.class);
        if (pm != null && !pm.isInteractive()) {
            return true;
        }
        KeyguardManager km = ContextCompat.getSystemService(this, KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    private void registerScreenLockReceiver() {
        if (screenLockReceiver != null) {
            return;
        }
        screenLockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    onScreenTurnedOff();
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        ContextCompat.registerReceiver(this, screenLockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterScreenLockReceiver() {
        if (screenLockReceiver != null) {
            try {
                unregisterReceiver(screenLockReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            screenLockReceiver = null;
        }
    }

    private void onScreenTurnedOff() {
        if (explicitlyNavigatedAway || stoppedWhileUnlocked) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean wasInRecents = resumed
                || started
                || (lastPausedUptimeMs > 0L && (now - lastPausedUptimeMs) < 2500L);
        if (wasInRecents) {
            triggerLockToHome();
        }
    }

    private void triggerLockToHome() {
        lockedWhileInRecents = true;
        resetCarouselOnNextResume = true;
        resetRecentsPressState();
        if (rootLayout != null) {
            rootLayout.setVisibility(View.INVISIBLE);
        }
        if (!homeSentForCurrentLock) {
            homeSentForCurrentLock = true;
            sendHomeIntent(false);
        }
    }

    private void syncWallpaperEffects(boolean forceApplyWindow) {
        boolean wallpaperEnabled = Appearance.isWallpaperEnabled(prefs);
        boolean dimOverlay = prefs.getBoolean("wallpaper-dim-overlay", false);
        boolean blurEnabled = Appearance.isBlurEnabled(prefs);
        boolean hasCachedBlur = blurEnabled && WallpaperBlurCache.get(this) != null;
        boolean nativeBlur = blurEnabled && !hasCachedBlur && Appearance.isCrossWindowBlurSupported(this);

        if (forceApplyWindow
                || wallpaperEnabled != lastWallpaperEnabled
                || dimOverlay != lastDimOverlay
                || blurEnabled != lastBlurEnabled
                || nativeBlur != lastNativeBlur
                || hasCachedBlur != lastHasCachedBlur) {
            lastWallpaperEnabled = wallpaperEnabled;
            lastDimOverlay = dimOverlay;
            lastBlurEnabled = blurEnabled;
            lastNativeBlur = nativeBlur;
            lastHasCachedBlur = hasCachedBlur;
            Appearance.applyWindowWallpaper(this, prefs);
        }

        if (rootLayout != null) {
            rootLayout.setClearTransparentViewport(wallpaperEnabled);
            rootLayout.setDarkWallpaperOverlay(dimOverlay);
            rootLayout.setWallpaperBlurEnabled(wallpaperEnabled && blurEnabled);
        }
        if (carousel != null) {
            carousel.setTransparentViewport(wallpaperEnabled);
        }

        if (wallpaperEnabled && blurEnabled) {
            WallpaperBlurCache.requestAsyncIfNeeded(this, () -> runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                syncWallpaperEffects(false);
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
        HistorySearcher.invalidateCachedTasks();
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
