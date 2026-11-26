package fr.neamar.kiss.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.result.AppResult;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.utils.DrawableUtils;
import fr.neamar.kiss.utils.RecentsBridgeClient;
import fr.neamar.kiss.utils.TaskSnapshotCache;
import fr.neamar.kiss.utils.Utilities;

/**
 * Circular Expressive carousel for recent apps:
 * - Unfocused entries render in KISS style along a subtle circular arc.
 * - The selected entry expands into a live snapshot banner outlined by its dominant icon color.
 * - Swiping left or long-pressing triggers a right-side curved icon context menu.
 * - Swiping right dismisses a row.
 */
public class RecentsCarouselView extends FrameLayout {

    public interface Callback {
        boolean canDismiss(Result<?> result);

        boolean includeInClearAll(Result<?> result);

        void onDismissed(List<Result<?>> results);

        void onLaunchSelected(Result<?> result, @Nullable View sourceView);

        void onKeepAliveToggled();
    }

    private enum DragMode {
        NONE,
        SCROLL_VERTICAL,
        SWIPE_CONTEXT,
        SWIPE_DISMISS
    }

    private static final float GRAB_SCALE = 0.90f;
    private static final float DISMISS_THRESHOLD_RATIO = 0.35f;
    private static final int STAGGER_CLEAR_ALL_MS = 35;
    private static final float FAST_WHEEL_ITEMS_PER_SEC = 5.5f;
    private static final long THUMBNAIL_STABILIZE_DELAY_MS = 75L;
    private static final float EDGE_OVERSCROLL_RANGE_ITEMS = 2.6f;
    private static final float MOMENTUM_OVERSHOOT_MAX_ITEMS = 0.70f;
    private static final float MOMENTUM_OVERSHOOT_BRAKE_RATE = 13.0f;
    private static final float MOMENTUM_OVERSHOOT_STOP_VEL = 1.8f;

    private static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final Interpolator DECELERATE = new DecelerateInterpolator(1.6f);
    private static final Interpolator OVERSHOOT = new OvershootInterpolator(1.1f);
    private static final Interpolator ELASTIC_SNAP = new OvershootInterpolator(1.35f);
    private static final Interpolator ACCELERATE = new AccelerateInterpolator(1.4f);

    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private final int longPressTimeoutMs;
    private final int unfocusedHeightPx;
    private final int bannerHeightPx;
    private final int itemGapPx;
    private final int horizontalMarginPx;
    private final int bannerHorizontalMarginPx;
    private final int contextShiftPx;
    private final int contextMenuButtonSizePx;
    private final int contextMenuStepPx;
    private final int maxScrollSwayPx;

    private final OverScroller scrollScroller;
    private final SparseArray<View> boundUnfocusedViews = new SparseArray<>();
    private final List<View> recycledUnfocusedViews = new ArrayList<>();
    private final Set<Integer> inFlightSnapshots = new HashSet<>();

    private View bannerView;
    private ContextMenuOverlay contextMenuLayer;
    private ImageButton btnInfo;
    private ImageButton btnUninstall;
    private ImageButton btnKeep;
    private ImageButton btnSplit;
    private ImageButton btnPopup;
    private ImageButton btnBack;
    private final List<ImageButton> visibleContextButtons = new ArrayList<>(6);

    private RecordAdapter adapter;
    private Callback callback;
    private boolean greyscaleSnapshots = true;

    private int selectedAdapterPos = -1;
    private int bannerBoundAdapterPos = -1;
    private String selectedPojoId;

    private float scrollItemPos = 0f;
    private float grabScale = 1f;
    private float horizontalSwipePx = 0f;
    private float contextMenuProgress = 0f;
    private float scrollSwayPx = 0f;
    private boolean grabbedSelected = false;
    private boolean inLayout = false;
    private float lastSelectedScreenCenterY = 0f;

    private int dismissingVisualIndex = -1;
    private float dismissingDx = 0f;
    private boolean dismissHapticFired = false;
    private boolean animatingClearAll = false;
    private boolean animatingSingleDismiss = false;

    private ValueAnimator scrollAnimator;
    private ValueAnimator grabAnimator;
    private ValueAnimator horizontalAnimator;
    private ValueAnimator swayAnimator;
    private ValueAnimator dismissAnimator;
    private ValueAnimator bannerExpandAnimator;
    private ValueAnimator shrinkAnimator;

    private DragMode dragMode = DragMode.NONE;
    private float downX;
    private float downY;
    private float lastY;
    private int downVisualIndex = -1;
    private float swipeStartX = 0f;
    private float swipeStartOffsetPx = 0f;
    private boolean longPressTriggered = false;
    private VelocityTracker velocityTracker;
    private Runnable pendingNotTouchedAction;

    private int shrinkingAdapterPos = -1;
    private float shrinkProgress = 1f;
    private float bannerExpandProgress = 1f;

    private int flingTargetVisualIdx = -1;
    private boolean suppressContextMenuOnHorizontalSnap = false;
    private float rawScrollItemPos = 0f;
    private float wheelSpeedItemsPerSec = 0f;
    private long lastVelSampleTimeMs = 0L;
    private boolean thumbnailsSuppressed = false;
    private boolean bannerBoundLightweight = false;
    private boolean transparentViewport = false;
    private boolean overshootBrakingActive = false;
    private float brakingEdge = 0f;
    private float brakingRawOvershoot = 0f;
    private float brakingVelItemsPerSec = 0f;
    private long lastBrakingTickMs = 0L;

    private final Runnable stabilizeThumbnailsRunnable = new Runnable() {
        @Override
        public void run() {
            onWheelStabilized();
        }
    };

