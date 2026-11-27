package fr.neamar.kiss.result;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import fr.neamar.kiss.IconsHandler;
import fr.neamar.kiss.KeepAlive;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.R;
import fr.neamar.kiss.UIColors;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.icons.IconPack;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.searcher.HistorySearcher;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.utils.DrawableUtils;
import fr.neamar.kiss.utils.IconColorUtils;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.RecentsBridgeClient;
import fr.neamar.kiss.utils.Utilities;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public class AppResult extends ResultWithTags<AppPojo> {

    private static final String TAG = AppResult.class.getSimpleName();
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int WINDOWING_MODE_MULTI_WINDOW = 6;
    private static final int BANNER_TAG_KEY = R.id.banner_snapshot;
    private static final ColorFilter GREYSCALE_COLOR_FILTER;

    static {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        GREYSCALE_COLOR_FILTER = new ColorMatrixColorFilter(matrix);
    }

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

    public static boolean isProtectedSystemPackage(String packageName) {
        return PROTECTED_SYSTEM_PACKAGES.contains(packageName);
    }

    public boolean canUninstall(Context context) {
        if (pojo.isTaskOnly || pojo.isDisabled()) {
            return false;
        }
        ApplicationInfo ai = PackageManagerUtils.getApplicationInfo(context, this.pojo.packageName, this.pojo.userHandle);
        if (ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return false;
        }
        UserManager userManager = ContextCompat.getSystemService(context, UserManager.class);
        if (userManager != null) {
            Bundle restrictions = userManager.getUserRestrictions(pojo.userHandle.getRealHandle());
            if (restrictions.getBoolean(UserManager.DISALLOW_APPS_CONTROL, false)
                    || restrictions.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS, false)) {
                return false;
            }
        }
        return true;
    }

    public boolean canKeepAlive() {
        return !isProtectedSystemPackage(pojo.packageName);
    }

    public boolean isKept(Context context) {
        return KeepAlive.isKept(context, pojo.packageName);
    }

    public boolean toggleKeepAlive(Context context) {
        boolean next = !KeepAlive.isKept(context, pojo.packageName);
        KeepAlive.setKept(context, pojo.packageName, next);
        return next;
    }

    public void openAppDetails(Context context) {
        launchAppDetails(context);
    }

    public void openUninstall(Context context) {
        launchUninstall(context);
    }

    public void openSplitScreen(Context context, @Nullable View v) {
        launchSplitScreen(context, v);
    }

    public void openPopupView(Context context, @Nullable View v) {
        launchPopupView(context, v);
    }

    @ColorInt
    public int getDominantColor(Context context) {
        if (pojo.dominantColor != 0) {
            return pojo.dominantColor;
        }
        int fallback = UIColors.getPrimaryColor(context);
        if (isDrawableCached()) {
            int extracted = IconColorUtils.extractDominantColor(icon, fallback);
            pojo.dominantColor = extracted;
            return extracted;
        }
        return fallback;
    }

    /**
     * Populate the expanded carousel card for the currently selected app.
     */
    public void displayBanner(@NonNull Context context, @NonNull View bannerView,
                              @Nullable Bitmap snapshot, boolean greyscale) {
        displayBanner(context, bannerView, snapshot, greyscale, false);
    }

    public void displayBanner(@NonNull Context context, @NonNull View bannerView,
                              @Nullable Bitmap snapshot, boolean greyscale, boolean skipHeavyVisuals) {
        final float cornerRadius = context.getResources().getDimension(R.dimen.banner_card_corner_radius);
        final int strokeWidthPx = Math.max(2, DrawableUtils.dpToPx(context, 2.5f));
        if (!(bannerView.getTag(R.id.banner_outline) instanceof Boolean)) {
            bannerView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
                }
            });
            bannerView.setClipToOutline(true);
            bannerView.setElevation(DrawableUtils.dpToPx(context, 6f));
            bannerView.setTag(R.id.banner_outline, Boolean.TRUE);
        }
        bannerView.setTag(BANNER_TAG_KEY, pojo.id);

        TextView appName = bannerView.findViewById(R.id.banner_app_name);
        appName.setText(pojo.getName());

        TextView metaView = bannerView.findViewById(R.id.banner_app_meta);
        if (KeepAlive.isKept(context, pojo.packageName)) {
            metaView.setText(R.string.keep_alive_label);
            metaView.setVisibility(View.VISIBLE);
        } else {
            metaView.setVisibility(View.GONE);
        }

        final ImageView snapshotView = bannerView.findViewById(R.id.banner_snapshot);
        final ImageView fallbackIconView = bannerView.findViewById(R.id.banner_fallback_icon);
        final View outlineView = bannerView.findViewById(R.id.banner_outline);

        final boolean hasSnapshot = !skipHeavyVisuals && snapshot != null && !snapshot.isRecycled();
        if (skipHeavyVisuals) {
            snapshotView.setImageDrawable(null);
            snapshotView.setColorFilter(null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                snapshotView.setRenderEffect(null);
            }
            snapshotView.setVisibility(View.GONE);
            fallbackIconView.setImageDrawable(null);
            fallbackIconView.setVisibility(View.GONE);
        } else if (hasSnapshot) {
            snapshotView.setImageBitmap(snapshot);
            snapshotView.setColorFilter(greyscale ? GREYSCALE_COLOR_FILTER : null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                int maxDim = Math.max(snapshot.getWidth(), snapshot.getHeight());
                if (maxDim < 1080) {
                    float blurDp = maxDim < 720 ? 3.2f : 2.0f;
                    float blurPx = Math.max(2f, blurDp * context.getResources().getDisplayMetrics().density);
                    snapshotView.setRenderEffect(RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP));
                } else {
                    snapshotView.setRenderEffect(null);
                }
            }
            snapshotView.setVisibility(View.VISIBLE);
            fallbackIconView.setVisibility(View.GONE);
        } else {
            snapshotView.setImageDrawable(null);
            snapshotView.setColorFilter(null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                snapshotView.setRenderEffect(null);
            }
            snapshotView.setVisibility(View.GONE);
            fallbackIconView.setVisibility(View.VISIBLE);
            if (!isHideIcons(context)) {
                setAsyncDrawable(fallbackIconView);
            } else {
                fallbackIconView.setImageDrawable(null);
            }
        }

        int dominant = getDominantColor(context);
        applyBannerChrome(bannerView, outlineView, cornerRadius, strokeWidthPx, dominant, hasSnapshot);

        if (!skipHeavyVisuals && pojo.dominantColor == 0) {
            final String expectedId = pojo.id;
            Utilities.<Integer>runAsync(task -> {
                if (task.isCancelled()) {
                    return null;
                }
                Drawable d = getDrawable(context);
                int extracted = IconColorUtils.extractDominantColor(d, UIColors.getPrimaryColor(context));
                pojo.dominantColor = extracted;
                return Integer.valueOf(extracted);
            }, (task, colorObj) -> {
                if (task.isCancelled() || colorObj == null) {
                    return;
                }
                if (expectedId.equals(bannerView.getTag(BANNER_TAG_KEY))) {
                    applyBannerChrome(bannerView, outlineView, cornerRadius, strokeWidthPx, colorObj.intValue(), hasSnapshot);
                }
            });
        }
    }

    private static void applyBannerChrome(View bannerView, View outlineView, float cornerRadius,
                                          int strokeWidthPx, @ColorInt int dominantColor, boolean hasSnapshot) {
        if (!hasSnapshot) {
            bannerView.setBackgroundColor(ColorUtils.blendARGB(0xFF161920, dominantColor, 0.22f));
        } else {
            bannerView.setBackgroundColor(0xFF181B22);
        }
        GradientDrawable border;
        if (outlineView.getBackground() instanceof GradientDrawable) {
            border = (GradientDrawable) outlineView.getBackground();
        } else {
            border = new GradientDrawable();
            outlineView.setBackground(border);
        }
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(cornerRadius);
        border.setStroke(strokeWidthPx, dominantColor);
    }

    @Override
    protected void buildPopupMenu(Context context, ArrayAdapter<ListPopup.Item> adapter) {
        if (!pojo.isDisabled()) {
            adapter.add(new ListPopup.Item(context, R.string.menu_app_details));
        }

        if (canUninstall(context)) {
            adapter.add(new ListPopup.Item(context, R.string.menu_app_uninstall));
        }

        if (canKeepAlive()) {
            adapter.add(new ListPopup.Item(context, KeepAlive.isKept(context, pojo.packageName)
                    ? R.string.menu_keep_alive_off : R.string.menu_keep_alive));
        }

        if (!pojo.isDisabled()) {
            adapter.add(new ListPopup.Item(context, R.string.menu_split_screen));
            adapter.add(new ListPopup.Item(context, R.string.menu_popup_view));
        }
    }

    @Override
    protected boolean popupMenuClickHandler(final Context context, final RecordAdapter parent, int stringId, View parentView) {
        if (stringId == R.string.menu_app_details) {
            launchAppDetails(context);
            return true;
        } else if (stringId == R.string.menu_app_uninstall) {
            launchUninstall(context);
            return true;
        } else if (stringId == R.string.menu_keep_alive || stringId == R.string.menu_keep_alive_off) {
            KeepAlive.setKept(context, pojo.packageName, stringId == R.string.menu_keep_alive);
            // Redraw the rows: the one that changed shows or hides "Not cleared"
            parent.notifyDataSetChanged();
            return true;
        } else if (stringId == R.string.menu_split_screen) {
            launchSplitScreen(context, parentView);
            return true;
        } else if (stringId == R.string.menu_popup_view) {
            launchPopupView(context, parentView);
            return true;
        }

        return super.popupMenuClickHandler(context, parent, stringId, parentView);
    }

    /**
     * Open an activity displaying details regarding the current package
     */
    private void launchAppDetails(Context context) {
        if (!pojo.isTaskOnly) {
            LauncherApps launcher = ContextCompat.getSystemService(context, LauncherApps.class);
            if (launcher != null) {
                try {
                    launcher.startAppDetailsActivity(getClassName(), pojo.userHandle.getRealHandle(), null, null);
                    return;
                } catch (ActivityNotFoundException | SecurityException e) {
                    Log.w(TAG, "startAppDetailsActivity failed, falling back to package details", e);
                }
            }
        }
        Intent detailsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pojo.packageName, null));
        detailsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(detailsIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void launchSplitScreen(Context context, @Nullable View v) {
        HistorySearcher.clearDismissed(pojo.packageName);
        if (pojo.taskId > 0) {
            HistorySearcher.clearDismissedTask(pojo.taskId);
        }

        Bundle opts = createLaunchAnimationBundle(v);
        if (pojo.taskId > 0 && RecentsBridgeClient.isAvailable()
                && RecentsBridgeClient.startTaskSplit(pojo.taskId, opts)) {
            return;
        }

        Bundle multiWindowOpts = mergeWindowingOptions(opts, WINDOWING_MODE_MULTI_WINDOW, null);
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            flags |= Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT;
        }
        if (startFallbackIntent(context, flags, multiWindowOpts)) {
            return;
        }

        Toast.makeText(context, context.getString(R.string.toast_multiwindow_error, pojo.getName()), Toast.LENGTH_SHORT).show();
    }

    private void launchPopupView(Context context, @Nullable View v) {
        HistorySearcher.clearDismissed(pojo.packageName);
        if (pojo.taskId > 0) {
            HistorySearcher.clearDismissedTask(pojo.taskId);
        }

        Rect bounds = computeDefaultPopupBounds(context);
        Bundle opts = createLaunchAnimationBundle(v);
        if (pojo.taskId > 0 && RecentsBridgeClient.isAvailable()
                && RecentsBridgeClient.startTaskPopup(pojo.taskId, bounds, opts)) {
            return;
        }

        Bundle freeformOpts = mergeWindowingOptions(opts, WINDOWING_MODE_FREEFORM, bounds);
        if (startFallbackIntent(context, Intent.FLAG_ACTIVITY_NEW_TASK, freeformOpts)) {
            return;
        }

        Toast.makeText(context, context.getString(R.string.toast_multiwindow_error, pojo.getName()), Toast.LENGTH_SHORT).show();
    }

    private boolean startFallbackIntent(Context context, int flags, @Nullable Bundle options) {
        Intent componentIntent = new Intent(Intent.ACTION_MAIN);
        componentIntent.setComponent(getClassName());
        componentIntent.addFlags(flags | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(componentIntent, options);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Component multi-window fallback failed", e);
        }

        Intent pkgIntent = context.getPackageManager().getLaunchIntentForPackage(pojo.packageName);
        if (pkgIntent != null) {
            pkgIntent.addFlags(flags);
            try {
                context.startActivity(pkgIntent, options);
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "Package multi-window fallback failed", e);
            }
        }
        return false;
    }

    @Nullable
    private View resolveLaunchAnimTarget(@Nullable View v) {
        if (v == null) {
            return null;
        }
        if (v.findViewById(R.id.banner_snapshot) != null) {
            return v;
        }
        View potentialIcon = v.findViewById(R.id.item_app_icon);
        if (potentialIcon == null) {
            potentialIcon = v.findViewById(R.id.favorite);
        }
        return potentialIcon != null ? potentialIcon : v;
    }

    @Nullable
    private Bundle createLaunchAnimationBundle(@Nullable View v) {
        View target = resolveLaunchAnimTarget(v);
        if (target == null) {
            return null;
        }
        int rawW = target.getWidth() > 0 ? target.getWidth() : target.getMeasuredWidth();
        int rawH = target.getHeight() > 0 ? target.getHeight() : target.getMeasuredHeight();
        if (rawW <= 0 || rawH <= 0) {
            return null;
        }
        int width = Math.max(1, Math.round(rawW * target.getScaleX()));
        int height = Math.max(1, Math.round(rawH * target.getScaleY()));
        return ActivityOptions.makeScaleUpAnimation(target, 0, 0, width, height).toBundle();
    }

    private static Rect computeDefaultPopupBounds(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int width = Math.max(320, Math.round(dm.widthPixels * 0.80f));
        int height = Math.max(420, Math.round(dm.heightPixels * 0.55f));
        int left = Math.max(0, (dm.widthPixels - width) / 2);
        int top = Math.max(0, (dm.heightPixels - height) / 2);
        return new Rect(left, top, left + width, top + height);
    }

    @Nullable
    private static Bundle mergeWindowingOptions(@Nullable Bundle baseOptions, int windowingMode, @Nullable Rect bounds) {
        Bundle merged = baseOptions != null ? new Bundle(baseOptions) : new Bundle();
        try {
            ActivityOptions ao = ActivityOptions.makeBasic();
            try {
                Method setMode = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                setMode.invoke(ao, windowingMode);
            } catch (Throwable ignored) {
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

            View animTarget = resolveLaunchAnimTarget(v);
            Rect sourceBounds = animTarget != null ? getViewBounds(animTarget) : null;
            Bundle opts = createLaunchAnimationBundle(v);

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
