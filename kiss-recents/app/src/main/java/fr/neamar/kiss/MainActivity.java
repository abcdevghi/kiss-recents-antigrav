package fr.neamar.kiss;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ImageView;

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
import fr.neamar.kiss.ui.AnimatedListView;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.ui.SwipeToDismiss;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.SystemBars;
import fr.neamar.kiss.utils.Utilities;

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
    /** A press of recents this soon after the previous one is a double press, even if the screen is not shown yet */
    private static final long DOUBLE_PRESS_WINDOW_MS = 1500;
    private static final long CACHED_TASKS_MAX_AGE_MS = 2500;

    private static final String TAG = "MainActivity";

    public RecordAdapter adapter;
    public SharedPreferences prefs;
    public AnimatedListView list;

    private View listContainer;
    private View emptyListView;
    private View launcherButton;
    private View loaderSpinner;
    private ListPopup mPopup;
    private String appearanceSignature;
    private SwipeToDismiss swipeToDismiss;
    private boolean started;
    private long lastRecentsPress;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // During a full backup restore, Android may start the process in restricted mode with a plain Application.
        if (!(getApplication() instanceof KissApplication)) {
            Log.w(TAG, "Not running in KissApplication, process is likely in restricted mode (backup/restore)");
            finish();
            return;
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Theme must be applied before setContentView()
        Appearance.applyTheme(this, prefs);
        appearanceSignature = Appearance.signature(prefs);
        UIColors.clearColorCache();
        UIColors.updateThemePrimaryColor(this);

        KissApplication.getApplication(this).getDataHandler().setMainActivity(this);

        setContentView(R.layout.main);
        View contentView = findViewById(android.R.id.content);
        SystemBars.applyInsets(contentView, contentView);

        list = findViewById(android.R.id.list);
        listContainer = (View) list.getParent();
        Appearance.applyRoundedCorners(prefs, findViewById(R.id.searchEditLayout), listContainer);
        emptyListView = findViewById(android.R.id.empty);
        launcherButton = findViewById(R.id.launcherButton);
        loaderSpinner = findViewById(R.id.loaderBar);
        ImageView clearButton = findViewById(R.id.clearButton);

        boolean providersLoaded = KissApplication.getApplication(this).getDataHandler().isAllProvidersLoaded();
        displayLoader(!providersLoaded);

        adapter = new RecordAdapter(this, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, position, id) -> adapter.onClick(position, v));
        list.setOnItemLongClickListener((parent, v, pos, id) -> {
            adapter.onLongClick(pos, v);
            return true;
        });

        // Swipe a row sideways to stop the app and remove it
        swipeToDismiss = new SwipeToDismiss(list, adapter, new SwipeToDismiss.Callback() {
            @Override
            public boolean canDismiss(Result<?> result) {
                return result instanceof AppResult && isRootActivated();
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
        });
        list.setOnTouchListener(swipeToDismiss);
        list.setOnScrollListener(swipeToDismiss.makeScrollListener());

        // Show a message when nothing is open
        adapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                super.onChanged();
                boolean empty = adapter.isEmpty();
                listContainer.setVisibility(empty ? View.GONE : View.VISIBLE);
                emptyListView.setVisibility(empty ? View.VISIBLE : View.GONE);
            }
        });

        // The search bar only sends the user Home
        findViewById(R.id.searchEditLayout).setOnClickListener(v -> goHome());
        findViewById(R.id.homeBar).setOnClickListener(v -> goHome());

        // Left button: settings
        launcherButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Right button: clear all recents
        clearButton.setOnClickListener(v -> clearAllRecents());

        handleRecentsPress(getIntent(), true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
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
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleRecentsPress(intent, false);
    }

    /**
     * The LSPosed hook opens this screen each time the recents button is pressed. A press while the screen is
     * already showing (or about to) is a double press: go to the app used before the last one,
     * as double-tapping recents does in Android.
     * Uses {@link #started} instead of resumed because singleTask delivery triggers onPause() before onNewIntent().
     */
    private void handleRecentsPress(@Nullable Intent intent, boolean created) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_RECENTS_PRESSED, false)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean doublePress = !created && (started || now - lastRecentsPress < DOUBLE_PRESS_WINDOW_MS);
        lastRecentsPress = now;
        if (doublePress) {
            switchToPreviousApp();
        }
    }

    /**
     * Launch the second most recent app: the most recent one is the app that was open when recents was pressed.
     * With a single open app, that app.
     */
    private void switchToPreviousApp() {
        if (!isRootActivated()) {
            return;
        }
        List<AppPojo> cached = HistorySearcher.getRecentCachedTasks(CACHED_TASKS_MAX_AGE_MS);
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
        HistorySearcher.clearDismissed(target.packageName);
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
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
    }

    private boolean isRootActivated() {
        return KissApplication.getApplication(this).getRootHandler().isRootActivated();
    }

    /**
     * Slide every row out, then stop all the apps listed.
     */
    private void clearAllRecents() {
        if (isRootActivated()) {
            swipeToDismiss.dismissAll();
        }
    }

    /**
     * Stop the apps of these results in a single batch root shell call.
     */
    private void stopApps(List<Result<?>> results) {
        final List<String> packages = new ArrayList<>();
        for (Result<?> result : results) {
            if (result instanceof AppResult) {
                packages.add(((AppResult) result).getPackageName());
            }
        }
        if (packages.isEmpty()) {
            return;
        }
        HistorySearcher.markDismissed(packages);
        final RootHandler rootHandler = KissApplication.getApplication(this).getRootHandler();
        Utilities.getDefaultExecutor().execute(() -> rootHandler.hibernateApps(packages));
    }

    public void updateSearchRecords() {
        if (swipeToDismiss != null && swipeToDismiss.isAnimating()) {
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
        updateTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);
        // Process after all current messages, to reset transcript mode to default
        list.post(() -> updateTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL));
    }

    @Override
    public void updateTranscriptMode(int transcriptMode) {
        list.setTranscriptMode(transcriptMode);
    }

    @Override
    public void beforeListChange() {
        // Remember where the rows are, so that they can slide to their new place
        list.prepareChangeAnim();
    }

    @Override
    public void afterListChange() {
        list.animateChange();
    }

    @Override
    public void launchOccurred() {
        // Nothing to clean up: there is no search text
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