    private final Runnable flingTick = new Runnable() {
        @Override
        public void run() {
            if (overshootBrakingActive) {
                long now = SystemClock.uptimeMillis();
                float dt = clamp((now - lastBrakingTickMs) / 1000f, 0.001f, 0.05f);
                lastBrakingTickMs = now;
                brakingVelItemsPerSec *= (float) Math.exp(-MOMENTUM_OVERSHOOT_BRAKE_RATE * dt);
                brakingRawOvershoot += brakingVelItemsPerSec * dt;
                float oldPos = scrollItemPos;
                scrollItemPos = mapMomentumOvershoot(brakingEdge, brakingRawOvershoot);
                float vel = (scrollItemPos - oldPos) / dt;
                scrollSwayPx = clamp(vel * DrawableUtils.dpToPx(getContext(), 6f), -maxScrollSwayPx, maxScrollSwayPx);
                updateWheelSpeed(oldPos, scrollItemPos);
                thumbnailsSuppressed = isWheelMovingFast();
                updateAutoSelectionFromScroll(true);

                if (Math.abs(brakingVelItemsPerSec) <= MOMENTUM_OVERSHOOT_STOP_VEL) {
                    overshootBrakingActive = false;
                    snapScrollTo(brakingEdge, true, true);
                    releaseScrollSway();
                    return;
                }
                layoutCarousel();
                postOnAnimation(this);
                return;
            }

            if (scrollScroller.computeScrollOffset()) {
                float oldPos = scrollItemPos;
                float nextPos = scrollScroller.getCurrY() / 1000f;
                int maxIdx = Math.max(0, getItemCount() - 1);
                if (flingTargetVisualIdx < 0 && (nextPos < 0f || nextPos > maxIdx)) {
                    float edge = nextPos < 0f ? 0f : maxIdx;
                    float sign = nextPos < 0f ? -1f : 1f;
                    float scrollerSpeed = Math.max(2f, scrollScroller.getCurrVelocity() / 1000f);
                    scrollScroller.abortAnimation();
                    overshootBrakingActive = true;
                    brakingEdge = edge;
                    brakingRawOvershoot = clamp(nextPos - edge, -0.15f, 0.15f);
                    brakingVelItemsPerSec = sign * Math.min(scrollerSpeed * 0.65f, 24f);
                    lastBrakingTickMs = SystemClock.uptimeMillis();
                    scrollItemPos = mapMomentumOvershoot(brakingEdge, brakingRawOvershoot);
                    float vel = (scrollItemPos - oldPos) * 60f;
                    scrollSwayPx = clamp(vel * DrawableUtils.dpToPx(getContext(), 6f), -maxScrollSwayPx, maxScrollSwayPx);
                    updateWheelSpeed(oldPos, scrollItemPos);
                    thumbnailsSuppressed = isWheelMovingFast();
                    updateAutoSelectionFromScroll(true);
                    layoutCarousel();
                    postOnAnimation(this);
                    return;
                }

                scrollItemPos = nextPos;
                float vel = (scrollItemPos - oldPos) * 60f;
                scrollSwayPx = clamp(vel * DrawableUtils.dpToPx(getContext(), 6f), -maxScrollSwayPx, maxScrollSwayPx);
                updateWheelSpeed(oldPos, scrollItemPos);
                thumbnailsSuppressed = isWheelMovingFast();
                updateAutoSelectionFromScroll(true);

                if (flingTargetVisualIdx >= 0) {
                    int targetIdx = flingTargetVisualIdx;
                    if (Math.abs(scrollItemPos - targetIdx) <= 0.22f
                            && Math.abs(scrollItemPos - targetIdx) < Math.abs(oldPos - targetIdx)) {
                        scrollScroller.abortAnimation();
                        flingTargetVisualIdx = -1;
                        snapScrollTo(targetIdx, true, true);
                        releaseScrollSway();
                        return;
                    }
                }

                layoutCarousel();
                postOnAnimation(this);
            } else {
                int targetIdx = flingTargetVisualIdx;
                flingTargetVisualIdx = -1;
                if (targetIdx >= 0) {
                    snapScrollTo(targetIdx, true, true);
                } else {
                    snapScrollToNearest(true);
                }
                releaseScrollSway();
            }
        }
    };

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (dragMode != DragMode.NONE || downVisualIndex < 0 || animatingClearAll) {
                return;
            }
            longPressTriggered = true;
            setGrabbedSelected(false);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            int targetAdapterPos = visualToAdapterPos(downVisualIndex);
            if (targetAdapterPos >= 0 && targetAdapterPos < getItemCount()) {
                if (targetAdapterPos != selectedAdapterPos) {
                    selectAdapterPosition(targetAdapterPos, true);
                }
                animateContextMenu(true);
            }
        }
    };

    public RecentsCarouselView(@NonNull Context context) {
        this(context, null);
    }

    public RecentsCarouselView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentsCarouselView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClipChildren(false);
        setClipToPadding(false);

        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity() * 3;
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout();

        unfocusedHeightPx = resolveUnfocusedRowHeight(context);
        bannerHeightPx = getResources().getDimensionPixelSize(R.dimen.banner_card_height);
        itemGapPx = DrawableUtils.dpToPx(context, 6f);
        horizontalMarginPx = DrawableUtils.dpToPx(context, 22f);
        bannerHorizontalMarginPx = DrawableUtils.dpToPx(context, 32f);
        contextShiftPx = DrawableUtils.dpToPx(context, 60f);
        contextMenuButtonSizePx = DrawableUtils.dpToPx(context, 44f);
        contextMenuStepPx = DrawableUtils.dpToPx(context, 52f);
        maxScrollSwayPx = DrawableUtils.dpToPx(context, 14f);

        scrollScroller = new OverScroller(context, DECELERATE);
        scrollScroller.setFriction(ViewConfiguration.getScrollFriction() * 2.35f);
        initContextMenuLayer(context);
    }

    private static int resolveUnfocusedRowHeight(Context context) {
        int fallback = context.getResources().getDimensionPixelSize(R.dimen.result_height);
        TypedArray ta = context.obtainStyledAttributes(new int[]{R.attr.resultHeight});
        try {
            return ta.getDimensionPixelSize(0, fallback);
        } finally {
            ta.recycle();
        }
    }

    private void initContextMenuLayer(Context context) {
        contextMenuLayer = new ContextMenuOverlay(context);
        contextMenuLayer.setElevation(DrawableUtils.dpToPx(context, 10f));
        contextMenuLayer.setVisibility(View.GONE);

        btnInfo = createArcButton(context, R.drawable.ic_action_info, R.string.menu_app_details, v -> {
            AppResult app = getSelectedAppResult();
            animateContextMenu(false);
            if (app != null) {
                app.openAppDetails(getContext());
            }
        });
        btnUninstall = createArcButton(context, R.drawable.ic_action_uninstall, R.string.menu_app_uninstall, v -> {
            AppResult app = getSelectedAppResult();
            animateContextMenu(false);
            if (app != null) {
                app.openUninstall(getContext());
            }
        });
        btnKeep = createArcButton(context, R.drawable.ic_action_keep_outline, R.string.menu_keep_alive, v -> {
            AppResult app = getSelectedAppResult();
            if (app != null) {
                boolean kept = app.toggleKeepAlive(getContext());
                updateKeepButtonVisual(kept);
                refreshAllBoundItems();
                if (callback != null) {
                    callback.onKeepAliveToggled();
                }
            }
        });
        btnSplit = createArcButton(context, R.drawable.ic_action_split, R.string.menu_split_screen, v -> {
            AppResult app = getSelectedAppResult();
            animateContextMenu(false);
            if (app != null) {
                app.openSplitScreen(getContext(), bannerView);
            }
        });
        btnPopup = createArcButton(context, R.drawable.ic_action_popup, R.string.menu_popup_view, v -> {
            AppResult app = getSelectedAppResult();
            animateContextMenu(false);
            if (app != null) {
                app.openPopupView(getContext(), bannerView);
            }
        });
        btnBack = createArcButton(context, R.drawable.ic_action_back, R.string.cd_exit_menu, v -> animateContextMenu(false));

        addView(contextMenuLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private ImageButton createArcButton(Context context, int iconRes, int contentDescRes, OnClickListener clickListener) {
        ImageButton btn = new ImageButton(context);
        btn.setBackgroundResource(R.drawable.context_arc_button_bg);
        btn.setImageResource(iconRes);
        btn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = DrawableUtils.dpToPx(context, 10f);
        btn.setPadding(pad, pad, pad, pad);
        btn.setContentDescription(context.getString(contentDescRes));
        btn.setOnClickListener(clickListener);
        contextMenuLayer.addView(btn, new LayoutParams(contextMenuButtonSizePx, contextMenuButtonSizePx));
        return btn;
    }

    public void setAdapter(@NonNull RecordAdapter adapter) {
        this.adapter = adapter;
        onDatasetChanged(true);
    }

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    public void setGreyscaleSnapshots(boolean greyscale) {
        if (this.greyscaleSnapshots != greyscale) {
            this.greyscaleSnapshots = greyscale;
            rebindBanner();
        }
    }

    public void setTransparentViewport(boolean transparentViewport) {
        this.transparentViewport = transparentViewport;
    }

    public boolean isContextMenuOpen() {
        return contextMenuProgress > 0.05f;
    }

    public void closeContextMenu() {
        if (contextMenuProgress > 0f) {
            animateContextMenu(false);
        }
    }

    public boolean isAnimating() {
        return animatingClearAll || animatingSingleDismiss;
    }

    public void resetSelection() {
        selectedPojoId = null;
        selectedAdapterPos = -1;
        bannerBoundAdapterPos = -1;
        shrinkingAdapterPos = -1;
        shrinkProgress = 1f;
        bannerExpandProgress = 1f;
        wheelSpeedItemsPerSec = 0f;
        thumbnailsSuppressed = false;
        bannerBoundLightweight = false;
        overshootBrakingActive = false;
        removeCallbacks(stabilizeThumbnailsRunnable);
        cancelAnimator(bannerExpandAnimator);
        cancelAnimator(shrinkAnimator);
        horizontalSwipePx = 0f;
        contextMenuProgress = 0f;
        dismissingVisualIndex = -1;
        dismissingDx = 0f;
        contextMenuLayer.setVisibility(View.GONE);
        clearAllItemViews();
        if (bannerView != null) {
            bannerView.setVisibility(View.GONE);
        }
        invalidateViewport();
    }

    public void onDatasetChanged(boolean resetScrollIfFresh) {
        removeCallbacks(stabilizeThumbnailsRunnable);
        cancelAnimator(bannerExpandAnimator);
        cancelAnimator(shrinkAnimator);
        shrinkingAdapterPos = -1;
        shrinkProgress = 1f;
        bannerExpandProgress = 1f;
        wheelSpeedItemsPerSec = 0f;
        thumbnailsSuppressed = false;

        int count = getItemCount();
        if (count == 0) {
            selectedAdapterPos = -1;
            selectedPojoId = null;
            horizontalSwipePx = 0f;
            contextMenuProgress = 0f;
            dismissingVisualIndex = -1;
            dismissingDx = 0f;
            contextMenuLayer.setVisibility(View.GONE);
            clearAllItemViews();
            return;
        }

        int resolvedPos = -1;
        if (!resetScrollIfFresh && selectedPojoId != null) {
            for (int i = 0; i < count; i++) {
                if (selectedPojoId.equals(adapter.getItem(i).getPojoId())) {
                    resolvedPos = i;
                    break;
                }
            }
        }
        if (resolvedPos < 0) {
            resolvedPos = Math.max(0, count - 2);
            selectedPojoId = adapter.getItem(resolvedPos).getPojoId();
            scrollItemPos = resolvedPos;
        } else {
            scrollItemPos = clamp(scrollItemPos, 0f, count - 1);
        }
        selectedAdapterPos = resolvedPos;

        clearAllItemViews();
        layoutCarousel();
        requestSnapshotsAroundSelection(true);
    }

    public void refreshAllBoundItems() {
        rebindBanner();
        for (int i = 0; i < boundUnfocusedViews.size(); i++) {
            int visualIdx = boundUnfocusedViews.keyAt(i);
            View v = boundUnfocusedViews.valueAt(i);
            int adapterPos = visualToAdapterPos(visualIdx);
            if (adapterPos >= 0 && adapterPos < getItemCount()) {
                adapter.getItem(adapterPos).display(getContext(), v, this, null);
            }
        }
    }

    public void dismissAll() {
        if (animatingClearAll || adapter == null || callback == null || adapter.getCount() == 0) {
            return;
        }
        closeContextMenu();
        int count = adapter.getCount();
        final List<Result<?>> toDismiss = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Result<?> r = adapter.getItem(i);
            if (callback.includeInClearAll(r)) {
                toDismiss.add(r);
            }
        }
        if (toDismiss.isEmpty()) {
            return;
        }

        final Set<Result<?>> toDismissSet = new HashSet<>(toDismiss);
        final List<View> viewsToSlide = new ArrayList<>();
        for (int v = count - 1; v >= 0; v--) {
            int adapterPos = visualToAdapterPos(v);
            Result<?> item = adapter.getItem(adapterPos);
            if (!toDismissSet.contains(item)) {
                continue;
            }
            View child = (adapterPos == selectedAdapterPos) ? bannerView : boundUnfocusedViews.get(v);
            if (child != null && child.getParent() == this) {
                viewsToSlide.add(child);
            }
        }

        if (viewsToSlide.isEmpty()) {
            callback.onDismissed(toDismiss);
            return;
        }

        animatingClearAll = true;
        final int[] remaining = {viewsToSlide.size()};
        final int slideOutDistance = Math.max(getWidth(), 800);
        for (int i = 0; i < viewsToSlide.size(); i++) {
            final View child = viewsToSlide.get(i);
            child.animate()
                    .translationX(child.getTranslationX() + slideOutDistance)
                    .alpha(0f)
                    .setStartDelay((long) i * STAGGER_CLEAR_ALL_MS)
                    .setDuration(190)
                    .setInterpolator(ACCELERATE)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            child.animate().setListener(null).setStartDelay(0);
                            remaining[0]--;
                            if (remaining[0] == 0) {
                                animatingClearAll = false;
                                callback.onDismissed(toDismiss);
                            }
                        }
                    });
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int height = getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        setMeasuredDimension(width, height);

        if (contextMenuLayer != null && contextMenuLayer.getVisibility() != GONE) {
            int wSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
            int hSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
            contextMenuLayer.measure(wSpec, hSpec);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        inLayout = true;
        try {
            layoutCarousel();
        } finally {
            inLayout = false;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            layoutCarousel();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        pendingNotTouchedAction = null;
        overshootBrakingActive = false;
        removeCallbacks(flingTick);
        removeCallbacks(stabilizeThumbnailsRunnable);
        removeCallbacks(longPressRunnable);
        cancelAnimator(scrollAnimator);
        cancelAnimator(grabAnimator);
        cancelAnimator(horizontalAnimator);
        cancelAnimator(swayAnimator);
        cancelAnimator(dismissAnimator);
        cancelAnimator(bannerExpandAnimator);
        cancelAnimator(shrinkAnimator);
        inFlightSnapshots.clear();
        recycleVelocityTracker();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (animatingClearAll || animatingSingleDismiss || getItemCount() == 0) {
            return true;
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (isContextMenuOpen() && isTouchInsideContextMenu(ev.getX(), ev.getY())) {
                return false;
            }
            return true;
        }
        return dragMode != DragMode.NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (animatingClearAll || animatingSingleDismiss || getItemCount() == 0) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handleActionDown(event);
                return true;

            case MotionEvent.ACTION_MOVE:
                handleActionMove(event);
                return true;

            case MotionEvent.ACTION_UP:
                handleActionUp(event);
                return true;

            case MotionEvent.ACTION_CANCEL:
                handleActionCancel();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void handleActionDown(MotionEvent event) {
        removeCallbacks(flingTick);
        removeCallbacks(stabilizeThumbnailsRunnable);
        removeCallbacks(longPressRunnable);
        if (!scrollScroller.isFinished()) {
            scrollScroller.abortAnimation();
        }
        cancelAnimator(scrollAnimator);
        cancelAnimator(swayAnimator);
        cancelAnimator(horizontalAnimator);
        if (!animatingSingleDismiss) {
            cancelAnimator(dismissAnimator);
            dismissingVisualIndex = -1;
            dismissingDx = 0f;
        }

        downX = event.getX();
        downY = event.getY();
        lastY = downY;
        dragMode = DragMode.NONE;
        longPressTriggered = false;
        dismissHapticFired = false;
        flingTargetVisualIdx = -1;
        overshootBrakingActive = false;
        suppressContextMenuOnHorizontalSnap = false;
        rawScrollItemPos = unmapOverscroll(scrollItemPos, Math.max(0, getItemCount() - 1));
        wheelSpeedItemsPerSec = 0f;
        lastVelSampleTimeMs = SystemClock.uptimeMillis();
        thumbnailsSuppressed = false;
        swipeStartX = downX;
        swipeStartOffsetPx = horizontalSwipePx;

        recycleVelocityTracker();
        velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);

        downVisualIndex = hitTestVisualIndex(downX, downY);
        int selectedVisualIdx = adapterToVisualPos(selectedAdapterPos);
        if (!isContextMenuOpen() && downVisualIndex >= 0 && downVisualIndex == selectedVisualIdx) {
            setGrabbedSelected(true);
        }

        if (downVisualIndex >= 0 && !isContextMenuOpen()) {
            postDelayed(longPressRunnable, longPressTimeoutMs);
        }
    }

    private void handleActionMove(MotionEvent event) {
        if (velocityTracker != null) {
            velocityTracker.addMovement(event);
        }
        float x = event.getX();
        float y = event.getY();
        float dx = x - downX;
        float dy = y - downY;

        if (longPressTriggered) {
            return;
        }

        if (dragMode == DragMode.NONE) {
            if (Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
                removeCallbacks(longPressRunnable);
                setGrabbedSelected(false);
                if (isContextMenuOpen() || Math.abs(horizontalSwipePx) > 0.5f) {
                    animateHorizontalTo(0f);
                }
                dragMode = DragMode.SCROLL_VERTICAL;
            } else if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                removeCallbacks(longPressRunnable);
                setGrabbedSelected(false);
                swipeStartX = downX + (dx >= 0f ? touchSlop : -touchSlop);
                swipeStartOffsetPx = horizontalSwipePx;
                if (dx < 0f) {
                    if (!isContextMenuOpen() && downVisualIndex >= 0) {
                        int targetAdapterPos = visualToAdapterPos(downVisualIndex);
                        if (targetAdapterPos >= 0 && targetAdapterPos != selectedAdapterPos) {
                            selectAdapterPosition(targetAdapterPos, true);
                        }
                    }
                    dragMode = DragMode.SWIPE_CONTEXT;
                } else if (isContextMenuOpen()) {
                    dragMode = DragMode.SWIPE_CONTEXT;
                } else {
                    if (downVisualIndex >= 0) {
                        int targetAdapterPos = visualToAdapterPos(downVisualIndex);
                        if (targetAdapterPos >= 0 && callback != null
                                && callback.canDismiss(adapter.getItem(targetAdapterPos))) {
                            dismissingVisualIndex = downVisualIndex;
                        }
                    }
                    dragMode = DragMode.SWIPE_DISMISS;
                }
            }
        }

        if (dragMode == DragMode.SCROLL_VERTICAL) {
            float stepDy = y - lastY;
            float pxPerItem = unfocusedHeightPx + itemGapPx;
            float deltaItems = -stepDy / Math.max(1f, pxPerItem);
            int maxIdx = Math.max(0, getItemCount() - 1);
            float oldPos = scrollItemPos;
            rawScrollItemPos += deltaItems;
            scrollItemPos = mapOverscroll(rawScrollItemPos, maxIdx);
            scrollSwayPx = clamp(-stepDy * 0.35f, -maxScrollSwayPx, maxScrollSwayPx);
            updateWheelSpeed(oldPos, scrollItemPos);
            thumbnailsSuppressed = isWheelMovingFast();
            scheduleThumbnailStabilize();
            updateAutoSelectionFromScroll(true);
            layoutCarousel();
        } else if (dragMode == DragMode.SWIPE_CONTEXT || dragMode == DragMode.SWIPE_DISMISS) {
            float rawSwipe = swipeStartOffsetPx + (x - swipeStartX);
            horizontalSwipePx = rawSwipe;
            if (dragMode == DragMode.SWIPE_CONTEXT) {
                contextMenuProgress = clamp(-horizontalSwipePx / Math.max(1f, contextShiftPx), 0f, 1f);
            } else {
                contextMenuProgress = 0f;
            }
            if (dragMode == DragMode.SWIPE_DISMISS && dismissingVisualIndex >= 0) {
                dismissingDx = Math.max(0f, rawSwipe);
                boolean over = dismissingDx > getWidth() * DISMISS_THRESHOLD_RATIO;
                if (over != dismissHapticFired) {
                    dismissHapticFired = over;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            }
            updateContextMenuVisibilityAndLayout();
            layoutCarousel();
        }

        lastY = y;
    }

    private void handleActionUp(MotionEvent event) {
        removeCallbacks(longPressRunnable);
        setGrabbedSelected(false);

        if (longPressTriggered) {
            resetTouchState();
            return;
        }

        float vx = 0f;
        float vy = 0f;
        if (velocityTracker != null) {
            velocityTracker.addMovement(event);
            velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
            vx = velocityTracker.getXVelocity();
            vy = velocityTracker.getYVelocity();
        }

        if (dragMode == DragMode.NONE) {
            if (isContextMenuOpen()) {
                animateHorizontalTo(0f);
            } else if (downVisualIndex >= 0) {
                int tappedAdapterPos = visualToAdapterPos(downVisualIndex);
                if (tappedAdapterPos >= 0 && tappedAdapterPos < getItemCount()) {
                    if (tappedAdapterPos == selectedAdapterPos) {
                        snapScrollToNearest(false);
                        if (callback != null) {
                            callback.onLaunchSelected(adapter.getItem(tappedAdapterPos), bannerView);
                        }
                    } else {
                        selectAdapterPosition(tappedAdapterPos, true);
                    }
                }
            } else {
                snapScrollToNearest(true);
            }
            if (Math.abs(horizontalSwipePx) > 0.5f && !isContextMenuOpen()) {
                animateHorizontalTo(0f);
            }
        } else if (dragMode == DragMode.SCROLL_VERTICAL) {
            int maxIdx = Math.max(0, getItemCount() - 1);
            if (scrollItemPos < 0f || scrollItemPos > maxIdx) {
                snapScrollTo(clamp(scrollItemPos, 0f, maxIdx), true, true);
                releaseScrollSway();
            } else if (Math.abs(vy) >= minFlingVelocity) {
                float pxPerItem = unfocusedHeightPx + itemGapPx;
                int startMilli = Math.round(scrollItemPos * 1000f);
                int velMilli = Math.round((-vy / Math.max(1f, pxPerItem)) * 880f);
                scrollScroller.fling(0, startMilli, 0, velMilli, 0, 0, -100000, maxIdx * 1000 + 100000, 0, 0);
                int rawFinalY = scrollScroller.getFinalY();
                if (rawFinalY < 0 || rawFinalY > maxIdx * 1000) {
                    flingTargetVisualIdx = -1;
                    overshootBrakingActive = false;
                    postOnAnimation(flingTick);
                } else {
                    int currentIdx = Math.round(clamp(scrollItemPos, 0f, maxIdx));
                    int targetIdx = Math.round(clamp(rawFinalY / 1000f, 0f, maxIdx));
                    if (targetIdx == currentIdx) {
                        targetIdx = (int) clamp(currentIdx + (vy < 0f ? 1f : -1f), 0f, maxIdx);
                    }
                    if (Math.abs(targetIdx - scrollItemPos) <= 1.15f) {
                        scrollScroller.abortAnimation();
                        flingTargetVisualIdx = -1;
                        snapScrollTo(targetIdx, true, true);
                        releaseScrollSway();
                    } else {
                        int boundY = targetIdx * 1000;
                        int minBound = Math.min(startMilli, boundY);
                        int maxBound = Math.max(startMilli, boundY);
                        scrollScroller.abortAnimation();
                        scrollScroller.fling(0, startMilli, 0, velMilli, 0, 0, minBound, maxBound, 0, 0);
                        flingTargetVisualIdx = targetIdx;
                        postOnAnimation(flingTick);
                    }
                }
            } else {
                snapScrollToNearest(true);
                releaseScrollSway();
            }
        } else if (dragMode == DragMode.SWIPE_CONTEXT) {
            boolean shouldOpen = horizontalSwipePx < -contextShiftPx * 0.35f || vx < -minFlingVelocity;
            if (swipeStartOffsetPx < -contextShiftPx * 0.5f
                    && (vx > minFlingVelocity || horizontalSwipePx > -contextShiftPx * 0.65f)) {
                shouldOpen = false;
            }
            if (shouldOpen) {
                bindContextButtons();
            }
            animateHorizontalTo(shouldOpen ? -contextShiftPx : 0f);
        } else if (dragMode == DragMode.SWIPE_DISMISS) {
            if (dismissingVisualIndex >= 0) {
                boolean shouldDismiss = dismissingDx > getWidth() * DISMISS_THRESHOLD_RATIO
                        || (vx > minFlingVelocity && dismissingDx > touchSlop * 2f);
                if (shouldDismiss) {
                    completeSingleDismiss(dismissingVisualIndex);
                    animateHorizontalTo(0f);
                } else {
                    animateHorizontalTo(0f);
                }
            } else {
                animateHorizontalTo(0f);
            }
        }

        resetTouchState();
    }

    private void handleActionCancel() {
        removeCallbacks(longPressRunnable);
        setGrabbedSelected(false);
        if (dragMode == DragMode.SCROLL_VERTICAL) {
            snapScrollToNearest(true);
            releaseScrollSway();
        } else if (dragMode == DragMode.SWIPE_CONTEXT) {
            boolean shouldOpen = horizontalSwipePx < -contextShiftPx * 0.5f;
            if (shouldOpen) {
                bindContextButtons();
            }
            animateHorizontalTo(shouldOpen ? -contextShiftPx : 0f);
        } else if (dragMode == DragMode.SWIPE_DISMISS) {
            animateHorizontalTo(0f);
        }
        resetTouchState();
    }

    private void resetTouchState() {
        dragMode = DragMode.NONE;
        downVisualIndex = -1;
        longPressTriggered = false;
        recycleVelocityTracker();
        flushPendingNotTouchedAction();
    }

    public void runWhenNotTouched(@NonNull Runnable action) {
        if (dragMode == DragMode.NONE && !grabbedSelected && !isAnimating()) {
            action.run();
        } else {
            pendingNotTouchedAction = action;
        }
    }

    public void cancelRunWhenNotTouched() {
        pendingNotTouchedAction = null;
    }

    private void flushPendingNotTouchedAction() {
        if (pendingNotTouchedAction != null && dragMode == DragMode.NONE && !grabbedSelected && !isAnimating()) {
            Runnable action = pendingNotTouchedAction;
            pendingNotTouchedAction = null;
            post(action);
        }
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    public void selectAdapterPosition(int adapterPos, boolean animate) {
        int count = getItemCount();
        if (adapterPos < 0 || adapterPos >= count) {
            return;
        }
        int targetVisual = adapterToVisualPos(adapterPos);
        if (adapterPos == selectedAdapterPos) {
            if (animate) {
                snapScrollTo(targetVisual, true, false);
            }
            return;
        }

        int previousPos = selectedAdapterPos;
        selectedAdapterPos = adapterPos;
        bannerBoundAdapterPos = -1;
        selectedPojoId = adapter.getItem(adapterPos).getPojoId();

        if (animate) {
            startTransitionAnimations(previousPos);
            snapScrollTo(targetVisual, true, false);
        } else {
            scrollItemPos = targetVisual;
            layoutCarousel();
        }

        if (isContextMenuOpen()) {
            bindContextButtons();
        }
        if (!thumbnailsSuppressed) {
            requestSnapshotsAroundSelection(!animate);
        }
    }

    private void updateAutoSelectionFromScroll(boolean playHaptic) {
        int count = getItemCount();
        if (count == 0) {
            return;
        }
        int centerVisual = Math.round(clamp(scrollItemPos, 0f, count - 1));
        int centerAdapterPos = visualToAdapterPos(centerVisual);
        if (centerAdapterPos >= 0 && centerAdapterPos != selectedAdapterPos) {
            int previousPos = selectedAdapterPos;
            selectedAdapterPos = centerAdapterPos;
            bannerBoundAdapterPos = -1;
            selectedPojoId = adapter.getItem(centerAdapterPos).getPojoId();
            if (playHaptic) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            startTransitionAnimations(previousPos);
            if (isContextMenuOpen()) {
                bindContextButtons();
            }
            if (!thumbnailsSuppressed) {
                requestSnapshotsAroundSelection(false);
            }
        }
    }

    private void startTransitionAnimations(int previousAdapterPos) {
        if (previousAdapterPos >= 0 && previousAdapterPos != selectedAdapterPos) {
            shrinkingAdapterPos = previousAdapterPos;
            cancelAnimator(shrinkAnimator);
            shrinkProgress = 0f;
            shrinkAnimator = ValueAnimator.ofFloat(0f, 1f);
            shrinkAnimator.setDuration(165);
            shrinkAnimator.setInterpolator(DECELERATE);
            shrinkAnimator.addUpdateListener(anim -> {
                shrinkProgress = (float) anim.getAnimatedValue();
                layoutCarousel();
            });
            shrinkAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    shrinkingAdapterPos = -1;
                    shrinkProgress = 1f;
                    layoutCarousel();
                }
            });
            shrinkAnimator.start();
        }

        cancelAnimator(bannerExpandAnimator);
        bannerExpandProgress = 0f;
        bannerExpandAnimator = ValueAnimator.ofFloat(0f, 1f);
        bannerExpandAnimator.setDuration(150);
        bannerExpandAnimator.setInterpolator(DECELERATE);
        bannerExpandAnimator.addUpdateListener(anim -> {
            bannerExpandProgress = (float) anim.getAnimatedValue();
            layoutCarousel();
        });
        bannerExpandAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                bannerExpandProgress = 1f;
                layoutCarousel();
            }
        });
        bannerExpandAnimator.start();
    }

    private void updateWheelSpeed(float prevPos, float newPos) {
        long now = SystemClock.uptimeMillis();
        if (lastVelSampleTimeMs > 0L) {
            long dtMs = Math.max(8L, Math.min(120L, now - lastVelSampleTimeMs));
            float instSpeed = Math.abs(newPos - prevPos) * (1000f / dtMs);
            wheelSpeedItemsPerSec = wheelSpeedItemsPerSec * 0.55f + instSpeed * 0.45f;
        }
        lastVelSampleTimeMs = now;
    }

    private boolean isWheelMovingFast() {
        return flingTargetVisualIdx >= 0 || wheelSpeedItemsPerSec >= FAST_WHEEL_ITEMS_PER_SEC;
    }

    private void scheduleThumbnailStabilize() {
        removeCallbacks(stabilizeThumbnailsRunnable);
        postDelayed(stabilizeThumbnailsRunnable, THUMBNAIL_STABILIZE_DELAY_MS);
    }

    private void onWheelStabilized() {
        removeCallbacks(stabilizeThumbnailsRunnable);
        wheelSpeedItemsPerSec = 0f;
        thumbnailsSuppressed = false;
        if (getItemCount() > 0 && selectedAdapterPos >= 0) {
            if (bannerBoundLightweight) {
                rebindBanner();
            }
            requestSnapshotsAroundSelection(true);
            layoutCarousel();
        }
    }

    private void snapScrollToNearest(boolean elastic) {
        int count = getItemCount();
        if (count == 0) {
            return;
        }
        int target = Math.round(clamp(scrollItemPos, 0f, count - 1));
        snapScrollTo(target, elastic, true);
    }

    private void snapScrollTo(float targetVisualPos, boolean elastic, final boolean updateAutoSelect) {
        overshootBrakingActive = false;
        if (Math.abs(scrollItemPos - targetVisualPos) < 0.002f) {
            scrollItemPos = targetVisualPos;
            if (updateAutoSelect) {
                updateAutoSelectionFromScroll(false);
            }
            onWheelStabilized();
            layoutCarousel();
            return;
        }
        boolean isEdgeSpring = elastic && (scrollItemPos < 0f || scrollItemPos > Math.max(0, getItemCount() - 1));
        cancelAnimator(scrollAnimator);
        scrollAnimator = ValueAnimator.ofFloat(scrollItemPos, targetVisualPos);
        scrollAnimator.setDuration(isEdgeSpring ? 205 : (elastic ? 155 : 210));
        scrollAnimator.setInterpolator(isEdgeSpring ? ELASTIC_SNAP : (elastic ? OVERSHOOT : FAST_OUT_SLOW_IN));
        scrollAnimator.addUpdateListener(anim -> {
            scrollItemPos = (float) anim.getAnimatedValue();
            if (updateAutoSelect) {
                updateAutoSelectionFromScroll(true);
            }
            layoutCarousel();
        });
        scrollAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                scrollItemPos = targetVisualPos;
                if (updateAutoSelect) {
                    updateAutoSelectionFromScroll(false);
                }
                onWheelStabilized();
                layoutCarousel();
            }
        });
        scrollAnimator.start();
    }

    private void setGrabbedSelected(boolean grabbed) {
        if (grabbedSelected == grabbed) {
            return;
        }
        grabbedSelected = grabbed;
        cancelAnimator(grabAnimator);
        grabAnimator = ValueAnimator.ofFloat(grabScale, grabbed ? GRAB_SCALE : 1.0f);
        grabAnimator.setDuration(grabbed ? 90 : 170);
        grabAnimator.setInterpolator(grabbed ? DECELERATE : OVERSHOOT);
        grabAnimator.addUpdateListener(anim -> {
            grabScale = (float) anim.getAnimatedValue();
            layoutCarousel();
        });
        grabAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                flushPendingNotTouchedAction();
            }
        });
        grabAnimator.start();
    }

    private void releaseScrollSway() {
        if (Math.abs(scrollSwayPx) < 0.5f) {
            scrollSwayPx = 0f;
            layoutCarousel();
            return;
        }
        cancelAnimator(swayAnimator);
        swayAnimator = ValueAnimator.ofFloat(scrollSwayPx, 0f);
        swayAnimator.setDuration(200);
        swayAnimator.setInterpolator(OVERSHOOT);
        swayAnimator.addUpdateListener(anim -> {
            scrollSwayPx = (float) anim.getAnimatedValue();
            layoutCarousel();
        });
        swayAnimator.start();
    }

    private void animateContextMenu(boolean open) {
        if (open) {
            bindContextButtons();
        }
        animateHorizontalTo(open ? -contextShiftPx : 0f);
    }

    private void animateHorizontalTo(final float targetPx) {
        cancelAnimator(horizontalAnimator);
        suppressContextMenuOnHorizontalSnap = (targetPx == 0f && horizontalSwipePx >= 0f);
        horizontalAnimator = ValueAnimator.ofFloat(horizontalSwipePx, targetPx);
        horizontalAnimator.setDuration(195);
        horizontalAnimator.setInterpolator(ELASTIC_SNAP);
        horizontalAnimator.addUpdateListener(anim -> {
            horizontalSwipePx = (float) anim.getAnimatedValue();
            contextMenuProgress = suppressContextMenuOnHorizontalSnap
                    ? 0f
                    : clamp(-horizontalSwipePx / Math.max(1f, contextShiftPx), 0f, 1f);
            if (!animatingSingleDismiss && dismissingVisualIndex >= 0) {
                dismissingDx = Math.max(0f, horizontalSwipePx);
            }
            updateContextMenuVisibilityAndLayout();
            layoutCarousel();
        });
        horizontalAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                suppressContextMenuOnHorizontalSnap = false;
                horizontalSwipePx = targetPx;
                contextMenuProgress = clamp(-horizontalSwipePx / Math.max(1f, contextShiftPx), 0f, 1f);
                if (!animatingSingleDismiss) {
                    dismissingVisualIndex = -1;
                    dismissingDx = 0f;
                }
                updateContextMenuVisibilityAndLayout();
                layoutCarousel();
            }
        });
        horizontalAnimator.start();
    }

    private void completeSingleDismiss(final int visualIndex) {
        final int adapterPos = visualToAdapterPos(visualIndex);
        if (adapterPos < 0 || adapterPos >= getItemCount()) {
            dismissingVisualIndex = -1;
            dismissingDx = 0f;
            return;
        }
        final Result<?> dismissedResult = adapter.getItem(adapterPos);
        animatingSingleDismiss = true;
        cancelAnimator(dismissAnimator);
        float targetX = Math.max(getWidth(), 800);
        dismissAnimator = ValueAnimator.ofFloat(dismissingDx, targetX);
        dismissAnimator.setDuration(170);
        dismissAnimator.setInterpolator(ACCELERATE);
        dismissAnimator.addUpdateListener(anim -> {
            dismissingDx = (float) anim.getAnimatedValue();
            layoutCarousel();
        });
        dismissAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animatingSingleDismiss = false;
                dismissingVisualIndex = -1;
                dismissingDx = 0f;
                if (dismissedResult.getPojo() instanceof AppPojo) {
                    TaskSnapshotCache.invalidate(((AppPojo) dismissedResult.getPojo()).taskId);
                }
                List<Result<?>> list = new ArrayList<>(1);
                list.add(dismissedResult);
                if (callback != null) {
                    callback.onDismissed(list);
                }
            }
        });
        dismissAnimator.start();
    }

    private void layoutCarousel() {
        int width = getWidth();
        int height = getHeight();
        int count = getItemCount();
        if (width <= 0 || height <= 0 || count == 0) {
            return;
        }

        int unfocusedWidth = Math.max(1, width - 2 * horizontalMarginPx);
        int bannerWidth = Math.max(1, width - 2 * bannerHorizontalMarginPx);
        float focalY = height * 0.52f;
        float arcRadius = Math.max(height * 0.95f, DrawableUtils.dpToPx(getContext(), 520f));
        float stepPx = unfocusedHeightPx + itemGapPx;
        float grabInset = bannerHeightPx * 0.5f * (1f - grabScale);
        float extraHalf = Math.max(0f, (bannerHeightPx - unfocusedHeightPx) * 0.5f - grabInset);

        int selectedVisualIdx = adapterToVisualPos(selectedAdapterPos);
        int shrinkingVisualIdx = adapterToVisualPos(shrinkingAdapterPos);

        // Track off-screen views to detach & recycle cleanly
        for (int i = boundUnfocusedViews.size() - 1; i >= 0; i--) {
            int v = boundUnfocusedViews.keyAt(i);
            float centerY = computeSlotCenterY(v, focalY, stepPx, extraHalf);
            if (v == selectedVisualIdx || centerY < -bannerHeightPx || centerY > height + bannerHeightPx) {
                View stale = boundUnfocusedViews.valueAt(i);
                boundUnfocusedViews.removeAt(i);
                recycleUnfocusedView(stale);
            }
        }

        float selectedScreenCenterY = focalY;
        float horizontalRubberRange = Math.max(160f, width * 0.75f);
        for (int v = 0; v < count; v++) {
            float centerY = computeSlotCenterY(v, focalY, stepPx, extraHalf);
            boolean isSelected = (v == selectedVisualIdx);
            if (isSelected) {
                selectedScreenCenterY = centerY;
            }
            if (centerY < -bannerHeightPx || centerY > height + bannerHeightPx) {
                if (isSelected && bannerView != null) {
                    bannerView.setVisibility(View.INVISIBLE);
                }
                continue;
            }

            View child;
            int childLeft;
            int childWidth;
            int childHeight;
            if (isSelected) {
                child = obtainAndBindBanner(visualToAdapterPos(v));
                child.setVisibility(View.VISIBLE);
                childLeft = bannerHorizontalMarginPx;
                childWidth = bannerWidth;
                childHeight = bannerHeightPx;
            } else {
                child = obtainAndBindUnfocused(v, visualToAdapterPos(v));
                childLeft = horizontalMarginPx;
                childWidth = unfocusedWidth;
                childHeight = unfocusedHeightPx;
            }

            layoutChildFrame(child, childLeft, Math.round(centerY - childHeight * 0.5f), childWidth, childHeight);

            float dy = centerY - focalY;
            float normDist = Math.min(1.4f, Math.abs(dy) / Math.max(1f, height * 0.48f));
            double underSqrt = Math.max(0.0, (double) arcRadius * arcRadius - (double) dy * dy);
            float arcOffset = (float) (arcRadius - Math.sqrt(underSqrt)) * 1.65f;
            arcOffset = Math.min(arcOffset, width * 0.36f);

            boolean isDismissTarget = (v == dismissingVisualIndex);
            float dismissAlpha = isDismissTarget
                    ? Math.max(0f, 1f - dismissingDx / (Math.max(1f, width) * 0.8f))
                    : 1f;

            float horizTx;
            if (isDismissTarget && (animatingSingleDismiss || horizontalSwipePx > 0f)) {
                horizTx = dismissingDx;
            } else if (horizontalSwipePx < 0f) {
                float leftPull;
                if (-horizontalSwipePx <= contextShiftPx) {
                    leftPull = horizontalSwipePx;
                } else {
                    float over = -horizontalSwipePx - contextShiftPx;
                    leftPull = -contextShiftPx - rubberBand(over, horizontalRubberRange);
                }
                if (isSelected) {
                    horizTx = leftPull;
                } else {
                    float contextFalloff = Math.max(0.30f, 1.0f - normDist * 0.50f);
                    horizTx = leftPull * 0.85f * contextFalloff;
                }
            } else if (horizontalSwipePx > 0f) {
                float rightPull;
                if (horizontalSwipePx <= contextShiftPx) {
                    rightPull = horizontalSwipePx;
                } else {
                    float over = horizontalSwipePx - contextShiftPx;
                    rightPull = contextShiftPx + rubberBand(over, horizontalRubberRange);
                }
                float swayScale = (dismissingVisualIndex >= 0) ? 0.65f : 0.85f;
                if (isSelected) {
                    horizTx = (dismissingVisualIndex >= 0) ? rightPull * 0.65f : rightPull;
                } else {
                    float falloff = Math.max(0.30f, 1.0f - normDist * 0.50f);
                    horizTx = rightPull * swayScale * falloff;
                }
            } else {
                horizTx = 0f;
            }

            if (isSelected) {
                float swayTx = scrollSwayPx * 0.45f;
                float expandScale = 0.88f + 0.12f * clamp(bannerExpandProgress, 0f, 1f);
                float totalScale = grabScale * expandScale;
                child.setPivotX(0f);
                child.setPivotY(childHeight * 0.5f);
                child.setScaleX(totalScale);
                child.setScaleY(totalScale);
                child.setAlpha(dismissAlpha);
                child.setTranslationX(horizTx + swayTx);
                child.setRotation(clamp(scrollSwayPx * 0.06f, -1.2f, 1.2f));
            } else {
                float scale = Math.max(0.78f, 0.95f - normDist * 0.14f);
                if (v == shrinkingVisualIdx && shrinkProgress < 1f) {
                    scale *= (1f + 0.28f * (1f - clamp(shrinkProgress, 0f, 1f)));
                }
                float baseAlpha = Math.max(0.55f, 1.0f - normDist * 0.28f);
                float swayTx = scrollSwayPx * (0.65f + normDist * 0.35f);
                child.setPivotX(0f);
                child.setPivotY(childHeight * 0.5f);
                child.setScaleX(scale);
                child.setScaleY(scale);
                child.setAlpha(baseAlpha * dismissAlpha);
                child.setTranslationX(arcOffset + horizTx + swayTx);
                child.setRotation(clamp(scrollSwayPx * 0.08f, -1.4f, 1.4f));
            }
        }

        lastSelectedScreenCenterY = selectedScreenCenterY;
        if (contextMenuLayer.getVisibility() != View.GONE) {
            layoutChildFrame(contextMenuLayer, 0, 0, width, height);
            layoutContextMenuButtons(selectedScreenCenterY);
        }
        invalidateViewport();
    }

    private void updateContextMenuVisibilityAndLayout() {
        if (contextMenuProgress > 0.01f) {
            if (contextMenuLayer.getVisibility() != View.VISIBLE) {
                bindContextButtons();
                contextMenuLayer.setVisibility(View.VISIBLE);
            }
        } else {
            if (contextMenuLayer.getVisibility() != View.GONE) {
                contextMenuLayer.setVisibility(View.GONE);
                invalidateViewport();
            }
        }
    }

    private void bindContextButtons() {
        AppResult app = getSelectedAppResult();
        visibleContextButtons.clear();

        if (app != null && !app.getPojo().isDisabled()) {
            btnInfo.setVisibility(View.VISIBLE);
            visibleContextButtons.add(btnInfo);
        } else {
            btnInfo.setVisibility(View.GONE);
        }

        if (app != null && app.canUninstall(getContext())) {
            btnUninstall.setVisibility(View.VISIBLE);
            visibleContextButtons.add(btnUninstall);
        } else {
            btnUninstall.setVisibility(View.GONE);
        }

        if (app != null && app.canKeepAlive()) {
            btnKeep.setVisibility(View.VISIBLE);
            updateKeepButtonVisual(app.isKept(getContext()));
            visibleContextButtons.add(btnKeep);
        } else {
            btnKeep.setVisibility(View.GONE);
        }

        if (app != null && !app.getPojo().isDisabled()) {
            btnSplit.setVisibility(View.VISIBLE);
            btnPopup.setVisibility(View.VISIBLE);
            visibleContextButtons.add(btnSplit);
            visibleContextButtons.add(btnPopup);
        } else {
            btnSplit.setVisibility(View.GONE);
            btnPopup.setVisibility(View.GONE);
        }

        btnBack.setVisibility(View.VISIBLE);
        visibleContextButtons.add(btnBack);
    }

    private void updateKeepButtonVisual(boolean kept) {
        if (kept) {
            btnKeep.setImageResource(R.drawable.ic_action_keep_filled);
            btnKeep.setColorFilter(0xFFFF5277);
            btnKeep.setContentDescription(getContext().getString(R.string.menu_keep_alive_off));
        } else {
            btnKeep.setImageResource(R.drawable.ic_action_keep_outline);
            btnKeep.clearColorFilter();
            btnKeep.setContentDescription(getContext().getString(R.string.menu_keep_alive));
        }
    }

    private void layoutContextMenuButtons(float selectedScreenCenterY) {
        if (contextMenuProgress <= 0.01f || visibleContextButtons.isEmpty()) {
            return;
        }
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        int m = visibleContextButtons.size();
        float totalSpan = (m - 1) * contextMenuStepPx;
        float halfSpan = totalSpan * 0.5f + contextMenuButtonSizePx * 0.5f;
        float minCenterY = halfSpan + DrawableUtils.dpToPx(getContext(), 12f);
        float maxCenterY = height - halfSpan - DrawableUtils.dpToPx(getContext(), 12f);
        float arcCenterY = clamp(selectedScreenCenterY, Math.min(minCenterY, height * 0.5f), Math.max(minCenterY, maxCenterY));

        float baseRightMargin = DrawableUtils.dpToPx(getContext(), 14f);
        float arcBowPx = DrawableUtils.dpToPx(getContext(), 16f);
        float halfDenom = Math.max(1f, (m - 1) * 0.5f);

        for (int i = 0; i < m; i++) {
            ImageButton btn = visibleContextButtons.get(i);
            float offsetIdx = i - (m - 1) * 0.5f;
            float norm = offsetIdx / halfDenom;
            float btnCenterY = arcCenterY + offsetIdx * contextMenuStepPx;
            float rightInset = baseRightMargin + arcBowPx * (norm * norm);
            int left = Math.round(width - rightInset - contextMenuButtonSizePx);
            int top = Math.round(btnCenterY - contextMenuButtonSizePx * 0.5f);

            layoutChildFrame(btn, left, top, contextMenuButtonSizePx, contextMenuButtonSizePx);

            float stagger = i * 0.045f;
            float localP = clamp((contextMenuProgress - stagger) / (1f - stagger), 0f, 1f);
            btn.setAlpha(localP);
            float scale = 0.65f + 0.35f * localP;
            btn.setScaleX(scale);
            btn.setScaleY(scale);
            btn.setTranslationX((1f - localP) * DrawableUtils.dpToPx(getContext(), 28f));
        }
    }

    private View obtainAndBindBanner(int adapterPos) {
        if (bannerView == null) {
            bannerView = LayoutInflater.from(getContext()).inflate(R.layout.item_app_banner, this, false);
            int idx = contextMenuLayer != null && contextMenuLayer.getParent() == this
                    ? Math.max(0, getChildCount() - 1) : -1;
            attachChild(bannerView, idx);
            bannerBoundAdapterPos = -1;
        } else if (bannerView.getParent() != this) {
            int idx = contextMenuLayer != null && contextMenuLayer.getParent() == this
                    ? Math.max(0, getChildCount() - 1) : -1;
            attachChild(bannerView, idx);
            bannerBoundAdapterPos = -1;
        }
        if (bannerBoundAdapterPos != adapterPos || (bannerBoundLightweight && !thumbnailsSuppressed)) {
            bindBannerAt(adapterPos);
        }
        return bannerView;
    }

    private void rebindBanner() {
        if (bannerView != null && selectedAdapterPos >= 0 && selectedAdapterPos < getItemCount()) {
            bindBannerAt(selectedAdapterPos);
        }
    }

    private void bindBannerAt(int adapterPos) {
        if (bannerView == null || adapterPos < 0 || adapterPos >= getItemCount()) {
            return;
        }
        bannerBoundAdapterPos = adapterPos;
        boolean skipHeavy = thumbnailsSuppressed;
        bannerBoundLightweight = skipHeavy;
        Result<?> result = adapter.getItem(adapterPos);
        if (result instanceof AppResult) {
            AppResult appResult = (AppResult) result;
            Bitmap cachedSnapshot = skipHeavy ? null : TaskSnapshotCache.get(appResult.getPojo().taskId);
            appResult.displayBanner(getContext(), bannerView, cachedSnapshot, greyscaleSnapshots, skipHeavy);
        }
    }

    private View obtainAndBindUnfocused(int visualIdx, int adapterPos) {
        View view = boundUnfocusedViews.get(visualIdx);
        if (view != null) {
            return view;
        }
        View convertView = recycledUnfocusedViews.isEmpty()
                ? null
                : recycledUnfocusedViews.remove(recycledUnfocusedViews.size() - 1);
        View bound = adapter.getItem(adapterPos).display(getContext(), convertView, this, null);
        if (bound.getParent() != this) {
            attachChild(bound, 0);
        }
        bound.setVisibility(View.VISIBLE);
        boundUnfocusedViews.put(visualIdx, bound);
        return bound;
    }

    private void attachChild(View child, int index) {
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        if (lp == null) {
            lp = generateDefaultLayoutParams();
        }
        if (inLayout) {
            addViewInLayout(child, index, lp, true);
        } else {
            addView(child, index, lp);
        }
    }

    private void detachChild(View child) {
        if (child.getParent() != this) {
            return;
        }
        if (inLayout) {
            removeViewInLayout(child);
        } else {
            removeView(child);
        }
    }

    private void clearAllItemViews() {
        bannerBoundAdapterPos = -1;
        if (bannerView != null) {
            bannerView.animate().cancel();
            bannerView.setTranslationX(0f);
            bannerView.setRotation(0f);
            bannerView.setScaleX(1f);
            bannerView.setScaleY(1f);
            bannerView.setAlpha(1f);
        }
        for (int i = 0; i < boundUnfocusedViews.size(); i++) {
            recycleUnfocusedView(boundUnfocusedViews.valueAt(i));
        }
        boundUnfocusedViews.clear();
        invalidateViewport();
    }

    private void invalidateViewport() {
        if (!transparentViewport) {
            return;
        }
        invalidate();
        ViewParent p = getParent();
        if (p instanceof View) {
            ((View) p).invalidate();
            ViewParent gp = p.getParent();
            if (gp instanceof View) {
                ((View) gp).invalidate();
            }
        }
    }

    private void recycleUnfocusedView(View view) {
        view.animate().cancel();
        view.setTranslationX(0f);
        view.setRotation(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setAlpha(1f);
        detachChild(view);
        recycledUnfocusedViews.add(view);
    }

    private static void layoutChildFrame(View child, int left, int top, int width, int height) {
        if (child.getMeasuredWidth() != width || child.getMeasuredHeight() != height || child.isLayoutRequested()) {
            int wSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
            int hSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
            child.measure(wSpec, hSpec);
        }
        child.layout(left, top, left + width, top + height);
    }

    private final class ContextMenuOverlay extends ViewGroup {

        ContextMenuOverlay(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(w, h);
            int btnSpec = MeasureSpec.makeMeasureSpec(contextMenuButtonSizePx, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() != GONE) {
                    child.measure(btnSpec, btnSpec);
                }
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            layoutContextMenuButtons(lastSelectedScreenCenterY);
        }
    }

    private void requestSnapshotsAroundSelection(boolean includeNeighbors) {
        if (!RecentsBridgeClient.isAvailable() || adapter == null || thumbnailsSuppressed) {
            return;
        }
        int count = getItemCount();
        if (selectedAdapterPos < 0 || selectedAdapterPos >= count) {
            return;
        }
        requestSnapshotForAdapterPos(selectedAdapterPos, includeNeighbors);
        if (includeNeighbors) {
            if (selectedAdapterPos - 1 >= 0) {
                requestSnapshotForAdapterPos(selectedAdapterPos - 1, false);
            }
            if (selectedAdapterPos + 1 < count) {
                requestSnapshotForAdapterPos(selectedAdapterPos + 1, false);
            }
        }
    }

    private void requestSnapshotForAdapterPos(int adapterPos, boolean refreshEvenIfCached) {
        Result<?> item = adapter.getItem(adapterPos);
        if (!(item.getPojo() instanceof AppPojo)) {
            return;
        }
        final int taskId = ((AppPojo) item.getPojo()).taskId;
        if (taskId <= 0) {
            return;
        }
        if (!refreshEvenIfCached && TaskSnapshotCache.get(taskId) != null) {
            return;
        }
        if (!inFlightSnapshots.add(taskId)) {
            return;
        }
        Utilities.getDefaultExecutor().execute(() -> {
            Bitmap fresh = RecentsBridgeClient.getTaskSnapshot(taskId);
            post(() -> {
                inFlightSnapshots.remove(taskId);
                if (fresh != null) {
                    TaskSnapshotCache.put(taskId, fresh);
                    AppResult selected = getSelectedAppResult();
                    if (selected != null && selected.getPojo().taskId == taskId) {
                        rebindBanner();
                    }
                }
            });
        });
    }

    private int hitTestVisualIndex(float x, float y) {
        int count = getItemCount();
        if (count == 0) {
            return -1;
        }
        int selectedVisualIdx = adapterToVisualPos(selectedAdapterPos);
        if (selectedVisualIdx >= 0 && bannerView != null && bannerView.getVisibility() == View.VISIBLE) {
            if (isPointInsideTransformedView(bannerView, x, y)) {
                return selectedVisualIdx;
            }
        }
        for (int i = 0; i < boundUnfocusedViews.size(); i++) {
            int v = boundUnfocusedViews.keyAt(i);
            View child = boundUnfocusedViews.valueAt(i);
            if (isPointInsideTransformedView(child, x, y)) {
                return v;
            }
        }
        return -1;
    }

    private boolean isTouchInsideContextMenu(float x, float y) {
        for (int i = 0; i < visibleContextButtons.size(); i++) {
            if (isPointInsideTransformedView(visibleContextButtons.get(i), x, y)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPointInsideTransformedView(View view, float x, float y) {
        float left = view.getLeft() + view.getTranslationX();
        float top = view.getTop() + view.getTranslationY();
        float right = left + view.getWidth();
        float bottom = top + view.getHeight();
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    @Nullable
    private AppResult getSelectedAppResult() {
        if (adapter == null || selectedAdapterPos < 0 || selectedAdapterPos >= adapter.getCount()) {
            return null;
        }
        Result<?> r = adapter.getItem(selectedAdapterPos);
        return r instanceof AppResult ? (AppResult) r : null;
    }

    private int getItemCount() {
        return adapter != null ? adapter.getCount() : 0;
    }

    private int visualToAdapterPos(int visualIdx) {
        int count = getItemCount();
        if (visualIdx < 0 || visualIdx >= count) {
            return -1;
        }
        return visualIdx;
    }

    private int adapterToVisualPos(int adapterPos) {
        int count = getItemCount();
        if (adapterPos < 0 || adapterPos >= count) {
            return -1;
        }
        return adapterPos;
    }

    private float computeSlotCenterY(int visualIdx, float focalY, float stepPx, float extraHalf) {
        float distIdx = visualIdx - scrollItemPos;
        float t = clamp(Math.abs(distIdx), 0f, 1f);
        float eased = t * t * (3f - 2f * t);
        float push = (distIdx >= 0f ? 1f : -1f) * extraHalf * eased;
        return focalY + distIdx * stepPx + push;
    }

    private static float mapOverscroll(float rawPos, int maxIdx) {
        if (rawPos < 0f) {
            return -rubberBand(-rawPos, EDGE_OVERSCROLL_RANGE_ITEMS);
        }
        if (rawPos > maxIdx) {
            return maxIdx + rubberBand(rawPos - maxIdx, EDGE_OVERSCROLL_RANGE_ITEMS);
        }
        return rawPos;
    }

    private static float mapMomentumOvershoot(float edge, float rawOvershoot) {
        if (rawOvershoot < 0f) {
            return edge - rubberBand(-rawOvershoot, MOMENTUM_OVERSHOOT_MAX_ITEMS);
        }
        return edge + rubberBand(rawOvershoot, MOMENTUM_OVERSHOOT_MAX_ITEMS);
    }

    private static float unmapOverscroll(float mappedPos, int maxIdx) {
        if (mappedPos < 0f) {
            float d = Math.min(EDGE_OVERSCROLL_RANGE_ITEMS * 0.92f, -mappedPos);
            return -(d * EDGE_OVERSCROLL_RANGE_ITEMS) / Math.max(0.05f, EDGE_OVERSCROLL_RANGE_ITEMS - d);
        }
        if (mappedPos > maxIdx) {
            float d = Math.min(EDGE_OVERSCROLL_RANGE_ITEMS * 0.92f, mappedPos - maxIdx);
            return maxIdx + (d * EDGE_OVERSCROLL_RANGE_ITEMS) / Math.max(0.05f, EDGE_OVERSCROLL_RANGE_ITEMS - d);
        }
        return mappedPos;
    }

    private static float rubberBand(float distance, float range) {
        if (distance <= 0f || range <= 0f) {
            return 0f;
        }
        return (distance * range) / (distance + range);
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private static void cancelAnimator(@Nullable ValueAnimator animator) {
        if (animator != null) {
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            if (animator.isRunning()) {
                animator.cancel();
            }
        }
    }
}
