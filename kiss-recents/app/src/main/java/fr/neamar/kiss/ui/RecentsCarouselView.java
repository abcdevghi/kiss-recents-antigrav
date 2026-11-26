package fr.neamar.kiss.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
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
    private static final float CONTEXT_SWIPE_OPEN_THRESHOLD = 0.28f;
    private static final int STAGGER_CLEAR_ALL_MS = 35;

    private static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final Interpolator DECELERATE = new DecelerateInterpolator(1.6f);
    private static final Interpolator OVERSHOOT = new OvershootInterpolator(1.1f);
    private static final Interpolator ACCELERATE = new AccelerateInterpolator(1.4f);

    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private final int longPressTimeoutMs;
    private final int unfocusedHeightPx;
    private final int bannerHeightPx;
    private final int itemGapPx;
    private final int horizontalMarginPx;
    private final int contextShiftPx;
    private final int contextMenuButtonSizePx;
    private final int contextMenuStepPx;
    private final int maxScrollSwayPx;

    private final OverScroller scrollScroller;
    private final SparseArray<View> boundUnfocusedViews = new SparseArray<>();
    private final List<View> recycledUnfocusedViews = new ArrayList<>();
    private final Set<Integer> inFlightSnapshots = new HashSet<>();

    private View bannerView;
    private FrameLayout contextMenuLayer;
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
    private int prevSelectedAdapterPos = -1;
    private String selectedPojoId;

    private float scrollItemPos = 0f;
    private float expandProgress = 1f;
    private float grabScale = 1f;
    private float contextMenuProgress = 0f;
    private float scrollSwayPx = 0f;
    private boolean grabbedSelected = false;

    private int dismissingVisualIndex = -1;
    private float dismissingDx = 0f;
    private boolean dismissHapticFired = false;
    private boolean animatingClearAll = false;
    private boolean animatingSingleDismiss = false;

    private ValueAnimator scrollAnimator;
    private ValueAnimator expandAnimator;
    private ValueAnimator grabAnimator;
    private ValueAnimator contextAnimator;
    private ValueAnimator swayAnimator;
    private ValueAnimator dismissAnimator;

    private DragMode dragMode = DragMode.NONE;
    private float downX;
    private float downY;
    private float lastY;
    private int downVisualIndex = -1;
    private float contextStartProgress = 0f;
    private boolean longPressTriggered = false;
    private VelocityTracker velocityTracker;
    private Runnable pendingNotTouchedAction;

    private float[] yCenterScratch = new float[0];
    private float[] heightScratch = new float[0];

    private final Runnable flingTick = new Runnable() {
        @Override
        public void run() {
            if (scrollScroller.computeScrollOffset()) {
                float oldPos = scrollItemPos;
                scrollItemPos = scrollScroller.getCurrY() / 1000f;
                float vel = (scrollItemPos - oldPos) * 60f;
                scrollSwayPx = clamp(vel * DrawableUtils.dpToPx(getContext(), 6f), -maxScrollSwayPx, maxScrollSwayPx);
                layoutCarousel();
                postOnAnimation(this);
            } else {
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
        minFlingVelocity = vc.getScaledMinimumFlingVelocity() * 8;
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout();

        unfocusedHeightPx = resolveUnfocusedRowHeight(context);
        bannerHeightPx = getResources().getDimensionPixelSize(R.dimen.banner_card_height);
        itemGapPx = DrawableUtils.dpToPx(context, 6f);
        horizontalMarginPx = DrawableUtils.dpToPx(context, 16f);
        contextShiftPx = DrawableUtils.dpToPx(context, 78f);
        contextMenuButtonSizePx = DrawableUtils.dpToPx(context, 44f);
        contextMenuStepPx = DrawableUtils.dpToPx(context, 52f);
        maxScrollSwayPx = DrawableUtils.dpToPx(context, 14f);

        scrollScroller = new OverScroller(context, DECELERATE);
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
        contextMenuLayer = new FrameLayout(context);
        contextMenuLayer.setClipChildren(false);
        contextMenuLayer.setClipToPadding(false);
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
        prevSelectedAdapterPos = -1;
        contextMenuProgress = 0f;
        contextMenuLayer.setVisibility(View.GONE);
    }

    public void onDatasetChanged(boolean resetScrollIfFresh) {
        int count = getItemCount();
        if (count == 0) {
            selectedAdapterPos = -1;
            prevSelectedAdapterPos = -1;
            selectedPojoId = null;
            contextMenuProgress = 0f;
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
            expandProgress = 1f;
            prevSelectedAdapterPos = -1;
        } else {
            scrollItemPos = clamp(scrollItemPos, 0f, count - 1);
        }
        selectedAdapterPos = resolvedPos;

        clearAllItemViews();
        layoutCarousel();
        requestSnapshotsAroundSelection();
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
        removeCallbacks(flingTick);
        removeCallbacks(longPressRunnable);
        cancelAnimator(scrollAnimator);
        cancelAnimator(expandAnimator);
        cancelAnimator(grabAnimator);
        cancelAnimator(contextAnimator);
        cancelAnimator(swayAnimator);
        cancelAnimator(dismissAnimator);
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
        removeCallbacks(longPressRunnable);
        if (!scrollScroller.isFinished()) {
            scrollScroller.abortAnimation();
        }
        cancelAnimator(scrollAnimator);
        cancelAnimator(swayAnimator);

        downX = event.getX();
        downY = event.getY();
        lastY = downY;
        dragMode = DragMode.NONE;
        longPressTriggered = false;
        dismissHapticFired = false;
        contextStartProgress = contextMenuProgress;

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
                if (isContextMenuOpen()) {
                    animateContextMenu(false);
                }
                dragMode = DragMode.SCROLL_VERTICAL;
            } else if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                removeCallbacks(longPressRunnable);
                setGrabbedSelected(false);
                if (isContextMenuOpen() && dx > 0f) {
                    dragMode = DragMode.SWIPE_CONTEXT;
                } else if (!isContextMenuOpen() && dx < 0f && downVisualIndex >= 0) {
                    int targetAdapterPos = visualToAdapterPos(downVisualIndex);
                    if (targetAdapterPos >= 0 && targetAdapterPos != selectedAdapterPos) {
                        selectAdapterPosition(targetAdapterPos, true);
                    }
                    dragMode = DragMode.SWIPE_CONTEXT;
                } else if (!isContextMenuOpen() && dx > 0f && downVisualIndex >= 0) {
                    int targetAdapterPos = visualToAdapterPos(downVisualIndex);
                    if (targetAdapterPos >= 0 && callback != null
                            && callback.canDismiss(adapter.getItem(targetAdapterPos))) {
                        dismissingVisualIndex = downVisualIndex;
                        dragMode = DragMode.SWIPE_DISMISS;
                    }
                }
            }
        }

        if (dragMode == DragMode.SCROLL_VERTICAL) {
            float stepDy = y - lastY;
            float pxPerItem = unfocusedHeightPx + itemGapPx;
            float deltaItems = -stepDy / Math.max(1f, pxPerItem);
            int maxIdx = Math.max(0, getItemCount() - 1);
            float next = scrollItemPos + deltaItems;
            if (next < 0f) {
                next = Math.max(-0.4f, next * 0.45f);
            } else if (next > maxIdx) {
                next = Math.min(maxIdx + 0.4f, maxIdx + (next - maxIdx) * 0.45f);
            }
            scrollItemPos = next;
            scrollSwayPx = clamp(-stepDy * 0.35f, -maxScrollSwayPx, maxScrollSwayPx);
            layoutCarousel();
        } else if (dragMode == DragMode.SWIPE_CONTEXT) {
            float dragSpan = Math.max(DrawableUtils.dpToPx(getContext(), 120f), getWidth() * 0.30f);
            contextMenuProgress = clamp(contextStartProgress + (-dx / dragSpan), 0f, 1f);
            updateContextMenuVisibilityAndLayout();
            layoutCarousel();
        } else if (dragMode == DragMode.SWIPE_DISMISS && dismissingVisualIndex >= 0) {
            dismissingDx = Math.max(0f, dx);
            boolean over = dismissingDx > getWidth() * DISMISS_THRESHOLD_RATIO;
            if (over != dismissHapticFired) {
                dismissHapticFired = over;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
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
                animateContextMenu(false);
            } else if (downVisualIndex >= 0) {
                int tappedAdapterPos = visualToAdapterPos(downVisualIndex);
                if (tappedAdapterPos >= 0 && tappedAdapterPos < getItemCount()) {
                    if (tappedAdapterPos == selectedAdapterPos) {
                        if (callback != null) {
                            callback.onLaunchSelected(adapter.getItem(tappedAdapterPos), bannerView);
                        }
                    } else {
                        selectAdapterPosition(tappedAdapterPos, true);
                    }
                }
            }
        } else if (dragMode == DragMode.SCROLL_VERTICAL) {
            int maxIdx = Math.max(0, getItemCount() - 1);
            if (scrollItemPos < 0f || scrollItemPos > maxIdx) {
                animateScrollTo(clamp(scrollItemPos, 0f, maxIdx));
                releaseScrollSway();
            } else if (Math.abs(vy) >= minFlingVelocity) {
                float pxPerItem = unfocusedHeightPx + itemGapPx;
                int startMilli = Math.round(scrollItemPos * 1000f);
                int velMilli = Math.round((-vy / Math.max(1f, pxPerItem)) * 1000f);
                scrollScroller.fling(0, startMilli, 0, velMilli, 0, 0, 0, maxIdx * 1000, 0, 180);
                postOnAnimation(flingTick);
            } else {
                releaseScrollSway();
            }
        } else if (dragMode == DragMode.SWIPE_CONTEXT) {
            boolean shouldOpen = contextMenuProgress > CONTEXT_SWIPE_OPEN_THRESHOLD || vx < -minFlingVelocity;
            if (contextStartProgress > 0.5f && vx > minFlingVelocity) {
                shouldOpen = false;
            }
            animateContextMenu(shouldOpen);
        } else if (dragMode == DragMode.SWIPE_DISMISS && dismissingVisualIndex >= 0) {
            boolean shouldDismiss = dismissingDx > getWidth() * DISMISS_THRESHOLD_RATIO
                    || (vx > minFlingVelocity && dismissingDx > touchSlop);
            if (shouldDismiss) {
                completeSingleDismiss(dismissingVisualIndex);
            } else {
                snapBackDismissedRow();
            }
        }

        resetTouchState();
    }

    private void handleActionCancel() {
        removeCallbacks(longPressRunnable);
        setGrabbedSelected(false);
        if (dragMode == DragMode.SCROLL_VERTICAL) {
            animateScrollTo(clamp(scrollItemPos, 0f, Math.max(0, getItemCount() - 1)));
            releaseScrollSway();
        } else if (dragMode == DragMode.SWIPE_CONTEXT) {
            animateContextMenu(contextMenuProgress > 0.5f);
        } else if (dragMode == DragMode.SWIPE_DISMISS && dismissingVisualIndex >= 0) {
            snapBackDismissedRow();
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
        if (adapterPos == selectedAdapterPos) {
            if (animate) {
                animateScrollTo(adapterToVisualPos(adapterPos));
            }
            return;
        }

        prevSelectedAdapterPos = selectedAdapterPos;
        selectedAdapterPos = adapterPos;
        selectedPojoId = adapter.getItem(adapterPos).getPojoId();
        clearAllItemViews();

        int targetVisual = adapterToVisualPos(adapterPos);
        if (animate) {
            expandProgress = 0f;
            cancelAnimator(expandAnimator);
            expandAnimator = ValueAnimator.ofFloat(0f, 1f);
            expandAnimator.setDuration(230);
            expandAnimator.setInterpolator(FAST_OUT_SLOW_IN);
            expandAnimator.addUpdateListener(anim -> {
                expandProgress = (float) anim.getAnimatedValue();
                layoutCarousel();
            });
            expandAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    prevSelectedAdapterPos = -1;
                    expandProgress = 1f;
                    layoutCarousel();
                }
            });
            expandAnimator.start();
            animateScrollTo(targetVisual);
        } else {
            prevSelectedAdapterPos = -1;
            expandProgress = 1f;
            scrollItemPos = targetVisual;
            layoutCarousel();
        }

        if (isContextMenuOpen()) {
            bindContextButtons();
        }
        requestSnapshotsAroundSelection();
    }

    private void animateScrollTo(float targetVisualPos) {
        cancelAnimator(scrollAnimator);
        scrollAnimator = ValueAnimator.ofFloat(scrollItemPos, targetVisualPos);
        scrollAnimator.setDuration(240);
        scrollAnimator.setInterpolator(FAST_OUT_SLOW_IN);
        scrollAnimator.addUpdateListener(anim -> {
            scrollItemPos = (float) anim.getAnimatedValue();
            layoutCarousel();
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
        swayAnimator.setDuration(220);
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
        cancelAnimator(contextAnimator);
        float target = open ? 1f : 0f;
        contextAnimator = ValueAnimator.ofFloat(contextMenuProgress, target);
        contextAnimator.setDuration(240);
        contextAnimator.setInterpolator(open ? OVERSHOOT : FAST_OUT_SLOW_IN);
        contextAnimator.addUpdateListener(anim -> {
            contextMenuProgress = (float) anim.getAnimatedValue();
            updateContextMenuVisibilityAndLayout();
            layoutCarousel();
        });
        contextAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                contextMenuProgress = target;
                updateContextMenuVisibilityAndLayout();
                layoutCarousel();
            }
        });
        contextAnimator.start();
    }

    private void snapBackDismissedRow() {
        cancelAnimator(dismissAnimator);
        dismissAnimator = ValueAnimator.ofFloat(dismissingDx, 0f);
        dismissAnimator.setDuration(200);
        dismissAnimator.setInterpolator(OVERSHOOT);
        dismissAnimator.addUpdateListener(anim -> {
            dismissingDx = (float) anim.getAnimatedValue();
            layoutCarousel();
        });
        dismissAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                dismissingVisualIndex = -1;
                dismissingDx = 0f;
                layoutCarousel();
            }
        });
        dismissAnimator.start();
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

        int rowWidth = Math.max(1, width - 2 * horizontalMarginPx);
        float focalY = height * 0.56f;
        float arcRadius = Math.max(height * 0.95f, DrawableUtils.dpToPx(getContext(), 520f));

        ensureScratchCapacity(count);
        int selectedVisualIdx = adapterToVisualPos(selectedAdapterPos);
        int prevSelectedVisualIdx = adapterToVisualPos(prevSelectedAdapterPos);

        for (int v = 0; v < count; v++) {
            if (v == selectedVisualIdx) {
                heightScratch[v] = unfocusedHeightPx + (bannerHeightPx - unfocusedHeightPx) * expandProgress;
            } else if (v == prevSelectedVisualIdx) {
                heightScratch[v] = unfocusedHeightPx + (bannerHeightPx - unfocusedHeightPx) * (1f - expandProgress);
            } else {
                heightScratch[v] = unfocusedHeightPx;
            }
        }

        yCenterScratch[0] = 0f;
        for (int v = 1; v < count; v++) {
            yCenterScratch[v] = yCenterScratch[v - 1] + (heightScratch[v - 1] + heightScratch[v]) * 0.5f + itemGapPx;
        }

        float focalVirtualY;
        if (scrollItemPos <= 0f) {
            focalVirtualY = yCenterScratch[0] + scrollItemPos * (unfocusedHeightPx + itemGapPx);
        } else if (scrollItemPos >= count - 1) {
            focalVirtualY = yCenterScratch[count - 1] + (scrollItemPos - (count - 1)) * (unfocusedHeightPx + itemGapPx);
        } else {
            int baseIdx = (int) Math.floor(scrollItemPos);
            float frac = scrollItemPos - baseIdx;
            focalVirtualY = yCenterScratch[baseIdx] + (yCenterScratch[baseIdx + 1] - yCenterScratch[baseIdx]) * frac;
        }

        // Track off-screen views to detach & recycle cleanly
        for (int i = boundUnfocusedViews.size() - 1; i >= 0; i--) {
            int v = boundUnfocusedViews.keyAt(i);
            float centerY = focalY + (yCenterScratch[v] - focalVirtualY);
            if (v == selectedVisualIdx || centerY < -bannerHeightPx || centerY > height + bannerHeightPx) {
                View stale = boundUnfocusedViews.valueAt(i);
                boundUnfocusedViews.removeAt(i);
                recycleUnfocusedView(stale);
            }
        }

        float selectedScreenCenterY = focalY;
        for (int v = 0; v < count; v++) {
            float centerY = focalY + (yCenterScratch[v] - focalVirtualY);
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
            int childHeight;
            if (isSelected) {
                child = obtainAndBindBanner(visualToAdapterPos(v));
                child.setVisibility(View.VISIBLE);
                childHeight = bannerHeightPx;
            } else {
                child = obtainAndBindUnfocused(v, visualToAdapterPos(v));
                childHeight = unfocusedHeightPx;
            }

            layoutChildFrame(child, horizontalMarginPx, Math.round(centerY - childHeight * 0.5f), rowWidth, childHeight);

            float dy = centerY - focalY;
            float normDist = Math.min(1.4f, Math.abs(dy) / Math.max(1f, height * 0.48f));
            double underSqrt = Math.max(0.0, (double) arcRadius * arcRadius - (double) dy * dy);
            float arcOffset = (float) (arcRadius - Math.sqrt(underSqrt)) * 1.65f;
            arcOffset = Math.min(arcOffset, width * 0.36f);

            float dismissTx = (v == dismissingVisualIndex) ? dismissingDx : 0f;
            float dismissAlpha = (v == dismissingVisualIndex)
                    ? Math.max(0f, 1f - dismissingDx / (Math.max(1f, width) * 0.8f))
                    : 1f;

            if (isSelected) {
                float contextTx = -contextShiftPx * contextMenuProgress;
                float swayTx = scrollSwayPx * 0.45f;
                child.setPivotX(rowWidth * 0.5f);
                child.setPivotY(childHeight * 0.5f);
                child.setScaleX(grabScale);
                child.setScaleY(grabScale);
                child.setAlpha(dismissAlpha);
                child.setTranslationX(contextTx + swayTx + dismissTx);
                child.setRotation(clamp(scrollSwayPx * 0.06f, -1.2f, 1.2f));
            } else {
                float scale = Math.max(0.78f, 0.95f - normDist * 0.14f);
                float baseAlpha = Math.max(0.55f, 1.0f - normDist * 0.28f);
                float contextFalloff = Math.max(0.25f, 1.0f - normDist * 0.55f);
                float contextTx = -DrawableUtils.dpToPx(getContext(), 52f) * contextMenuProgress * contextFalloff;
                float swayTx = scrollSwayPx * (0.65f + normDist * 0.35f);
                child.setPivotX(0f);
                child.setPivotY(childHeight * 0.5f);
                child.setScaleX(scale);
                child.setScaleY(scale);
                child.setAlpha(baseAlpha * dismissAlpha);
                child.setTranslationX(arcOffset + contextTx + swayTx + dismissTx);
                child.setRotation(clamp(scrollSwayPx * 0.08f, -1.4f, 1.4f));
            }
        }

        if (bannerView != null && bannerView.getParent() == this) {
            bannerView.bringToFront();
        }
        contextMenuLayer.bringToFront();
        layoutContextMenuButtons(selectedScreenCenterY);
    }

    private void updateContextMenuVisibilityAndLayout() {
        if (contextMenuProgress > 0.01f) {
            if (contextMenuLayer.getVisibility() != View.VISIBLE) {
                bindContextButtons();
                contextMenuLayer.setVisibility(View.VISIBLE);
            }
        } else {
            contextMenuLayer.setVisibility(View.GONE);
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
            addView(bannerView);
        } else if (bannerView.getParent() != this) {
            addView(bannerView);
        }
        bindBannerAt(adapterPos);
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
        Result<?> result = adapter.getItem(adapterPos);
        if (result instanceof AppResult) {
            AppResult appResult = (AppResult) result;
            Bitmap cachedSnapshot = TaskSnapshotCache.get(appResult.getPojo().taskId);
            appResult.displayBanner(getContext(), bannerView, cachedSnapshot, greyscaleSnapshots);
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
            addView(bound);
        }
        bound.setVisibility(View.VISIBLE);
        boundUnfocusedViews.put(visualIdx, bound);
        return bound;
    }

    private void clearAllItemViews() {
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
    }

    private void recycleUnfocusedView(View view) {
        view.animate().cancel();
        view.setTranslationX(0f);
        view.setRotation(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setAlpha(1f);
        removeView(view);
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

    private void requestSnapshotsAroundSelection() {
        if (!RecentsBridgeClient.isAvailable() || adapter == null) {
            return;
        }
        int count = getItemCount();
        if (selectedAdapterPos < 0 || selectedAdapterPos >= count) {
            return;
        }
        requestSnapshotForAdapterPos(selectedAdapterPos, true);
        if (selectedAdapterPos - 1 >= 0) {
            requestSnapshotForAdapterPos(selectedAdapterPos - 1, false);
        }
        if (selectedAdapterPos + 1 < count) {
            requestSnapshotForAdapterPos(selectedAdapterPos + 1, false);
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

    private void ensureScratchCapacity(int count) {
        if (yCenterScratch.length < count) {
            yCenterScratch = new float[count];
            heightScratch = new float[count];
        }
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private static void cancelAnimator(@Nullable ValueAnimator animator) {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
    }
}
