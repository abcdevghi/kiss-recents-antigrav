package fr.neamar.kiss.result;

import static fr.neamar.kiss.dataprovider.ProviderName.APPS;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.IconsHandler;
import fr.neamar.kiss.KeepAlive;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.UIColors;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.icons.IconPack;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.searcher.HistorySearcher;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.utils.DrawableUtils;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.RecentsBridgeClient;
import fr.neamar.kiss.utils.Utilities;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public class AppResult extends ResultWithTags<AppPojo> {

    private static final String TAG = AppResult.class.getSimpleName();
    private static final Set<String> PROTECTED_SYSTEM_PACKAGES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("android", "com.android.systemui", "com.android.settings", "com.android.shell")
    ));
    private volatile Drawable icon = null;

    public String getPackageName() {
        return pojo.packageName;
    }

    AppResult(@NonNull AppPojo pojo) {
        super(pojo);
    }

    @NonNull
    @Override
    public View display(final Context context, View view, @NonNull ViewGroup parent, FuzzyScore fuzzyScore) {
        if (view == null) {
            view = inflateFromId(context, R.layout.item_app, parent);
        }

        TextView appName = view.findViewById(R.id.item_app_name);

        displayHighlighted(pojo.normalizedName, pojo.getName(), fuzzyScore, appName, context);

        TextView tagsView = view.findViewById(R.id.item_app_tag);
        displayTags(context, fuzzyScore, tagsView);
        if (KeepAlive.isKept(context, pojo.packageName)) {
            // Shown after the memory figure, when there is one
            String kept = context.getString(R.string.keep_alive_label);
            tagsView.setText(tagsView.getVisibility() == View.VISIBLE ? tagsView.getText() + " \u00b7 " + kept : kept);
            tagsView.setVisibility(View.VISIBLE);
        }

        final ImageView appIcon = view.findViewById(R.id.item_app_icon);
        if (!isHideIcons(context)) {
            this.setAsyncDrawable(appIcon);
        } else {
            appIcon.setImageDrawable(null);
        }

        displayNotificationDot(context, view, false);

        return view;
    }

    @Override
    public void inflateFavorite(@NonNull Context context, @NonNull View favoriteView) {
        super.inflateFavorite(context, favoriteView);
        displayNotificationDot(context, favoriteView, true);
    }

    private void displayNotificationDot(Context context, View view, boolean isFavorite) {
        // Notification dots are not supported: there is no notification listener in this app
        view.findViewById(R.id.item_notification_dot).setVisibility(View.GONE);
    }

    private String getPackageKey() {
        return pojo.getPackageKey();
    }

    public static boolean isProtectedSystemPackage(String packageName) {
        return PROTECTED_SYSTEM_PACKAGES.contains(packageName);
    }

    @Override
    protected void buildPopupMenu(Context context, ArrayAdapter<ListPopup.Item> adapter) {
        super.buildPopupMenu(context, adapter);

        if (!pojo.isTaskOnly) {
            adapter.add(new ListPopup.Item(context, R.string.menu_exclude));
            adapter.add(new ListPopup.Item(context, R.string.menu_app_rename));
            if (!pojo.isDisabled()) {
                adapter.add(new ListPopup.Item(context, R.string.menu_app_details));
            }
            adapter.add(new ListPopup.Item(context, R.string.menu_app_store));

            boolean uninstallDisabled = pojo.isDisabled();
            if (!uninstallDisabled) {
                // app installed under /system can't be uninstalled
                ApplicationInfo ai = PackageManagerUtils.getApplicationInfo(context, this.pojo.packageName, this.pojo.userHandle);
                // Need to AND the flags with SYSTEM:
                uninstallDisabled = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            }

            if (!uninstallDisabled) {
                UserManager userManager = ContextCompat.getSystemService(context, UserManager.class);
                if (userManager != null) {
                    Bundle restrictions = userManager.getUserRestrictions(pojo.userHandle.getRealHandle());
                    uninstallDisabled = restrictions.getBoolean(UserManager.DISALLOW_APPS_CONTROL, false)
                            || restrictions.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS, false);
                }
            }

            if (!uninstallDisabled) {
                adapter.add(new ListPopup.Item(context, R.string.menu_app_uninstall));
            }
        }

        // Append task removal / root menu if available
        boolean rootActivated = KissApplication.getApplication(context).getRootHandler().isRootActivated();
        if (rootActivated && !pojo.isTaskOnly) {
            adapter.add(new ListPopup.Item(context, KeepAlive.isKept(context, pojo.packageName)
                    ? R.string.menu_keep_alive_off : R.string.menu_keep_alive));
        }
        if (rootActivated || (pojo.taskId > 0 && RecentsBridgeClient.isAvailable())) {
            adapter.add(new ListPopup.Item(context, R.string.menu_app_hibernate));
        }
    }

    @Override
    protected boolean popupMenuClickHandler(final Context context, final RecordAdapter parent, int stringId, View parentView) {
        if (stringId == R.string.menu_app_details) {
            launchAppDetails(context);
            return true;
        } else if (stringId == R.string.menu_app_store) {
            launchAppStore(context);
            return true;
        } else if (stringId == R.string.menu_app_uninstall) {
            launchUninstall(context);
            return true;
        } else if (stringId == R.string.menu_app_hibernate) {
            hibernate(context);
            return true;
        } else if (stringId == R.string.menu_keep_alive || stringId == R.string.menu_keep_alive_off) {
            KeepAlive.setKept(context, pojo.packageName, stringId == R.string.menu_keep_alive);
            // Redraw the rows: the one that changed shows or hides "Not cleared"
            parent.notifyDataSetChanged();
            return true;
        } else if (stringId == R.string.menu_exclude) {
            final int EXCLUDE_HISTORY_ID = 0;
            final int EXCLUDE_KISS_ID = 1;
            PopupMenu popupExcludeMenu = new PopupMenu(context, parentView);
            //Adding menu items
            popupExcludeMenu.getMenu().add(EXCLUDE_HISTORY_ID, Menu.NONE, Menu.NONE, R.string.menu_exclude_history);
            popupExcludeMenu.getMenu().add(EXCLUDE_KISS_ID, Menu.NONE, Menu.NONE, R.string.menu_exclude_kiss);
            //registering popup with OnMenuItemClickListener
            popupExcludeMenu.setOnMenuItemClickListener(item -> {
                switch (item.getGroupId()) {
                    case EXCLUDE_HISTORY_ID:
                        excludeFromHistory(context, pojo);
                        return true;
                    case EXCLUDE_KISS_ID:
                        excludeFromKiss(context, pojo, parent);
                        return true;
                }

                return true;
            });

            popupExcludeMenu.show();
            return true;
        } else if (stringId == R.string.menu_app_rename) {
            launchRenameDialog(context, parent, pojo);
            return true;
        }

        return super.popupMenuClickHandler(context, parent, stringId, parentView);
    }

    private void excludeFromHistory(Context context, AppPojo pojo) {
        // add to excluded from history app list
        KissApplication.getApplication(context).getDataHandler().addToExcludedFromHistory(pojo);
        // remove from history
        removeFromHistory(context);
        // inform user
        Toast.makeText(context, R.string.excluded_app_history_added, Toast.LENGTH_LONG).show();
    }

    private void excludeFromKiss(Context context, AppPojo pojo, final RecordAdapter parent) {
        // remove item since it will be hidden
        parent.removeResult(AppResult.this);

        KissApplication.getApplication(context).getDataHandler().addToExcluded(pojo);
        Toast.makeText(context, R.string.excluded_app_list_added, Toast.LENGTH_LONG).show();
    }

    private void launchRenameDialog(final Context context, RecordAdapter parent, final AppPojo app) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setTitle(context.getResources().getString(R.string.app_rename_title));

        builder.setView(R.layout.rename_dialog);

        builder.setPositiveButton(R.string.custom_name_rename, (dialog, which) -> {
            EditText input = ((AlertDialog) dialog).findViewById(R.id.rename);
            dialog.dismiss();

            // Set new name
            String newName = input.getText().toString().trim();
            DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();
            dataHandler.updateApp(app, a -> a.setName(newName));
            dataHandler.renameApp(app.getComponentName(), newName);

            // Show toast message
            String msg = context.getResources().getString(R.string.app_rename_confirmation, newName);
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
            setTranscriptModeAlwaysScroll(parent);
        });
        builder.setNegativeButton(R.string.custom_name_set_default, (dialog, which) -> {
            dialog.dismiss();

            KissApplication.getApplication(context).getDataHandler().removeRenameApp(getComponentName());

            // Set initial name
            String name = PackageManagerUtils.getLabel(context, new ComponentName(app.packageName, app.activityName), app.userHandle);
            if (name != null) {
                KissApplication.getApplication(context).getDataHandler().updateApp(app, a -> a.setName(name));

                // Show toast message
                String msg = context.getResources().getString(R.string.app_rename_confirmation, name);
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
            }
            setTranscriptModeAlwaysScroll(parent);
        });
        builder.setNeutralButton(android.R.string.cancel, (dialog, which) -> {
            dialog.cancel();
            setTranscriptModeAlwaysScroll(parent);
        });
        setTranscriptModeDisabled(parent);
        AlertDialog dialog = builder.create();
        dialog.show();
        // call after dialog got inflated (show call)
        ((TextView) dialog.findViewById(R.id.rename)).setText(app.getName());
    }

    /**
     * Open an activity displaying details regarding the current package
     */
    private void launchAppDetails(Context context) {
        LauncherApps launcher = ContextCompat.getSystemService(context, LauncherApps.class);
        assert launcher != null;
        launcher.startAppDetailsActivity(getClassName(), pojo.userHandle.getRealHandle(), null, null);
    }

    private void launchAppStore(Context context) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pojo.packageName)));
        } catch (ActivityNotFoundException anfe) {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pojo.packageName)));
        }
    }

    private void hibernate(Context context) {
        final Context appContext = context.getApplicationContext();
        final int taskId = pojo.taskId;
        final String packageName = pojo.packageName;
        final boolean forceStopEligible = !pojo.isTaskOnly && !isProtectedSystemPackage(packageName);

        if (taskId > 0) {
            HistorySearcher.markDismissedTasks(Collections.singletonList(taskId));
        }
        if (forceStopEligible) {
            HistorySearcher.markDismissed(Collections.singletonList(packageName));
        }

        Utilities.getDefaultExecutor().execute(() -> {
            boolean removed = taskId > 0 && RecentsBridgeClient.removeTask(taskId);
            boolean stopped = false;
            if (forceStopEligible && KissApplication.getApplication(appContext).getRootHandler().isRootActivated()) {
                stopped = KissApplication.getApplication(appContext).getRootHandler().hibernateApp(packageName);
            }
            final boolean ok = removed || stopped;
            new Handler(Looper.getMainLooper()).post(() -> {
                String msg = appContext.getResources().getString(
                        ok ? R.string.toast_hibernate_completed : R.string.toast_hibernate_error);
                if (ok) {
                    if (context instanceof MainActivity) {
                        ((MainActivity) context).updateSearchRecords();
                    } else {
                        KissApplication.getApplication(appContext).getDataHandler().reload(APPS);
                    }
                }
                Toast.makeText(appContext, String.format(msg, pojo.getName()), Toast.LENGTH_SHORT).show();
            });
        });
    }

    /**
     * Open an activity to uninstall the current package
     */
    private void launchUninstall(Context context) {
        Intent intent = new Intent(Intent.ACTION_DELETE,
                Uri.fromParts("package", pojo.packageName, null));
        intent.putExtra(Intent.EXTRA_USER, pojo.userHandle.getRealHandle());
        context.startActivity(intent);
    }

    @Override
    boolean isDrawableCached() {
        return icon != null;
    }

    @Override
    void setDrawableCache(Drawable drawable) {
        icon = drawable;
    }

    @Override
    public Drawable getDrawable(Context context) {
        if (icon == null) {
            synchronized (this) {
                if (icon == null) {
                    if (pojo.isTaskOnly) {
                        if (pojo.hasTaskIcon && pojo.taskId > 0) {
                            Bitmap bmp = RecentsBridgeClient.getTaskIcon(pojo.taskId);
                            if (bmp != null) {
                                icon = DrawableUtils.getThemedDrawable(context, new BitmapDrawable(context.getResources(), bmp));
                            }
                        }
                        if (icon == null) {
                            icon = PackageManagerUtils.getActivityIcon(context, getClassName(), pojo.userHandle);
                        }
                    } else {
                        IconsHandler iconsHandler = KissApplication.getApplication(context).getIconsHandler();
                        icon = iconsHandler.getDrawableIconForPackage(context, getClassName(), this.pojo.userHandle);
                    }
                }
            }
        }
        DrawableUtils.setDisabled(icon, this.pojo.isDisabled());
        return icon;
    }

    @Override
    protected boolean canAddToHistory() {
        return super.canAddToHistory() && !pojo.isTaskOnly;
    }

    @Override
    public void doLaunch(Context context, @Nullable View v) {
        try {
            HistorySearcher.clearDismissed(pojo.packageName);
            if (pojo.taskId > 0) {
                HistorySearcher.clearDismissedTask(pojo.taskId);
            }

            Rect sourceBounds = null;
            Bundle opts = null;
            if (v != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // We're on a modern Android and can display activity animations
                // If AppResult, find the icon
                View potentialIcon = v.findViewById(R.id.item_app_icon);
                if (potentialIcon == null) {
                    // If favorite, find the icon
                    potentialIcon = v.findViewById(R.id.favorite);
                }

                if (potentialIcon != null) {
                    sourceBounds = getViewBounds(potentialIcon);

                    // If we got an icon, we create options to get a nice animation
                    opts = ActivityOptions.makeClipRevealAnimation(potentialIcon, 0, 0, potentialIcon.getMeasuredWidth(), potentialIcon.getMeasuredHeight()).toBundle();
                }
            }

            // 1. Prefer resuming the live task directly by taskId via the Recents Binder bridge.
            // This handles parasitic root apps (e.g. LSPosed) and non-launcher activities natively.
            if (pojo.taskId > 0 && RecentsBridgeClient.isAvailable()) {
                if (RecentsBridgeClient.startTask(pojo.taskId, opts)) {
                    return;
                }
            }

            // 2. Standard launcher activity launch
            if (!pojo.isTaskOnly) {
                LauncherApps launcher = ContextCompat.getSystemService(context, LauncherApps.class);
                if (launcher != null) {
                    try {
                        launcher.startMainActivity(getClassName(), pojo.userHandle.getRealHandle(), sourceBounds, opts);
                        return;
                    } catch (ActivityNotFoundException | SecurityException e) {
                        Log.w(TAG, "startMainActivity failed, falling back to component/package intent", e);
                    }
                }
            }

            // 3. Explicit component or package launch intent fallback
            Intent componentIntent = new Intent(Intent.ACTION_MAIN);
            componentIntent.setComponent(getClassName());
            componentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (sourceBounds != null) {
                componentIntent.setSourceBounds(sourceBounds);
            }
            try {
                context.startActivity(componentIntent, opts);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "Explicit component launch failed, trying package launch intent", e);
            }

            Intent pkgIntent = context.getPackageManager().getLaunchIntentForPackage(pojo.packageName);
            if (pkgIntent != null) {
                pkgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (sourceBounds != null) {
                    pkgIntent.setSourceBounds(sourceBounds);
                }
                context.startActivity(pkgIntent, opts);
                return;
            }

            Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | NullPointerException | SecurityException e) {
            Log.w(TAG, "Unable to launch activity", e);
            Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();
        }
    }

    @Nullable
    @Override
    protected Rect getViewBounds(@Nullable View view) {
        if (view == null) {
            return null;
        }

        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    public String getComponentName() {
        return pojo.getComponentName();
    }

    public ComponentName getClassName() {
        return pojo.getComponent();
    }

    @Override
    protected boolean isAllowedAsFavorite() {
        return !pojo.isTaskOnly;
    }

    @Override
    protected boolean canRemoveFromHistory(Context context) {
        return !pojo.isTaskOnly;
    }

    @Override
    protected boolean canHaveCustomIcon(Context context, IconPack iconPack) {
        return !pojo.isTaskOnly;
    }
}
