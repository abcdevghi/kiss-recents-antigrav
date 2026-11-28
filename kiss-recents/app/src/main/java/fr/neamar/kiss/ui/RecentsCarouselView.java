package fr.neamar.kiss.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.SparseIntArray;
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
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.result.AppResult;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.HistorySearcher;
import fr.neamar.kiss.utils.DrawableUtils;
import fr.neamar.kiss.utils.RecentsBridgeClient;
import fr.neamar.kiss.utils.TaskSnapshotCache;

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

        default void onExternalNavigation() {
        }

        default void onUserInteracted() {
        }

        default void onOpenSesameProgress(float progress) {
        }
    }

    private enum DragMode {
        NONE,
        SCROLL_VERTICAL,
        SWIPE_CONTEXT,
        SWIPE_DISMISS,
        SCROLLBAR_DRAG
    }

    private static final float GRAB_SCALE = 0.90f;
    private static final float DISMISS_THRESHOLD_RATIO = 0.35f;
    private static final long CLEAR_ALL_STAGGER_MS = 28L;
    private static final long CLEAR_ALL_SLIDE_DURATION_MS = 200L;
    private static final float FAST_WHEEL_ITEMS_PER_SEC = 5.5f;
    private static final long THUMBNAIL_STABILIZE_DELAY_MS = 75L;
    private static final float EDGE_OVERSCROLL_RANGE_ITEMS = 2.6f;
    private static final float MOMENTUM_OVERSHOOT_MAX_ITEMS = 0.70f;
    private static final float MOMENTUM_OVERSHOOT_BRAKE_RATE = 13.0f;
    private static final float MOMENTUM_OVERSHOOT_STOP_VEL = 1.8f;
    private static final float WHEEL_DETENT_AMP = 0.075f;
    private static final float EARLY_MOMENTUM_KILL_DIST = 1.85f;
    private static final float EARLY_MOMENTUM_MIN_RATIO = 0.45f;
    private static final float EARLY_MOMENTUM_ABS_SPEED = 6.2f;
    private static final float SPRING_STIFFNESS = 460f;
    private static final float SPRING_DAMPING_RATIO = 0.72f;
    private static final float SPRING_STOP_POS_EPS = 0.003f;
    private static final float SPRING_STOP_VEL_EPS = 0.10f;
    private static final float FLICK_NOTCH_CATCH_DIST = 0.16f;
    private static final float FLICK_NOTCH_CATCH_VEL = 2500f;
    private static final int REFLOW_DURATION_MS = 220;
    private static final long LAUNCH_ENTRANCE_DURATION_MS = 245L;
    private static final long LAUNCH_EXIT_DURATION_MS = 205L;
    private static final int MAX_RECYCLED_POOL_SIZE = 16;

    private static final Interpolator LINEAR = new LinearInterpolator();
    private static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final Interpolator DECELERATE = new DecelerateInterpolator(1.6f);
    private static final Interpolator ENTRANCE_EASE_OUT = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private static final Interpolator EXIT_EASE_IN = new PathInterpolator(0.35f, 0f, 0.85f, 0.65f);
    private static final Interpolator OVERSHOOT = new OvershootInterpolator(0.95f);
    private static final Interpolator ELASTIC_SNAP = new OvershootInterpolator(0.85f);
    private static final Interpolator ACCELERATE = new AccelerateInterpolator(1.4f);

    private final int touchSlop;
    private final int rawMinFlingVelocity;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private final int longPressTimeoutMs;
    private final int unfocusedHeightPx;
    private final int unfocusedBannerHeightPx;
    private final int compactBannerHeightPx;
    private final int calculatedFullBannerHeightPx;
    private final int bannerHeightPx;
    private final float bannerCornerRadiusPx;
    private final float unfocusedBannerCornerRadiusPx;
    private final int itemGapPx;
    private final int horizontalMarginPx;
    private final int bannerHorizontalMarginPx;
    private final int contextShiftPx;
    private final int contextMenuButtonSizePx;
    private final int contextMenuStepPx;
    private final int maxScrollSwayPx;
    private final int launchSlideDistancePx;
    private final int scrollbarHitWidthPx;
    private final int scrollbarPaddingVPx;
    private final float scrollbarOffsetXPx;
    private final float scrollbarIdleWidthPx;
    private final float scrollbarActiveWidthPx;
    private final float scrollbarMinThumbPx;
    private final int separatorGapPx;

    private final OverScroller scrollScroller;
    private final SparseArray<View> boundUnfocusedViews = new SparseArray<>();
    private final List<View> recycledUnfocusedViews = new ArrayList<>();
    private final List<View> recycledUnfocusedBannerViews = new ArrayList<>();
    private final Set<String> inFlightSnapshots = new HashSet<>();
    private final Paint scrollbarTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrollbarThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF scrollbarTrackRect = new RectF();
    private final RectF scrollbarThumbRect = new RectF();
    private final Paint separatorLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint separatorPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint separatorTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF separatorPillRect = new RectF();
    private int[] visualGroups = new int[0];
    private final List<Integer> sectionBoundaryVisuals = new ArrayList<>(2);
    private final String labelRam;
    private final String labelHistory;
    private final String labelDead;

    private View bannerView;
    private float lastBannerParallaxDy = 0f;
    private float lastBannerParallaxTx = 0f;
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
    private boolean greyscaleSnapshots = false;
    private boolean thumbnailParallax = false;
    private boolean clearDeadThumbnails = false;
    private boolean selectedBanner = false;
    private boolean selectedBannerIcon = false;
    private boolean selectedBannerOutline = false;
    private boolean ramHistorySort = false;
    private boolean ramHistorySeparators = false;
    private boolean focusCurrentApp = false;
    private boolean reverseOrder = false;
    private boolean moreBanners = false;
    private boolean moreBannersAll = false;
    private boolean moreBannersCalculatedSize = false;
    private boolean carouselArc = false;
    private boolean carouselDepthEffects = false;
    private boolean tiltSwayOvershoot = false;
    private boolean tactileScrollSnapping = false;
    private boolean launchEntranceAnim = false;
    private boolean curvedContextMenu = false;
    private boolean showScrollbar = false;
    private boolean openSesame = false;

    private int selectedAdapterPos = -1;
    private int bannerBoundAdapterPos = -1;
    private String selectedPojoId;

    private float scrollItemPos = 0f;
    private float grabScale = 1f;
    private float horizontalSwipePx = 0f;
    private float contextMenuProgress = 0f;
    private float scrollSwayPx = 0f;
    private boolean grabbed = false;
    private int grabbedVisualIndex = -1;
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
    private ValueAnimator clearAllAnimator;
    private ValueAnimator shrinkAnimator;
    private ValueAnimator scrollbarAnimator;
    private ValueAnimator scrollbarVisibilityAnimator;
    private ValueAnimator openSesameAnimator;
    private ValueAnimator launchEntranceAnimator;
    private ValueAnimator launchExitAnimator;

    private final SparseIntArray clearAllDismissRanks = new SparseIntArray();
    private float clearAllProgress = 0f;
    private long clearAllTotalDurationMs = 0L;

    private float launchEntranceProgress = 1f;
    private boolean pendingLaunchEntrance = false;
    private boolean launchExitActive = false;
    private float launchExitProgress = 0f;
    @Nullable
    private Runnable launchExitEndAction;
    private boolean pendingPlainListInitialScroll = false;

    private float scrollbarEngagement = 0f;
    private boolean scrollbarEngaged = false;
    private float scrollbarVisibility = 0f;
    private boolean scrollbarVisibilityTargetShown = false;
    private final Runnable hideScrollbarRunnable = this::fadeOutScrollbar;
    private static final long SCROLLBAR_HIDE_DELAY_MS = 850L;
    private boolean openSesameActive = false;
    private float openSesameProgress = 0f;
    private float sesameStartLeft = 0f;
    private float sesameStartTop = 0f;
    private float sesameStartWidth = 0f;
    private float sesameStartHeight = 0f;
    private float sesameTargetHeight = 0f;
    private final ColorMatrix sesameSatMatrix = new ColorMatrix();

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

    private ValueAnimator reflowAnimator;

    private static final class ReflowStart {
        final float centerY;
        final float screenLeft;
        final float visualHeight;
        final float alpha;

        ReflowStart(float centerY, float screenLeft, float visualHeight, float alpha) {
            this.centerY = centerY;
            this.screenLeft = screenLeft;
            this.visualHeight = visualHeight;
            this.alpha = alpha;
        }
    }

    private final Map<String, ReflowStart> reflowStarts = new HashMap<>();
    private float reflowProgress = 1f;
    private boolean pendingReflowAfterDismiss = false;
    @Nullable
    private String pendingDismissReplacementPojoId;

    private int flingTargetVisualIdx = -1;
    private float flingInitialVelItemsPerSec = 0f;
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
    private boolean springSnapActive = false;
    private float springTargetPos = 0f;
    private float springVelocityItemsPerSec = 0f;
    private long lastSpringTickMs = 0L;

    private final Runnable stabilizeThumbnailsRunnable = new Runnable() {
        @Override
        public void run() {
            onWheelStabilized();
        }
    };

    private final Runnable flingTick = new Runnable() {
        @Override
        public void run() {
            if (springSnapActive) {
                long now = SystemClock.uptimeMillis();
                float dt = clamp((now - lastSpringTickMs) / 1000f, 0.001f, 0.033f);
                lastSpringTickMs = now;
                int subSteps = dt > 0.012f ? 2 : 1;
                float subDt = dt / subSteps;
                float omega = (float) Math.sqrt(SPRING_STIFFNESS);
                float dampingCoeff = 2f * SPRING_DAMPING_RATIO * omega;
                float oldPos = scrollItemPos;
                for (int s = 0; s < subSteps; s++) {
                    float accel = SPRING_STIFFNESS * (springTargetPos - scrollItemPos)
                            - dampingCoeff * springVelocityItemsPerSec;
                    springVelocityItemsPerSec += accel * subDt;
                    scrollItemPos += springVelocityItemsPerSec * subDt;
                }
                float avgVel = (scrollItemPos - oldPos) / dt;
                scrollSwayPx = tiltSwayOvershoot
                        ? clamp(avgVel * DrawableUtils.dpToPx(getContext(), 2.5f), -maxScrollSwayPx, maxScrollSwayPx)
                        : 0f;
                updateWheelSpeed(oldPos, scrollItemPos);
                thumbnailsSuppressed = isWheelMovingFast();
                updateAutoSelectionFromScroll(isSnapScrollEnabled());

                if (Math.abs(springTargetPos - scrollItemPos) < SPRING_STOP_POS_EPS
                        && Math.abs(springVelocityItemsPerSec) < SPRING_STOP_VEL_EPS) {
                    scrollItemPos = springTargetPos;
                    springSnapActive = false;
                    springVelocityItemsPerSec = 0f;
                    updateAutoSelectionFromScroll(false);
                    onWheelStabilized();
                    releaseScrollSway();
                    layoutCarousel();
                    return;
                }
                layoutCarousel();
                postOnAnimation(this);
                return;
            }

            if (overshootBrakingActive) {
                boolean snapScroll = isSnapScrollEnabled();
                if (!tiltSwayOvershoot) {
                    float maxScroll = getMaxScrollItems();
                    overshootBrakingActive = false;
                    scrollItemPos = clamp(scrollItemPos, 0f, maxScroll);
                    rawScrollItemPos = unmapScrollPos(scrollItemPos, maxScroll);
                    scrollSwayPx = 0f;
                    updateAutoSelectionFromScroll(false);
                    onWheelStabilized();
                    layoutCarousel();
                    return;
                }
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
                updateAutoSelectionFromScroll(snapScroll);

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
                boolean snapScroll = isSnapScrollEnabled();
                float oldPos = scrollItemPos;
                float nextPos = scrollScroller.getCurrY() / 1000f;
                float maxScroll = getMaxScrollItems();
                if (flingTargetVisualIdx < 0 && (nextPos < 0f || nextPos > maxScroll)) {
                    if (!tiltSwayOvershoot) {
                        scrollScroller.abortAnimation();
                        scrollItemPos = clamp(nextPos, 0f, maxScroll);
                        rawScrollItemPos = unmapScrollPos(scrollItemPos, maxScroll);
                        scrollSwayPx = 0f;
                        updateWheelSpeed(oldPos, scrollItemPos);
                        thumbnailsSuppressed = false;
                        updateAutoSelectionFromScroll(false);
                        onWheelStabilized();
                        layoutCarousel();
                        return;
                    }
                    float edge = nextPos < 0f ? 0f : maxScroll;
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
                    updateAutoSelectionFromScroll(snapScroll);
                    layoutCarousel();
                    postOnAnimation(this);
                    return;
                }

                scrollItemPos = nextPos;
                float vel = (scrollItemPos - oldPos) * 60f;
                scrollSwayPx = tiltSwayOvershoot
                        ? clamp(vel * DrawableUtils.dpToPx(getContext(), 6f), -maxScrollSwayPx, maxScrollSwayPx)
                        : 0f;
                updateWheelSpeed(oldPos, scrollItemPos);
                thumbnailsSuppressed = isWheelMovingFast();
                updateAutoSelectionFromScroll(snapScroll);

                if (snapScroll) {
                    if (flingTargetVisualIdx >= 0) {
                        int targetIdx = flingTargetVisualIdx;
                        float dist = Math.abs(scrollItemPos - targetIdx);
                        float curSpeedItems = scrollScroller.getCurrVelocity() / 1000f;
                        boolean lostMostMomentum = flingInitialVelItemsPerSec > 0.1f
                                && (curSpeedItems <= flingInitialVelItemsPerSec * EARLY_MOMENTUM_MIN_RATIO
                                || curSpeedItems <= EARLY_MOMENTUM_ABS_SPEED);
                        if ((dist <= EARLY_MOMENTUM_KILL_DIST && lostMostMomentum)
                                || (dist <= 0.35f && dist < Math.abs(oldPos - targetIdx))
                                || dist <= 0.04f) {
                            float dir = (targetIdx >= scrollItemPos ? 1f : -1f);
                            float transferVel = clamp(dir * curSpeedItems * 0.58f, -6.5f, 6.5f);
                            scrollScroller.abortAnimation();
                            flingTargetVisualIdx = -1;
                            flingInitialVelItemsPerSec = 0f;
                            startSpringSnap(targetIdx, transferVel);
                            releaseScrollSway();
                            return;
                        }
                    } else if (scrollItemPos >= 0f && scrollItemPos <= maxScroll) {
                        int maxIdxInt = Math.max(0, getItemCount() - 1);
                        int nearestIdx = Math.round(clamp(scrollItemPos, 0f, maxIdxInt));
                        if (Math.abs(scrollItemPos - nearestIdx) <= FLICK_NOTCH_CATCH_DIST
                                && scrollScroller.getCurrVelocity() < FLICK_NOTCH_CATCH_VEL) {
                            float dir = (nearestIdx >= scrollItemPos ? 1f : -1f);
                            float transferVel = clamp(dir * (scrollScroller.getCurrVelocity() / 1000f) * 0.60f, -5.0f, 5.0f);
                            scrollScroller.abortAnimation();
                            startSpringSnap(nearestIdx, transferVel);
                            releaseScrollSway();
                            return;
                        }
                    }
                }

                layoutCarousel();
                postOnAnimation(this);
            } else {
                int targetIdx = flingTargetVisualIdx;
                flingTargetVisualIdx = -1;
                flingInitialVelItemsPerSec = 0f;
                if (!isSnapScrollEnabled()) {
                    float maxScroll = getMaxScrollItems();
                    if (scrollItemPos < 0f || scrollItemPos > maxScroll) {
                        if (tiltSwayOvershoot) {
                            snapScrollTo(clamp(scrollItemPos, 0f, maxScroll), true, true);
                        } else {
                            scrollItemPos = clamp(scrollItemPos, 0f, maxScroll);
                            rawScrollItemPos = unmapScrollPos(scrollItemPos, maxScroll);
                            updateAutoSelectionFromScroll(false);
                            onWheelStabilized();
                            layoutCarousel();
                        }
                    } else {
                        rawScrollItemPos = unmapScrollPos(scrollItemPos, maxScroll);
                        updateAutoSelectionFromScroll(false);
                        onWheelStabilized();
                        layoutCarousel();
                    }
                    releaseScrollSway();
                } else {
                    if (targetIdx >= 0) {
                        snapScrollTo(targetIdx, true, true);
                    } else {
                        snapScrollToNearest(true);
                    }
                    releaseScrollSway();
                }
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
            if (callback != null) {
                callback.onUserInteracted();
            }
            setGrabbedVisual(grabbedVisualIndex, false);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            int targetAdapterPos = visualToAdapterPos(downVisualIndex);
            if (targetAdapterPos >= 0 && targetAdapterPos < getItemCount()) {
                if (!curvedContextMenu) {
                    View targetView = (selectedBanner && targetAdapterPos == selectedAdapterPos)
                            ? bannerView
                            : boundUnfocusedViews.get(downVisualIndex);
                    if (targetView != null && adapter != null) {
                        adapter.onLongClick(targetAdapterPos, targetView);
                    }
                    return;
                }
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
        rawMinFlingVelocity = vc.getScaledMinimumFlingVelocity();
        minFlingVelocity = Math.round(rawMinFlingVelocity * 2.0f);
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout();

        unfocusedHeightPx = resolveUnfocusedRowHeight(context);
        unfocusedBannerHeightPx = getResources().getDimensionPixelSize(R.dimen.unfocused_banner_card_height);
        compactBannerHeightPx = getResources().getDimensionPixelSize(R.dimen.compact_banner_card_height);
        calculatedFullBannerHeightPx = getResources().getDimensionPixelSize(R.dimen.calculated_full_banner_card_height);
        bannerHeightPx = getResources().getDimensionPixelSize(R.dimen.banner_card_height);
        bannerCornerRadiusPx = getResources().getDimension(R.dimen.banner_card_corner_radius);
        unfocusedBannerCornerRadiusPx = getResources().getDimension(R.dimen.unfocused_banner_card_corner_radius);
        itemGapPx = DrawableUtils.dpToPx(context, 6f);
        horizontalMarginPx = DrawableUtils.dpToPx(context, 22f);
        bannerHorizontalMarginPx = DrawableUtils.dpToPx(context, 32f);
        contextShiftPx = DrawableUtils.dpToPx(context, 60f);
        contextMenuButtonSizePx = DrawableUtils.dpToPx(context, 44f);
        contextMenuStepPx = DrawableUtils.dpToPx(context, 52f);
        maxScrollSwayPx = DrawableUtils.dpToPx(context, 14f);
        launchSlideDistancePx = DrawableUtils.dpToPx(context, 44f);
        scrollbarHitWidthPx = DrawableUtils.dpToPx(context, 24f);
        scrollbarPaddingVPx = DrawableUtils.dpToPx(context, 18f);
        scrollbarOffsetXPx = DrawableUtils.dpToPx(context, 7f);
        scrollbarIdleWidthPx = DrawableUtils.dpToPx(context, 4f);
        scrollbarActiveWidthPx = DrawableUtils.dpToPx(context, 6.5f);
        scrollbarMinThumbPx = DrawableUtils.dpToPx(context, 28f);
        separatorGapPx = DrawableUtils.dpToPx(context, 20f);

        scrollbarTrackPaint.setStyle(Paint.Style.FILL);
        scrollbarTrackPaint.setColor(0x28FFFFFF);
        scrollbarThumbPaint.setStyle(Paint.Style.FILL);
        scrollbarThumbPaint.setColor(0xFFFFFFFF);

        separatorLinePaint.setStyle(Paint.Style.STROKE);
        separatorLinePaint.setStrokeWidth(Math.max(1f, DrawableUtils.dpToPx(context, 1f)));
        separatorLinePaint.setColor(0x3DFFFFFF);

        separatorPillPaint.setStyle(Paint.Style.FILL);
        separatorPillPaint.setColor(0x9A181B22);

        separatorTextPaint.setStyle(Paint.Style.FILL);
        separatorTextPaint.setColor(0xCCFFFFFF);
        separatorTextPaint.setTextSize(DrawableUtils.dpToPx(context, 9.5f));
        separatorTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        separatorTextPaint.setLetterSpacing(0.06f);

        labelRam = context.getString(R.string.section_label_ram);
        labelHistory = context.getString(R.string.section_label_history);
        labelDead = context.getString(R.string.section_label_dead);

        scrollScroller = new OverScroller(context, DECELERATE);
        updateScrollFriction();
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
                if (callback != null) {
                    callback.onExternalNavigation();
                }
                app.openAppDetails(getContext());
            }
        });
        btnUninstall = createArcButton(context, R.drawable.ic_action_uninstall, R.string.menu_app_uninstall, v -> {
            AppResult app = getSelectedAppResult();
            animateContextMenu(false);
            if (app != null) {
                if (callback != null) {
                    callback.onExternalNavigation();
                }
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
            View anchor = getSelectedAnchorView();
            animateContextMenu(false);
            if (app != null) {
                if (callback != null) {
                    callback.onExternalNavigation();
                }
                app.openSplitScreen(getContext(), anchor);
            }
        });
        btnPopup = createArcButton(context, R.drawable.ic_action_popup, R.string.menu_popup_view, v -> {
            AppResult app = getSelectedAppResult();
            View anchor = getSelectedAnchorView();
            animateContextMenu(false);
            if (app != null) {
                if (callback != null) {
                    callback.onExternalNavigation();
                }
                app.openPopupView(getContext(), anchor);
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
            refreshAllBoundItems();
        }
    }

    public void setThumbnailParallax(boolean thumbnailParallax) {
        if (this.thumbnailParallax != thumbnailParallax) {
            this.thumbnailParallax = thumbnailParallax;
            layoutCarousel();
        }
    }

    public void setClearDeadThumbnails(boolean clearDeadThumbnails) {
        if (this.clearDeadThumbnails != clearDeadThumbnails) {
            this.clearDeadThumbnails = clearDeadThumbnails;
            refreshAllBoundItems();
            if (!clearDeadThumbnails) {
                requestSnapshotsAroundSelection(true);
            }
        }
    }

    public void setSelectedBanner(boolean selectedBanner) {
        if (this.selectedBanner != selectedBanner) {
            this.selectedBanner = selectedBanner;
            updateScrollFriction();
            clearAllItemViews();
            if (!selectedBanner && bannerView != null) {
                bannerView.setVisibility(View.GONE);
            }
            int count = getItemCount();
            if (count > 0) {
                if (selectedBanner && selectedAdapterPos < 0) {
                    selectedAdapterPos = resolveDefaultAdapterPos(count);
                    selectedPojoId = adapter.getItem(selectedAdapterPos).getPojoId();
                    scrollItemPos = adapterToVisualPos(selectedAdapterPos);
                    rawScrollItemPos = unmapScrollPos(scrollItemPos, Math.max(0, count - 1));
                } else if (isPlainListMode()) {
                    scrollItemPos = clamp(scrollItemPos, 0f, getMaxScrollItems());
                    rawScrollItemPos = unmapScrollPos(scrollItemPos, getMaxScrollItems());
                }
                layoutCarousel();
                if (selectedBanner) {
                    requestSnapshotsAroundSelection(true);
                }
            }
        }
    }

    public void setSelectedBannerIcon(boolean selectedBannerIcon) {
        if (this.selectedBannerIcon != selectedBannerIcon) {
            this.selectedBannerIcon = selectedBannerIcon;
            rebindBanner();
        }
    }

    public void setSelectedBannerOutline(boolean selectedBannerOutline) {
        if (this.selectedBannerOutline != selectedBannerOutline) {
            this.selectedBannerOutline = selectedBannerOutline;
            rebindBanner();
        }
    }

    public void setRamHistorySort(boolean ramHistorySort, boolean ramHistorySeparators) {
        if (this.ramHistorySort != ramHistorySort || this.ramHistorySeparators != ramHistorySeparators) {
            this.ramHistorySort = ramHistorySort;
            this.ramHistorySeparators = ramHistorySort && ramHistorySeparators;
            rebuildSectionGroups();
            layoutCarousel();
        }
    }

    public void setTransparentViewport(boolean transparentViewport) {
        this.transparentViewport = transparentViewport;
    }

    public void setFocusCurrentApp(boolean focusCurrentApp) {
        this.focusCurrentApp = focusCurrentApp;
    }

    public void setReverseOrder(boolean reverseOrder) {
        if (this.reverseOrder != reverseOrder) {
            this.reverseOrder = reverseOrder;
            rebuildSectionGroups();
            int count = getItemCount();
            if (count > 0) {
                if (isPlainListMode()) {
                    scrollItemPos = reverseOrder ? 0f : getPlainListMaxScrollItems(count, getHeight());
                } else if (selectedAdapterPos >= 0) {
                    scrollItemPos = adapterToVisualPos(selectedAdapterPos);
                }
                rawScrollItemPos = unmapScrollPos(scrollItemPos, getMaxScrollItems());
                clearAllItemViews();
                layoutCarousel();
            }
        }
    }

    public void setMoreBanners(boolean enabled, boolean applyToAll, boolean calculatedSize) {
        boolean all = enabled && applyToAll;
        boolean calc = all && calculatedSize;
        if (this.moreBanners != enabled || this.moreBannersAll != all || this.moreBannersCalculatedSize != calc) {
            this.moreBanners = enabled;
            this.moreBannersAll = all;
            this.moreBannersCalculatedSize = calc;
            updateScrollFriction();
            clearAllItemViews();
            if (getItemCount() > 0) {
                layoutCarousel();
                requestSnapshotsAroundSelection(true);
            }
        }
    }

    public void setCarouselArc(boolean carouselArc) {
        if (this.carouselArc != carouselArc) {
            this.carouselArc = carouselArc;
            clearAllItemViews();
            if (getItemCount() > 0) {
                layoutCarousel();
            }
        }
    }

    public void setCarouselDepthEffects(boolean carouselDepthEffects) {
        if (this.carouselDepthEffects != carouselDepthEffects) {
            this.carouselDepthEffects = carouselDepthEffects;
            if (!carouselDepthEffects) {
                cancelAnimator(grabAnimator);
                cancelAnimator(shrinkAnimator);
                grabbed = false;
                grabbedVisualIndex = -1;
                grabScale = 1f;
                shrinkingAdapterPos = -1;
                shrinkProgress = 1f;
            }
            layoutCarousel();
        }
    }

    public void setTiltSwayOvershoot(boolean tiltSwayOvershoot) {
        if (this.tiltSwayOvershoot != tiltSwayOvershoot) {
            this.tiltSwayOvershoot = tiltSwayOvershoot;
            if (!tiltSwayOvershoot) {
                cancelAnimator(swayAnimator);
                scrollSwayPx = 0f;
                overshootBrakingActive = false;
                float maxScroll = getMaxScrollItems();
                scrollItemPos = clamp(scrollItemPos, 0f, maxScroll);
                rawScrollItemPos = unmapScrollPos(scrollItemPos, maxScroll);
            }
            layoutCarousel();
        }
    }

    public void setTactileScrollSnapping(boolean tactileScrollSnapping) {
        if (this.tactileScrollSnapping != tactileScrollSnapping) {
            this.tactileScrollSnapping = tactileScrollSnapping;
            updateScrollFriction();
        }
    }

    public void setLaunchEntranceAnim(boolean launchEntranceAnim) {
        if (this.launchEntranceAnim != launchEntranceAnim) {
            this.launchEntranceAnim = launchEntranceAnim;
            if (!launchEntranceAnim) {
                cancelAnimator(launchEntranceAnimator);
                pendingLaunchEntrance = false;
                launchEntranceProgress = 1f;
                layoutCarousel();
            }
        }
    }

    public void setCurvedContextMenu(boolean curvedContextMenu) {
        if (this.curvedContextMenu != curvedContextMenu) {
            this.curvedContextMenu = curvedContextMenu;
            if (!curvedContextMenu) {
                closeContextMenu();
            }
        }
    }

    private boolean isPlainListMode() {
        return !selectedBanner && !carouselArc;
    }

    private boolean hasMoreBanners() {
        return selectedBanner && moreBanners;
    }

    private boolean hasAllUnfocusedBanners() {
        return selectedBanner && moreBanners && moreBannersAll;
    }

    private boolean hasCalculatedBannerSize() {
        return selectedBanner && moreBanners && moreBannersAll && moreBannersCalculatedSize;
    }

    private float getCalculatedBannerHeight(float distFromFocal) {
        float d = Math.abs(distFromFocal);
        if (d < 1f) {
            float t = d * d * (3f - 2f * d);
            return calculatedFullBannerHeightPx + (unfocusedBannerHeightPx - calculatedFullBannerHeightPx) * t;
        }
        if (d < 2f) {
            float u = d - 1f;
            float t = u * u * (3f - 2f * u);
            return unfocusedBannerHeightPx + (compactBannerHeightPx - unfocusedBannerHeightPx) * t;
        }
        return compactBannerHeightPx;
    }

    private boolean isSectionBoundaryVisual(int visualIdx) {
        for (int i = 0; i < sectionBoundaryVisuals.size(); i++) {
            if (sectionBoundaryVisuals.get(i) == visualIdx) {
                return true;
            }
        }
        return false;
    }

    private boolean isSnapScrollEnabled() {
        return selectedBanner && tactileScrollSnapping;
    }

    private boolean shouldShowSectionSeparators() {
        return ramHistorySort && ramHistorySeparators && !sectionBoundaryVisuals.isEmpty();
    }

    private float getMaxScrollItems() {
        int count = getItemCount();
        if (count <= 0) {
            return 0f;
        }
        if (isPlainListMode()) {
            return getPlainListMaxScrollItems(count, getHeight());
        }
        return Math.max(0, count - 1);
    }

    private float getPlainListContentHeight(int count) {
        if (count <= 0) {
            return 0f;
        }
        float sepTotal = shouldShowSectionSeparators() ? sectionBoundaryVisuals.size() * separatorGapPx : 0f;
        return count * (float) unfocusedHeightPx + sepTotal;
    }

    private float getPlainListMaxScrollItems(int count, int height) {
        if (count <= 0 || height <= 0) {
            return 0f;
        }
        float overflowPx = Math.max(0f, getPlainListContentHeight(count) - height);
        return overflowPx / Math.max(1f, unfocusedHeightPx);
    }

    private float computePlainListCenterY(int visualIdx, int count, int height) {
        float contentH = getPlainListContentHeight(count);
        float sepBefore = 0f;
        if (shouldShowSectionSeparators()) {
            for (int i = 0; i < sectionBoundaryVisuals.size(); i++) {
                if (visualIdx >= sectionBoundaryVisuals.get(i)) {
                    sepBefore += separatorGapPx;
                }
            }
        }
        float baseTopY = contentH <= height
                ? (reverseOrder ? 0f : (height - contentH)) - scrollItemPos * unfocusedHeightPx
                : -scrollItemPos * unfocusedHeightPx;
        return baseTopY + visualIdx * (float) unfocusedHeightPx + sepBefore + unfocusedHeightPx * 0.5f;
    }

    private void updateScrollFriction() {
        scrollScroller.setFriction(ViewConfiguration.getScrollFriction() * (isSnapScrollEnabled() ? 1.75f : 1.0f));
    }

    public void setShowScrollbar(boolean showScrollbar) {
        if (this.showScrollbar != showScrollbar) {
            this.showScrollbar = showScrollbar;
            if (!showScrollbar) {
                removeCallbacks(hideScrollbarRunnable);
                cancelAnimator(scrollbarVisibilityAnimator);
                scrollbarVisibility = 0f;
                scrollbarVisibilityTargetShown = false;
                scrollbarEngaged = false;
                scrollbarEngagement = 0f;
            }
            invalidate();
        }
    }

    public void setOpenSesame(boolean openSesame) {
        this.openSesame = openSesame;
    }

    public void resetOpenSesame() {
        cancelAnimator(openSesameAnimator);
        boolean wasActive = openSesameActive;
        openSesameActive = false;
        openSesameProgress = 0f;
        if (bannerView != null) {
            bannerView.setTag(R.id.banner_scrim, null);
            bannerView.setTag(R.id.banner_app_name, null);
            bannerView.invalidateOutline();
            if (!selectedBanner) {
                bannerView.setVisibility(View.GONE);
            }
        }
        if (wasActive) {
            if (isPlainListMode()) {
                selectedAdapterPos = -1;
                selectedPojoId = null;
            }
            if (callback != null) {
                callback.onOpenSesameProgress(0f);
            }
            if (getItemCount() > 0) {
                if (selectedBanner && selectedAdapterPos >= 0) {
                    rebindBanner();
                }
                layoutCarousel();
            }
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
        return animatingClearAll || animatingSingleDismiss || reflowProgress < 1f || openSesameActive || launchExitActive;
    }

    public boolean startHomeExitAnimation(@NonNull Runnable onComplete) {
        if (launchExitActive) {
            return true;
        }
        if (!launchEntranceAnim || openSesameActive || animatingClearAll || animatingSingleDismiss
                || getItemCount() == 0 || getWidth() <= 0 || getHeight() <= 0) {
            return false;
        }
        boolean hasAnyVisible = (bannerView != null && bannerView.getVisibility() == View.VISIBLE)
                || boundUnfocusedViews.size() > 0;
        if (!hasAnyVisible) {
            return false;
        }
        removeCallbacks(flingTick);
        removeCallbacks(stabilizeThumbnailsRunnable);
        removeCallbacks(longPressRunnable);
        if (!scrollScroller.isFinished()) {
            scrollScroller.abortAnimation();
        }
        cancelAnimator(scrollAnimator);
        cancelAnimator(swayAnimator);
        cancelAnimator(horizontalAnimator);
        cancelAnimator(grabAnimator);
        cancelAnimator(launchEntranceAnimator);
        dragMode = DragMode.NONE;
        grabbedVisualIndex = -1;
        grabScale = 1f;
        horizontalSwipePx = 0f;
        contextMenuProgress = 0f;
        dismissingVisualIndex = -1;
        dismissingDx = 0f;
        updateContextMenuVisibilityAndLayout();
        pendingLaunchEntrance = false;
        launchEntranceProgress = 1f;

        launchExitActive = true;
        launchExitProgress = 0f;
        launchExitEndAction = onComplete;
        cancelAnimator(launchExitAnimator);
        launchExitAnimator = ValueAnimator.ofFloat(0f, 1f);
        launchExitAnimator.setDuration(LAUNCH_EXIT_DURATION_MS);
        launchExitAnimator.setInterpolator(LINEAR);
        launchExitAnimator.addUpdateListener(anim -> {
            launchExitProgress = (float) anim.getAnimatedValue();
            layoutCarousel();
        });
        launchExitAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                Runnable endAction = launchExitEndAction;
                launchExitEndAction = null;
                launchExitProgress = 1f;
                layoutCarousel();
                launchExitActive = false;
                if (endAction != null) {
                    endAction.run();
                }
            }
        });
        launchExitAnimator.start();
        return true;
    }

    public void cancelHomeExitAnimation() {
        launchExitEndAction = null;
        if (launchExitActive) {
            launchExitActive = false;
            launchExitProgress = 0f;
            cancelAnimator(launchExitAnimator);
            layoutCarousel();
        } else {
            cancelAnimator(launchExitAnimator);
        }
    }

    public void resetSelection() {
        selectedPojoId = null;
        selectedAdapterPos = -1;
        bannerBoundAdapterPos = -1;
        lastBannerParallaxDy = 0f;
        lastBannerParallaxTx = 0f;
        shrinkingAdapterPos = -1;
        shrinkProgress = 1f;
        scrollItemPos = 0f;
        rawScrollItemPos = 0f;
        flingTargetVisualIdx = -1;
        flingInitialVelItemsPerSec = 0f;
        wheelSpeedItemsPerSec = 0f;
        lastVelSampleTimeMs = 0L;
        thumbnailsSuppressed = false;
        bannerBoundLightweight = false;
        overshootBrakingActive = false;
        springSnapActive = false;
        springVelocityItemsPerSec = 0f;
        scrollbarEngaged = false;
        scrollbarEngagement = 0f;
        scrollbarVisibility = 0f;
        scrollbarVisibilityTargetShown = false;
        openSesameActive = false;
        openSesameProgress = 0f;
        grabScale = 1f;
        grabbed = false;
        grabbedVisualIndex = -1;
        pendingReflowAfterDismiss = false;
        pendingDismissReplacementPojoId = null;
        reflowStarts.clear();
        reflowProgress = 1f;
        removeCallbacks(flingTick);
        removeCallbacks(stabilizeThumbnailsRunnable);
        removeCallbacks(longPressRunnable);
        removeCallbacks(hideScrollbarRunnable);
        if (!scrollScroller.isFinished()) {
            scrollScroller.abortAnimation();
        }
        cancelAnimator(scrollAnimator);
        cancelAnimator(grabAnimator);
        cancelAnimator(horizontalAnimator);
        cancelAnimator(swayAnimator);
        cancelAnimator(dismissAnimator);
        cancelAnimator(clearAllAnimator);
        cancelAnimator(shrinkAnimator);
        cancelAnimator(reflowAnimator);
        cancelAnimator(scrollbarAnimator);
        cancelAnimator(scrollbarVisibilityAnimator);
        cancelAnimator(openSesameAnimator);
        cancelAnimator(launchEntranceAnimator);
        launchExitEndAction = null;
        cancelAnimator(launchExitAnimator);
        launchExitActive = false;
        launchExitProgress = 0f;
        animatingClearAll = false;
        clearAllProgress = 0f;
        clearAllDismissRanks.clear();
        launchEntranceProgress = 1f;
        pendingLaunchEntrance = launchEntranceAnim;
        pendingPlainListInitialScroll = isPlainListMode();
        horizontalSwipePx = 0f;
        contextMenuProgress = 0f;
        scrollSwayPx = 0f;
        dismissingVisualIndex = -1;
        dismissingDx = 0f;
        contextMenuLayer.setVisibility(View.GONE);
        clearAllItemViews();
        if (bannerView != null) {
            bannerView.setTag(R.id.banner_scrim, null);
            bannerView.setTag(R.id.banner_app_name, null);
            bannerView.invalidateOutline();
            bannerView.setVisibility(View.GONE);
        }
        invalidateViewport();
    }

    public void onDatasetChanged(boolean resetScrollIfFresh) {
        removeCallbacks(stabilizeThumbnailsRunnable);
        cancelAnimator(shrinkAnimator);
        cancelAnimator(reflowAnimator);
        shrinkingAdapterPos = -1;
        shrinkProgress = 1f;
        wheelSpeedItemsPerSec = 0f;
        thumbnailsSuppressed = false;

        int count = getItemCount();
        rebuildSectionGroups();
        if (count == 0) {
            selectedAdapterPos = -1;
            selectedPojoId = null;
            scrollItemPos = 0f;
            rawScrollItemPos = 0f;
            flingTargetVisualIdx = -1;
            overshootBrakingActive = false;
            pendingReflowAfterDismiss = false;
            pendingDismissReplacementPojoId = null;
            pendingPlainListInitialScroll = false;
            reflowStarts.clear();
            reflowProgress = 1f;
            animatingClearAll = false;
            clearAllProgress = 0f;
            clearAllDismissRanks.clear();
            cancelAnimator(clearAllAnimator);
            launchExitEndAction = null;
            launchExitActive = false;
            launchExitProgress = 0f;
            cancelAnimator(launchExitAnimator);
            cancelAnimator(launchEntranceAnimator);
            launchEntranceProgress = 1f;
            horizontalSwipePx = 0f;
            contextMenuProgress = 0f;
            dismissingVisualIndex = -1;
            dismissingDx = 0f;
            contextMenuLayer.setVisibility(View.GONE);
            clearAllItemViews();
            return;
        }

        if (isPlainListMode()) {
            selectedAdapterPos = -1;
            selectedPojoId = null;
            if (resetScrollIfFresh || pendingPlainListInitialScroll) {
                int h = getHeight();
                if (h > 0) {
                    scrollItemPos = reverseOrder ? 0f : getPlainListMaxScrollItems(count, h);
                    pendingPlainListInitialScroll = false;
                } else {
                    scrollItemPos = 0f;
                    pendingPlainListInitialScroll = true;
                }
            } else {
                scrollItemPos = clamp(scrollItemPos, 0f, getMaxScrollItems());
            }
            rawScrollItemPos = unmapScrollPos(scrollItemPos, getMaxScrollItems());
        } else {
            pendingPlainListInitialScroll = false;
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
                resolvedPos = resolveDefaultAdapterPos(count);
                selectedPojoId = adapter.getItem(resolvedPos).getPojoId();
                scrollItemPos = adapterToVisualPos(resolvedPos);
            } else {
                int resolvedVisual = adapterToVisualPos(resolvedPos);
                if (dragMode == DragMode.NONE && !overshootBrakingActive && !springSnapActive && scrollScroller.isFinished()) {
                    scrollItemPos = clamp(resolvedVisual, 0f, count - 1);
                } else {
                    scrollItemPos = clamp(scrollItemPos, 0f, count - 1);
                }
            }
            rawScrollItemPos = unmapScrollPos(scrollItemPos, Math.max(0, count - 1));
            selectedAdapterPos = resolvedPos;
        }

        clearAllItemViews();
        boolean runReflow = pendingReflowAfterDismiss && !reflowStarts.isEmpty();
        pendingReflowAfterDismiss = false;
        if (runReflow) {
            pendingLaunchEntrance = false;
            cancelAnimator(launchEntranceAnimator);
            launchEntranceProgress = 1f;
            startReflowAnimation();
        } else {
            reflowStarts.clear();
            reflowProgress = 1f;
            if (launchEntranceAnim && pendingLaunchEntrance) {
                pendingLaunchEntrance = false;
                startLaunchEntranceAnimation();
            } else {
                pendingLaunchEntrance = false;
                cancelAnimator(launchEntranceAnimator);
                launchEntranceProgress = 1f;
            }
        }
        layoutCarousel();
        requestSnapshotsAroundSelection(true);
    }

    private void startLaunchEntranceAnimation() {
        cancelAnimator(launchEntranceAnimator);
        launchEntranceProgress = 0f;
        launchEntranceAnimator = ValueAnimator.ofFloat(0f, 1f);
        launchEntranceAnimator.setDuration(LAUNCH_ENTRANCE_DURATION_MS);
        launchEntranceAnimator.setInterpolator(LINEAR);
        launchEntranceAnimator.addUpdateListener(anim -> {
            launchEntranceProgress = (float) anim.getAnimatedValue();
            layoutCarousel();
        });
        launchEntranceAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                launchEntranceProgress = 1f;
                layoutCarousel();
            }
        });
        launchEntranceAnimator.start();
    }

    private int resolveDefaultAdapterPos(int count) {
        int currentPos = -1;
        int previousPos = -1;
        for (int i = 0; i < count; i++) {
            Object pojo = adapter.getItem(i).getPojo();
            if (pojo instanceof AppPojo) {
                int rank = ((AppPojo) pojo).recentTaskRank;
                if (rank == 0 && currentPos < 0) {
                    currentPos = i;
                } else if (rank == 1 && previousPos < 0) {
                    previousPos = i;
                }
            }
        }
        if (focusCurrentApp) {
            return currentPos >= 0 ? currentPos : Math.max(0, count - 1);
        }
        if (previousPos >= 0) {
            return previousPos;
        }
        if (currentPos >= 0) {
            return currentPos;
        }
        return Math.max(0, count - 2);
    }

    private void rebuildSectionGroups() {
        sectionBoundaryVisuals.clear();
        int count = getItemCount();
        if (!ramHistorySort || count <= 1 || adapter == null) {
            visualGroups = new int[0];
            return;
        }
        if (visualGroups.length != count) {
            visualGroups = new int[count];
        }
        int prevGroup = -1;
        for (int v = 0; v < count; v++) {
            int aPos = visualToAdapterPos(v);
            int group = 2;
            if (aPos >= 0) {
                Object pojo = adapter.getItem(aPos).getPojo();
                if (pojo instanceof AppPojo) {
                    AppPojo app = (AppPojo) pojo;
                    if (app.isTopHistory) {
                        group = 1;
                    } else if (app.rssKb > 0L) {
                        group = 0;
                    } else {
                        group = 2;
                    }
                }
            }
            visualGroups[v] = group;
            if (v > 0 && group != prevGroup) {
                sectionBoundaryVisuals.add(v);
            }
            prevGroup = group;
        }
    }

    public void refreshAllBoundItems() {
        rebindBanner();
        for (int i = 0; i < boundUnfocusedViews.size(); i++) {
            int visualIdx = boundUnfocusedViews.keyAt(i);
            View v = boundUnfocusedViews.valueAt(i);
            int adapterPos = visualToAdapterPos(visualIdx);
            if (adapterPos >= 0 && adapterPos < getItemCount()) {
                Result<?> item = adapter.getItem(adapterPos);
                if (isUnfocusedBannerView(v) && item instanceof AppResult) {
                    AppResult appResult = (AppResult) item;
                    Bitmap cachedSnapshot = getResolvedUnfocusedSnapshot(appResult);
                    appResult.displayUnfocusedBanner(getContext(), v, cachedSnapshot, greyscaleSnapshots, thumbnailsSuppressed);
                } else {
                    item.display(getContext(), v, this, null);
                }
            }
        }
        layoutCarousel();
    }

    public void dismissAll() {
        if (animatingClearAll || animatingSingleDismiss || launchExitActive || openSesameActive
                || adapter == null || callback == null || adapter.getCount() == 0) {
            return;
        }
        removeCallbacks(flingTick);
        removeCallbacks(longPressRunnable);
        if (!scrollScroller.isFinished()) {
            scrollScroller.abortAnimation();
        }
        overshootBrakingActive = false;
        springSnapActive = false;
        cancelAnimator(scrollAnimator);
        cancelAnimator(horizontalAnimator);
        cancelAnimator(grabAnimator);
        cancelAnimator(launchEntranceAnimator);
        dragMode = DragMode.NONE;
        grabbedVisualIndex = -1;
        grabScale = 1f;
        horizontalSwipePx = 0f;
        contextMenuProgress = 0f;
        dismissingVisualIndex = -1;
        dismissingDx = 0f;
        updateContextMenuVisibilityAndLayout();
        pendingLaunchEntrance = false;
        launchEntranceProgress = 1f;
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
        clearAllDismissRanks.clear();
        int dismissRank = 0;
        for (int v = count - 1; v >= 0; v--) {
            int adapterPos = visualToAdapterPos(v);
            Result<?> item = adapter.getItem(adapterPos);
            if (!toDismissSet.contains(item)) {
                continue;
            }
            View child = (selectedBanner && !hasCalculatedBannerSize() && adapterPos == selectedAdapterPos)
                    ? bannerView : boundUnfocusedViews.get(v);
            if (child != null && child.getParent() == this && child.getVisibility() == View.VISIBLE) {
                clearAllDismissRanks.put(v, dismissRank++);
            }
        }

        if (clearAllDismissRanks.size() == 0) {
            finishClearAllDismiss(toDismiss, toDismissSet);
            return;
        }

        animatingClearAll = true;
        clearAllProgress = 0f;
        clearAllTotalDurationMs = CLEAR_ALL_SLIDE_DURATION_MS
                + (long) Math.max(0, clearAllDismissRanks.size() - 1) * CLEAR_ALL_STAGGER_MS;
        cancelAnimator(clearAllAnimator);
        clearAllAnimator = ValueAnimator.ofFloat(0f, 1f);
        clearAllAnimator.setDuration(clearAllTotalDurationMs);
        clearAllAnimator.setInterpolator(LINEAR);
        clearAllAnimator.addUpdateListener(anim -> {
            clearAllProgress = (float) anim.getAnimatedValue();
            layoutCarousel();
        });
        clearAllAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animatingClearAll = false;
                clearAllProgress = 1f;
                clearAllDismissRanks.clear();
                finishClearAllDismiss(toDismiss, toDismissSet);
            }
        });
        clearAllAnimator.start();
    }

    private void finishClearAllDismiss(List<Result<?>> toDismiss, Set<Result<?>> toDismissSet) {
        captureReflowStarts(-1, toDismissSet);
        Result<?> currentSel = (selectedAdapterPos >= 0 && selectedAdapterPos < getItemCount())
                ? adapter.getItem(selectedAdapterPos) : null;
        if (currentSel != null && toDismissSet.contains(currentSel) && !isPlainListMode()) {
            int curVis = adapterToVisualPos(selectedAdapterPos);
            int count = getItemCount();
            String replacementId = null;
            for (int offset = 1; offset < count; offset++) {
                int up = curVis - offset;
                if (up >= 0) {
                    Result<?> r = adapter.getItem(visualToAdapterPos(up));
                    if (!toDismissSet.contains(r)) {
                        replacementId = r.getPojoId();
                        break;
                    }
                }
                int down = curVis + offset;
                if (down < count) {
                    Result<?> r = adapter.getItem(visualToAdapterPos(down));
                    if (!toDismissSet.contains(r)) {
                        replacementId = r.getPojoId();
                        break;
                    }
                }
            }
            selectedPojoId = replacementId;
        }
        pendingDismissReplacementPojoId = null;
        pendingReflowAfterDismiss = !reflowStarts.isEmpty();
        for (int i = 0; i < toDismiss.size(); i++) {
            Result<?> r = toDismiss.get(i);
            if (r.getPojo() instanceof AppPojo) {
                TaskSnapshotCache.invalidate(((AppPojo) r.getPojo()).taskId);
            }
        }
        if (callback != null) {
            callback.onDismissed(toDismiss);
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
        springSnapActive = false;
        pendingReflowAfterDismiss = false;
        pendingDismissReplacementPojoId = null;
        reflowStarts.clear();
        reflowProgress = 1f;
        removeCallbacks(flingTick);
        removeCallbacks(stabilizeThumbnailsRunnable);
        removeCallbacks(longPressRunnable);
        removeCallbacks(hideScrollbarRunnable);
        cancelAnimator(scrollAnimator);
        cancelAnimator(grabAnimator);
        cancelAnimator(horizontalAnimator);
        cancelAnimator(swayAnimator);
        cancelAnimator(dismissAnimator);
        cancelAnimator(clearAllAnimator);
        cancelAnimator(shrinkAnimator);
        cancelAnimator(reflowAnimator);
        cancelAnimator(scrollbarAnimator);
        cancelAnimator(scrollbarVisibilityAnimator);
        cancelAnimator(openSesameAnimator);
        cancelAnimator(launchEntranceAnimator);
        launchExitEndAction = null;
        cancelAnimator(launchExitAnimator);
        launchExitActive = false;
        launchExitProgress = 0f;
        animatingClearAll = false;
        clearAllProgress = 0f;
        clearAllDismissRanks.clear();
        launchEntranceProgress = 1f;
        inFlightSnapshots.clear();
        recycledUnfocusedViews.clear();
        recycledUnfocusedBannerViews.clear();
        recycleVelocityTracker();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (animatingClearAll || animatingSingleDismiss || openSesameActive || launchExitActive || getItemCount() == 0) {
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
        if (animatingClearAll || animatingSingleDismiss || openSesameActive || launchExitActive || getItemCount() == 0) {
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
        cancelAnimator(reflowAnimator);
        reflowStarts.clear();
        reflowProgress = 1f;
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
        flingInitialVelItemsPerSec = 0f;
        overshootBrakingActive = false;
        springSnapActive = false;
        springVelocityItemsPerSec = 0f;
        suppressContextMenuOnHorizontalSnap = false;
        rawScrollItemPos = unmapScrollPos(scrollItemPos, getMaxScrollItems());
        wheelSpeedItemsPerSec = 0f;
        lastVelSampleTimeMs = SystemClock.uptimeMillis();
        thumbnailsSuppressed = false;
        swipeStartX = downX;
        swipeStartOffsetPx = horizontalSwipePx;

        recycleVelocityTracker();
        velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);

        downVisualIndex = hitTestVisualIndex(downX, downY);
        if (showScrollbar && getMaxScrollItems() > 0.01f && downX <= scrollbarHitWidthPx && !isContextMenuOpen()) {
            dragMode = DragMode.SCROLLBAR_DRAG;
            if (callback != null) {
                callback.onUserInteracted();
            }
            setScrollbarEngaged(true);
            scrubToScrollbarY(downY, false);
            return;
        }
        if (!isContextMenuOpen() && downVisualIndex >= 0) {
            setGrabbedVisual(downVisualIndex, true);
            postDelayed(longPressRunnable, longPressTimeoutMs);
        }
    }

    private void handleActionMove(MotionEvent event) {
        if (velocityTracker != null) {
            velocityTracker.addMovement(event);
        }
        float x = event.getX();
        float y = event.getY();
        if (dragMode == DragMode.SCROLLBAR_DRAG) {
            scrubToScrollbarY(y, true);
            lastY = y;
            return;
        }
        float dx = x - downX;
        float dy = y - downY;

        if (longPressTriggered) {
            return;
        }

        if (dragMode == DragMode.NONE) {
            if (Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
                removeCallbacks(longPressRunnable);
                setGrabbedVisual(grabbedVisualIndex, false);
                if (isContextMenuOpen() || Math.abs(horizontalSwipePx) > 0.5f) {
                    animateHorizontalTo(0f);
                }
                dragMode = DragMode.SCROLL_VERTICAL;
                setScrollbarEngaged(true);
                showScrollbarTemporarily(true);
                if (callback != null) {
                    callback.onUserInteracted();
                }
            } else if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                if (dx < 0f && !curvedContextMenu && !isContextMenuOpen()) {
                    lastY = y;
                    return;
                }
                removeCallbacks(longPressRunnable);
                swipeStartX = downX + (dx >= 0f ? touchSlop : -touchSlop);
                swipeStartOffsetPx = horizontalSwipePx;
                if (dx < 0f) {
                    if (!isContextMenuOpen() && downVisualIndex >= 0) {
                        int targetAdapterPos = visualToAdapterPos(downVisualIndex);
                        if (targetAdapterPos >= 0 && targetAdapterPos != selectedAdapterPos) {
                            selectAdapterPosition(targetAdapterPos, selectedBanner);
                        }
                    }
                    setGrabbedVisual(downVisualIndex >= 0 ? downVisualIndex : adapterToVisualPos(selectedAdapterPos), true);
                    dragMode = DragMode.SWIPE_CONTEXT;
                } else if (isContextMenuOpen()) {
                    setGrabbedVisual(adapterToVisualPos(selectedAdapterPos), true);
                    dragMode = DragMode.SWIPE_CONTEXT;
                } else {
                    if (downVisualIndex >= 0) {
                        setGrabbedVisual(downVisualIndex, true);
                        int targetAdapterPos = visualToAdapterPos(downVisualIndex);
                        if (targetAdapterPos >= 0 && callback != null
                                && callback.canDismiss(adapter.getItem(targetAdapterPos))) {
                            dismissingVisualIndex = downVisualIndex;
                        }
                    } else {
                        setGrabbedVisual(grabbedVisualIndex, false);
                    }
                    dragMode = DragMode.SWIPE_DISMISS;
                }
                if (callback != null) {
                    callback.onUserInteracted();
                }
            }
        }

        if (dragMode == DragMode.SCROLL_VERTICAL) {
            boolean snapScroll = isSnapScrollEnabled();
            float stepDy = y - lastY;
            float pxPerItem = getSlotStepPx();
            float deltaItems = -stepDy / Math.max(1f, pxPerItem);
            float maxScroll = getMaxScrollItems();
            float oldPos = scrollItemPos;
            rawScrollItemPos += deltaItems;
            if (!tiltSwayOvershoot) {
                rawScrollItemPos = clamp(rawScrollItemPos, 0f, maxScroll);
            }
            scrollItemPos = applyWheelDetent(rawScrollItemPos, maxScroll);
            scrollSwayPx = tiltSwayOvershoot
                    ? clamp(-stepDy * 0.35f, -maxScrollSwayPx, maxScrollSwayPx)
                    : 0f;
            updateWheelSpeed(oldPos, scrollItemPos);
            thumbnailsSuppressed = isWheelMovingFast();
            showScrollbarTemporarily(true);
            scheduleThumbnailStabilize();
            updateAutoSelectionFromScroll(snapScroll);
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
        setGrabbedVisual(grabbedVisualIndex, false);

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

        boolean snapScroll = isSnapScrollEnabled();
        if (dragMode == DragMode.SCROLLBAR_DRAG) {
            setScrollbarEngaged(false);
            if (!snapScroll) {
                float maxScroll = getMaxScrollItems();
                scrollItemPos = clamp(scrollItemPos, 0f, maxScroll);
                rawScrollItemPos = unmapScrollPos(scrollItemPos, maxScroll);
                onWheelStabilized();
            } else {
                snapScrollToNearest(true);
            }
            releaseScrollSway();
        } else if (dragMode == DragMode.NONE) {
            if (isContextMenuOpen()) {
                animateHorizontalTo(0f);
            } else if (downVisualIndex >= 0) {
                int tappedAdapterPos = visualToAdapterPos(downVisualIndex);
                if (tappedAdapterPos >= 0 && tappedAdapterPos < getItemCount()) {
                    View tappedView = (selectedBanner && !hasCalculatedBannerSize() && tappedAdapterPos == selectedAdapterPos)
                            ? bannerView
                            : boundUnfocusedViews.get(downVisualIndex);
                    if (openSesame) {
                        startOpenSesameLaunch(adapter.getItem(tappedAdapterPos), tappedAdapterPos, tappedView);
                    } else {
                        if (selectedBanner) {
                            if (tappedAdapterPos == selectedAdapterPos) {
                                if (snapScroll) {
                                    snapScrollToNearest(false);
                                }
                            } else {
                                selectAdapterPosition(tappedAdapterPos, true);
                            }
                        }
                        if (callback != null) {
                            callback.onLaunchSelected(adapter.getItem(tappedAdapterPos), tappedView != null ? tappedView : bannerView);
                        }
                    }
                }
            } else if (snapScroll) {
                snapScrollToNearest(true);
            }
            if (Math.abs(horizontalSwipePx) > 0.5f && !isContextMenuOpen()) {
                animateHorizontalTo(0f);
            }
        } else if (dragMode == DragMode.SCROLL_VERTICAL) {
            float maxScroll = getMaxScrollItems();
            if (scrollItemPos < 0f || scrollItemPos > maxScroll) {
                if (tiltSwayOvershoot) {
                    snapScrollTo(clamp(scrollItemPos, 0f, maxScroll), true, true);
                    releaseScrollSway();
                } else {
                    scrollItemPos = clamp(scrollItemPos, 0f, maxScroll);
                    rawScrollItemPos = unmapScrollPos(scrollItemPos, maxScroll);
                    scrollSwayPx = 0f;
                    onWheelStabilized();
                    layoutCarousel();
                }
            } else if (!snapScroll) {
                if (Math.abs(vy) >= rawMinFlingVelocity && maxScroll > 0.001f) {
                    float pxPerItem = getSlotStepPx();
                    int startMilli = Math.round(scrollItemPos * 1000f);
                    int velMilli = Math.round((-vy / Math.max(1f, pxPerItem)) * 1000f);
                    int minFling = tiltSwayOvershoot ? -100000 : 0;
                    int maxFling = tiltSwayOvershoot
                            ? Math.round(maxScroll * 1000f) + 100000
                            : Math.round(maxScroll * 1000f);
                    scrollScroller.fling(0, startMilli, 0, velMilli, 0, 0, minFling, maxFling, 0, 0);
                    flingTargetVisualIdx = -1;
                    flingInitialVelItemsPerSec = Math.abs(velMilli / 1000f);
                    overshootBrakingActive = false;
                    springSnapActive = false;
                    postOnAnimation(flingTick);
                } else {
                    rawScrollItemPos = unmapScrollPos(scrollItemPos, maxScroll);
                    updateAutoSelectionFromScroll(false);
                    onWheelStabilized();
                    releaseScrollSway();
                }
            } else if (Math.abs(vy) >= minFlingVelocity) {
                int maxIdx = Math.max(0, getItemCount() - 1);
                float pxPerItem = getSlotStepPx();
                int startMilli = Math.round(scrollItemPos * 1000f);
                int velMilli = Math.round((-vy / Math.max(1f, pxPerItem)) * 900f);
                int minFling = tiltSwayOvershoot ? -100000 : 0;
                int maxFling = tiltSwayOvershoot ? maxIdx * 1000 + 100000 : maxIdx * 1000;
                scrollScroller.fling(0, startMilli, 0, velMilli, 0, 0, minFling, maxFling, 0, 0);
                int rawFinalY = scrollScroller.getFinalY();
                if (tiltSwayOvershoot && (rawFinalY < 0 || rawFinalY > maxIdx * 1000)) {
                    flingTargetVisualIdx = -1;
                    flingInitialVelItemsPerSec = Math.abs(velMilli / 1000f);
                    overshootBrakingActive = false;
                    springSnapActive = false;
                    postOnAnimation(flingTick);
                } else {
                    int currentIdx = Math.round(clamp(scrollItemPos, 0f, maxIdx));
                    int targetIdx = Math.round(clamp(rawFinalY / 1000f, 0f, maxIdx));
                    if (targetIdx == currentIdx) {
                        targetIdx = (int) clamp(currentIdx + (vy < 0f ? 1f : -1f), 0f, maxIdx);
                    }
                    float remaining = Math.abs(targetIdx - scrollItemPos);
                    if (remaining <= EARLY_MOMENTUM_KILL_DIST) {
                        scrollScroller.abortAnimation();
                        flingTargetVisualIdx = -1;
                        flingInitialVelItemsPerSec = 0f;
                        float dir = (targetIdx >= scrollItemPos ? 1f : -1f);
                        float transferVel = dir * clamp(Math.abs(velMilli / 1000f) * 0.50f, 1.8f, 6.2f);
                        startSpringSnap(targetIdx, transferVel);
                        releaseScrollSway();
                    } else {
                        int boundY = targetIdx * 1000;
                        int minBound = Math.min(startMilli, boundY);
                        int maxBound = Math.max(startMilli, boundY);
                        scrollScroller.abortAnimation();
                        scrollScroller.fling(0, startMilli, 0, velMilli, 0, 0, minBound, maxBound, 0, 0);
                        flingTargetVisualIdx = targetIdx;
                        flingInitialVelItemsPerSec = Math.abs(velMilli / 1000f);
                        springSnapActive = false;
                        postOnAnimation(flingTick);
                    }
                }
            } else if (Math.abs(vy) >= minFlingVelocity * 0.25f) {
                int maxIdx = Math.max(0, getItemCount() - 1);
                int nearest = Math.round(clamp(scrollItemPos, 0f, maxIdx));
                int dir = vy < 0f ? 1 : -1;
                int target = (nearest - scrollItemPos) * dir <= 0.18f
                        ? (int) clamp(nearest + dir, 0f, maxIdx)
                        : nearest;
                float pxPerItem = getSlotStepPx();
                float kickDir = (target >= scrollItemPos ? 1f : -1f);
                float startVel = kickDir * clamp(Math.abs(vy / pxPerItem) * 0.70f, 1.5f, 5.2f);
                startSpringSnap(target, startVel);
                releaseScrollSway();
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
        setGrabbedVisual(grabbedVisualIndex, false);
        boolean snapScroll = isSnapScrollEnabled();
        if (dragMode == DragMode.SCROLLBAR_DRAG) {
            setScrollbarEngaged(false);
            if (!snapScroll) {
                float maxScroll = getMaxScrollItems();
                scrollItemPos = clamp(scrollItemPos, 0f, maxScroll);
                rawScrollItemPos = unmapScrollPos(scrollItemPos, maxScroll);
                onWheelStabilized();
            } else {
                snapScrollToNearest(true);
            }
            releaseScrollSway();
        } else if (dragMode == DragMode.SCROLL_VERTICAL) {
            if (!snapScroll) {
                float maxScroll = getMaxScrollItems();
                if (scrollItemPos < 0f || scrollItemPos > maxScroll) {
                    if (tiltSwayOvershoot) {
                        snapScrollTo(clamp(scrollItemPos, 0f, maxScroll), true, true);
                    } else {
                        scrollItemPos = clamp(scrollItemPos, 0f, maxScroll);
                        rawScrollItemPos = unmapScrollPos(scrollItemPos, maxScroll);
                        onWheelStabilized();
                        layoutCarousel();
                    }
                } else {
                    rawScrollItemPos = unmapScrollPos(scrollItemPos, maxScroll);
                    onWheelStabilized();
                }
            } else {
                snapScrollToNearest(true);
            }
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
        if (dragMode == DragMode.NONE && !grabbed && !isAnimating()) {
            action.run();
        } else {
            pendingNotTouchedAction = action;
        }
    }

    public void cancelRunWhenNotTouched() {
        pendingNotTouchedAction = null;
    }

    private void flushPendingNotTouchedAction() {
        if (pendingNotTouchedAction != null && dragMode == DragMode.NONE && !grabbed && !isAnimating()) {
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
            layoutCarousel();
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
        if (isPlainListMode()) {
            return;
        }
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
            if (playHaptic && isSnapScrollEnabled()) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            startTransitionAnimations(previousPos);
            if (isContextMenuOpen()) {
                bindContextButtons();
            }
            if (selectedBanner && !thumbnailsSuppressed) {
                requestSnapshotsAroundSelection(false);
            }
        }
    }

    private void startTransitionAnimations(int previousAdapterPos) {
        if (!carouselDepthEffects || !selectedBanner || hasCalculatedBannerSize()) {
            shrinkingAdapterPos = -1;
            shrinkProgress = 1f;
            return;
        }
        if (previousAdapterPos >= 0 && previousAdapterPos != selectedAdapterPos) {
            shrinkingAdapterPos = previousAdapterPos;
            cancelAnimator(shrinkAnimator);
            shrinkProgress = 0f;
            shrinkAnimator = ValueAnimator.ofFloat(0f, 1f);
            boolean snapScroll = isSnapScrollEnabled();
            shrinkAnimator.setDuration(snapScroll ? 165L : 190L);
            shrinkAnimator.setInterpolator(snapScroll ? DECELERATE : FAST_OUT_SLOW_IN);
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
        return flingTargetVisualIdx >= 0
                || wheelSpeedItemsPerSec >= FAST_WHEEL_ITEMS_PER_SEC
                || (springSnapActive && Math.abs(springVelocityItemsPerSec) >= FAST_WHEEL_ITEMS_PER_SEC);
    }

    private void scheduleThumbnailStabilize() {
        removeCallbacks(stabilizeThumbnailsRunnable);
        postDelayed(stabilizeThumbnailsRunnable, THUMBNAIL_STABILIZE_DELAY_MS);
    }

    private void onWheelStabilized() {
        removeCallbacks(stabilizeThumbnailsRunnable);
        wheelSpeedItemsPerSec = 0f;
        thumbnailsSuppressed = false;
        setScrollbarEngaged(false);
        showScrollbarTemporarily(false);
        if (getItemCount() > 0 && selectedBanner && selectedAdapterPos >= 0) {
            if (bannerBoundLightweight) {
                rebindBanner();
            }
            if (hasMoreBanners()) {
                for (int i = 0; i < boundUnfocusedViews.size(); i++) {
                    View uv = boundUnfocusedViews.valueAt(i);
                    if (isUnfocusedBannerView(uv)) {
                        int aPos = visualToAdapterPos(boundUnfocusedViews.keyAt(i));
                        if (aPos >= 0 && aPos < getItemCount()) {
                            Result<?> r = adapter.getItem(aPos);
                            if (r instanceof AppResult) {
                                AppResult appResult = (AppResult) r;
                                Bitmap cached = getResolvedUnfocusedSnapshot(appResult);
                                appResult.displayUnfocusedBanner(getContext(), uv, cached, greyscaleSnapshots, false);
                                if (cached == null && !(clearDeadThumbnails && appResult.getPojo().rssKb <= 0L)) {
                                    requestSnapshotForAdapterPos(aPos, false, false);
                                }
                            }
                        }
                    }
                }
            }
            requestSnapshotsAroundSelection(true);
            layoutCarousel();
        }
    }

    private void startSpringSnap(float targetVisualPos, float initialVelocityItemsPerSec) {
        removeCallbacks(flingTick);
        cancelAnimator(scrollAnimator);
        overshootBrakingActive = false;
        if (!scrollScroller.isFinished()) {
            scrollScroller.abortAnimation();
        }
        springTargetPos = targetVisualPos;
        springVelocityItemsPerSec = initialVelocityItemsPerSec;
        if (Math.abs(springTargetPos - scrollItemPos) < SPRING_STOP_POS_EPS
                && Math.abs(springVelocityItemsPerSec) < SPRING_STOP_VEL_EPS) {
            scrollItemPos = springTargetPos;
            springSnapActive = false;
            springVelocityItemsPerSec = 0f;
            updateAutoSelectionFromScroll(false);
            onWheelStabilized();
            layoutCarousel();
            return;
        }
        springSnapActive = true;
        lastSpringTickMs = SystemClock.uptimeMillis();
        postOnAnimation(flingTick);
    }

    private void snapScrollToNearest(boolean elastic) {
        int count = getItemCount();
        if (count == 0) {
            return;
        }
        int target = Math.round(clamp(scrollItemPos, 0f, getMaxScrollItems()));
        snapScrollTo(target, elastic, true);
    }

    private void snapScrollTo(float targetVisualPos, boolean elastic, final boolean updateAutoSelect) {
        overshootBrakingActive = false;
        springSnapActive = false;
        springVelocityItemsPerSec = 0f;
        removeCallbacks(flingTick);
        if (Math.abs(scrollItemPos - targetVisualPos) < 0.002f) {
            scrollItemPos = targetVisualPos;
            if (updateAutoSelect) {
                updateAutoSelectionFromScroll(false);
            }
            onWheelStabilized();
            layoutCarousel();
            return;
        }
        float dist = Math.abs(targetVisualPos - scrollItemPos);
        boolean isEdgeSpring = elastic && (scrollItemPos < 0f || scrollItemPos > getMaxScrollItems());
        boolean useElastic = elastic && tiltSwayOvershoot;
        long duration = isEdgeSpring ? 210L : (useElastic ? Math.round(clamp(175f + dist * 40f, 180f, 235f)) : 185L);
        cancelAnimator(scrollAnimator);
        scrollAnimator = ValueAnimator.ofFloat(scrollItemPos, targetVisualPos);
        scrollAnimator.setDuration(duration);
        scrollAnimator.setInterpolator(useElastic ? ELASTIC_SNAP : FAST_OUT_SLOW_IN);
        scrollAnimator.addUpdateListener(anim -> {
            scrollItemPos = (float) anim.getAnimatedValue();
            if (updateAutoSelect) {
                updateAutoSelectionFromScroll(isSnapScrollEnabled());
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

    private void setGrabbedVisual(int visualIdx, final boolean nextGrabbed) {
        if (!carouselDepthEffects) {
            cancelAnimator(grabAnimator);
            grabbed = false;
            grabbedVisualIndex = -1;
            grabScale = 1f;
            return;
        }
        if (nextGrabbed) {
            if (visualIdx < 0) {
                return;
            }
            if (grabbed && grabbedVisualIndex == visualIdx) {
                return;
            }
            grabbed = true;
            grabbedVisualIndex = visualIdx;
        } else {
            if (!grabbed) {
                return;
            }
            grabbed = false;
        }
        cancelAnimator(grabAnimator);
        grabAnimator = ValueAnimator.ofFloat(grabScale, nextGrabbed ? GRAB_SCALE : 1.0f);
        grabAnimator.setDuration(nextGrabbed ? 90 : 170);
        grabAnimator.setInterpolator(nextGrabbed ? DECELERATE : (tiltSwayOvershoot ? OVERSHOOT : DECELERATE));
        grabAnimator.addUpdateListener(anim -> {
            grabScale = (float) anim.getAnimatedValue();
            layoutCarousel();
        });
        grabAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!grabbed) {
                    grabbedVisualIndex = -1;
                    grabScale = 1f;
                }
                flushPendingNotTouchedAction();
            }
        });
        grabAnimator.start();
    }

    private void releaseScrollSway() {
        if (!tiltSwayOvershoot || Math.abs(scrollSwayPx) < 0.5f) {
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
        horizontalAnimator.setInterpolator(tiltSwayOvershoot ? ELASTIC_SNAP : FAST_OUT_SLOW_IN);
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
        int count = getItemCount();
        if (adapterPos < 0 || adapterPos >= count) {
            dismissingVisualIndex = -1;
            dismissingDx = 0f;
            return;
        }
        final Result<?> dismissedResult = adapter.getItem(adapterPos);
        if (adapterPos == selectedAdapterPos && count > 1) {
            int nextVisual = (visualIndex + 1 < count) ? (visualIndex + 1) : (visualIndex - 1);
            int nextIdx = visualToAdapterPos(nextVisual);
            pendingDismissReplacementPojoId = nextIdx >= 0 ? adapter.getItem(nextIdx).getPojoId() : null;
        } else {
            pendingDismissReplacementPojoId = null;
        }
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
                captureReflowStarts(visualIndex, null);
                if (pendingDismissReplacementPojoId != null) {
                    selectedPojoId = pendingDismissReplacementPojoId;
                    pendingDismissReplacementPojoId = null;
                }
                pendingReflowAfterDismiss = true;
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

    private void captureReflowStarts(int dismissedVisualIdx, @Nullable Set<Result<?>> excludedResults) {
        reflowStarts.clear();
        int width = getWidth();
        int height = getHeight();
        int count = getItemCount();
        if (width <= 0 || height <= 0 || count == 0) {
            return;
        }
        boolean plainList = isPlainListMode();
        float focalY = height * 0.52f;
        float arcRadius = Math.max(height * 0.95f, DrawableUtils.dpToPx(getContext(), 520f));
        float stepPx = getSlotStepPx();
        int selectedVisualIdx = selectedBanner ? adapterToVisualPos(selectedAdapterPos) : -1;
        boolean bannerGrabbed = (carouselDepthEffects && selectedVisualIdx >= 0 && grabbedVisualIndex == selectedVisualIdx);
        float bannerScale = bannerGrabbed ? grabScale : 1f;
        float grabInset = selectedBanner ? (bannerHeightPx * 0.5f * (1f - bannerScale)) : 0f;
        int baseUnfocusedH = hasAllUnfocusedBanners() ? unfocusedBannerHeightPx : unfocusedHeightPx;
        float extraHalf = selectedBanner ? Math.max(0f, (bannerHeightPx - baseUnfocusedH) * 0.5f - grabInset) : 0f;
        int unfocusedLeftMargin = (carouselArc || selectedBanner) ? horizontalMarginPx : 0;

        boolean calcSize = hasCalculatedBannerSize();
        for (int v = 0; v < count; v++) {
            if (dismissedVisualIdx >= 0 && v == dismissedVisualIdx) {
                continue;
            }
            int adapterPos = visualToAdapterPos(v);
            if (adapterPos < 0) {
                continue;
            }
            Result<?> item = adapter.getItem(adapterPos);
            if (excludedResults != null && excludedResults.contains(item)) {
                continue;
            }
            String pojoId = item.getPojoId();
            View child = (selectedBanner && !calcSize && v == selectedVisualIdx) ? bannerView : boundUnfocusedViews.get(v);
            if (child != null && child.getParent() == this && child.getVisibility() == View.VISIBLE) {
                float centerY = child.getTop() + child.getTranslationY() + child.getHeight() * 0.5f;
                float screenLeft = child.getLeft() + child.getTranslationX();
                float visualHeight = child.getHeight() * child.getScaleY();
                reflowStarts.put(pojoId, new ReflowStart(centerY, screenLeft, visualHeight, child.getAlpha()));
            } else if (plainList) {
                float centerY = computePlainListCenterY(v, count, height);
                reflowStarts.put(pojoId, new ReflowStart(centerY, 0f, unfocusedHeightPx, 1f));
            } else {
                float centerY = computeSlotCenterY(v, focalY, stepPx, extraHalf);
                float dy = centerY - focalY;
                float normDist = Math.min(1.4f, Math.abs(dy) / Math.max(1f, height * 0.48f));
                float arcOffset = 0f;
                if (carouselArc) {
                    double underSqrt = Math.max(0.0, (double) arcRadius * arcRadius - (double) dy * dy);
                    arcOffset = (float) (arcRadius - Math.sqrt(underSqrt)) * 1.65f;
                    arcOffset = Math.min(arcOffset, width * 0.36f);
                }
                boolean isSel = selectedBanner && !calcSize && (v == selectedVisualIdx);
                float scale = isSel ? 1f
                        : (carouselDepthEffects
                            ? (calcSize ? Math.max(0.78f, 1.0f - normDist * 0.20f) : Math.max(0.78f, 0.95f - normDist * 0.14f))
                            : 1f);
                float adjFactor = isSel ? 1f : getAdjacentBannerFactor(v);
                float dSlot = Math.abs(v - scrollItemPos);
                float left;
                int slotH;
                if (calcSize) {
                    left = unfocusedLeftMargin + arcOffset;
                    slotH = Math.round(getCalculatedBannerHeight(dSlot));
                } else {
                    left = isSel ? bannerHorizontalMarginPx : unfocusedLeftMargin + arcOffset;
                    slotH = isSel ? bannerHeightPx
                            : Math.round(unfocusedHeightPx + (unfocusedBannerHeightPx - unfocusedHeightPx) * adjFactor);
                }
                float h = slotH * scale;
                float alpha = isSel ? 1f : (carouselDepthEffects ? Math.max(0.55f, 1.0f - normDist * 0.28f) : 1f);
                reflowStarts.put(pojoId, new ReflowStart(centerY, left, h, alpha));
            }
        }
    }

    private void startReflowAnimation() {
        cancelAnimator(reflowAnimator);
        reflowProgress = 0f;
        reflowAnimator = ValueAnimator.ofFloat(0f, 1f);
        reflowAnimator.setDuration(REFLOW_DURATION_MS);
        reflowAnimator.setInterpolator(FAST_OUT_SLOW_IN);
        reflowAnimator.addUpdateListener(anim -> {
            reflowProgress = (float) anim.getAnimatedValue();
            layoutCarousel();
        });
        reflowAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                reflowProgress = 1f;
                reflowStarts.clear();
                layoutCarousel();
                flushPendingNotTouchedAction();
            }
        });
        reflowAnimator.start();
    }

    private void layoutCarousel() {
        int width = getWidth();
        int height = getHeight();
        int count = getItemCount();
        if (width <= 0 || height <= 0 || count == 0) {
            return;
        }

        boolean plainList = isPlainListMode();
        if (plainList && pendingPlainListInitialScroll) {
            scrollItemPos = reverseOrder ? 0f : getPlainListMaxScrollItems(count, height);
            rawScrollItemPos = unmapScrollPos(scrollItemPos, getMaxScrollItems());
            pendingPlainListInitialScroll = false;
        }
        boolean calculatedSizeMode = hasCalculatedBannerSize();
        if ((!selectedBanner || calculatedSizeMode) && !openSesameActive && bannerView != null && bannerView.getVisibility() != View.GONE) {
            bannerView.setVisibility(View.GONE);
        }

        int unfocusedLeftMargin = (carouselArc || selectedBanner) ? horizontalMarginPx : 0;
        int unfocusedWidth = Math.max(1, width - 2 * unfocusedLeftMargin);
        int bannerWidth = Math.max(1, width - 2 * bannerHorizontalMarginPx);
        float focalY = height * 0.52f;
        float arcRadius = Math.max(height * 0.95f, DrawableUtils.dpToPx(getContext(), 520f));
        float stepPx = getSlotStepPx();
        int selectedVisualIdx = selectedBanner ? adapterToVisualPos(selectedAdapterPos) : -1;
        boolean bannerGrabbed = (carouselDepthEffects && selectedVisualIdx >= 0 && grabbedVisualIndex == selectedVisualIdx);
        float bannerGrabScale = bannerGrabbed ? grabScale : 1f;
        float grabInset = selectedBanner ? (bannerHeightPx * 0.5f * (1f - bannerGrabScale)) : 0f;
        int baseUnfocusedH = hasAllUnfocusedBanners() ? unfocusedBannerHeightPx : unfocusedHeightPx;
        float extraHalf = selectedBanner ? Math.max(0f, (bannerHeightPx - baseUnfocusedH) * 0.5f - grabInset) : 0f;
        boolean reflowActive = reflowProgress < 1f && !reflowStarts.isEmpty();

        int shrinkingVisualIdx = (carouselDepthEffects && selectedBanner && !calculatedSizeMode) ? adapterToVisualPos(shrinkingAdapterPos) : -1;
        float sesameFadeOut = openSesameActive ? (1f - clamp(openSesameProgress * 1.8f, 0f, 1f)) : 1f;
        float cullMargin = calculatedSizeMode ? calculatedFullBannerHeightPx : (selectedBanner ? bannerHeightPx : unfocusedHeightPx);

        // Track off-screen views to detach & recycle cleanly
        for (int i = boundUnfocusedViews.size() - 1; i >= 0; i--) {
            int v = boundUnfocusedViews.keyAt(i);
            if (v < 0 || v >= count || (selectedBanner && !calculatedSizeMode && v == selectedVisualIdx)) {
                View stale = boundUnfocusedViews.valueAt(i);
                boundUnfocusedViews.removeAt(i);
                recycleUnfocusedView(stale);
                continue;
            }
            float centerY = plainList
                    ? computePlainListCenterY(v, count, height)
                    : computeSlotCenterY(v, focalY, stepPx, extraHalf);
            if (reflowActive) {
                ReflowStart start = reflowStarts.get(adapter.getItem(visualToAdapterPos(v)).getPojoId());
                if (start != null) {
                    centerY = start.centerY + (centerY - start.centerY) * reflowProgress;
                }
            }
            if (centerY < -cullMargin || centerY > height + cullMargin) {
                View stale = boundUnfocusedViews.valueAt(i);
                boundUnfocusedViews.removeAt(i);
                recycleUnfocusedView(stale);
            }
        }

        float selectedScreenCenterY = focalY;
        float horizontalRubberRange = Math.max(160f, width * 0.75f);
        boolean entranceActive = launchEntranceAnim && (pendingLaunchEntrance || launchEntranceProgress < 1f) && !openSesameActive;
        for (int v = 0; v < count; v++) {
            int adapterPos = visualToAdapterPos(v);
            float targetCenterY = plainList
                    ? computePlainListCenterY(v, count, height)
                    : computeSlotCenterY(v, focalY, stepPx, extraHalf);
            ReflowStart reflowStart = reflowActive ? reflowStarts.get(adapter.getItem(adapterPos).getPojoId()) : null;
            float centerY = reflowStart != null
                    ? reflowStart.centerY + (targetCenterY - reflowStart.centerY) * reflowProgress
                    : targetCenterY;

            boolean isSelected = selectedBanner && (v == selectedVisualIdx);
            if (isSelected) {
                selectedScreenCenterY = centerY;
                if (openSesameActive && !calculatedSizeMode) {
                    continue;
                }
            }
            if (centerY < -cullMargin || centerY > height + cullMargin) {
                if (isSelected && !calculatedSizeMode && bannerView != null) {
                    bannerView.setVisibility(View.INVISIBLE);
                }
                continue;
            }

            View child;
            int childLeft;
            int childWidth;
            int childHeight;
            float adjFactor = 0f;
            boolean useUnfocusedBanner = false;
            float distFromFocal = Math.abs(v - scrollItemPos);
            if (calculatedSizeMode) {
                adjFactor = 1f;
                useUnfocusedBanner = true;
                child = obtainAndBindUnfocused(v, adapterPos, true);
                childLeft = unfocusedLeftMargin;
                childWidth = unfocusedWidth;
                childHeight = Math.round(getCalculatedBannerHeight(distFromFocal));
            } else if (isSelected) {
                child = obtainAndBindBanner(adapterPos);
                child.setVisibility(View.VISIBLE);
                childLeft = bannerHorizontalMarginPx;
                childWidth = bannerWidth;
                childHeight = bannerHeightPx;
            } else {
                adjFactor = getAdjacentBannerFactor(v);
                useUnfocusedBanner = adjFactor > 0.02f;
                child = obtainAndBindUnfocused(v, adapterPos, useUnfocusedBanner);
                childLeft = unfocusedLeftMargin;
                childWidth = unfocusedWidth;
                childHeight = useUnfocusedBanner
                        ? Math.round(unfocusedHeightPx + (unfocusedBannerHeightPx - unfocusedHeightPx) * adjFactor)
                        : unfocusedHeightPx;
            }

            layoutChildAt(child, childLeft, Math.round(centerY - childHeight * 0.5f), childWidth, childHeight);

            float dy = targetCenterY - focalY;
            float normDist = Math.min(1.4f, Math.abs(dy) / Math.max(1f, height * 0.48f));
            float arcOffset = 0f;
            if (!plainList && carouselArc) {
                double underSqrt = Math.max(0.0, (double) arcRadius * arcRadius - (double) dy * dy);
                arcOffset = (float) (arcRadius - Math.sqrt(underSqrt)) * 1.65f;
                arcOffset = Math.min(arcOffset, width * 0.36f);
            }

            boolean isDismissTarget = (v == dismissingVisualIndex);
            float dismissAlpha = isDismissTarget
                    ? Math.max(0f, 1f - dismissingDx / (Math.max(1f, width) * 0.8f))
                    : 1f;

            float clearAllDx = 0f;
            float clearAllAlpha = 1f;
            if (animatingClearAll) {
                int dismissRank = clearAllDismissRanks.get(v, -1);
                if (dismissRank >= 0) {
                    float elapsedMs = clearAllProgress * clearAllTotalDurationMs;
                    float localP = clamp((elapsedMs - dismissRank * CLEAR_ALL_STAGGER_MS) / (float) CLEAR_ALL_SLIDE_DURATION_MS, 0f, 1f);
                    float eased = ACCELERATE.getInterpolation(localP);
                    clearAllDx = Math.max(width, 800) * eased;
                    clearAllAlpha = Math.max(0f, 1f - clearAllDx / (Math.max(1f, width) * 0.8f));
                }
            }

            float horizTx;
            if (clearAllDx > 0f) {
                horizTx = clearAllDx;
            } else if (isDismissTarget && (animatingSingleDismiss || horizontalSwipePx > 0f)) {
                horizTx = dismissingDx;
            } else if (horizontalSwipePx < 0f && curvedContextMenu) {
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
            } else if (horizontalSwipePx > 0f && tiltSwayOvershoot) {
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

            float targetScale;
            float targetAlpha;
            float targetTx;
            float targetRotation;
            if (isSelected && !calculatedSizeMode) {
                float swayTx = tiltSwayOvershoot ? scrollSwayPx * 0.45f : 0f;
                targetScale = bannerGrabScale;
                targetAlpha = dismissAlpha * clearAllAlpha;
                targetTx = horizTx + swayTx;
                targetRotation = tiltSwayOvershoot ? clamp(scrollSwayPx * 0.06f, -1.2f, 1.2f) : 0f;
            } else if (calculatedSizeMode) {
                float scale = carouselDepthEffects ? Math.max(0.78f, 1.0f - normDist * 0.20f) : 1f;
                if (carouselDepthEffects && v == grabbedVisualIndex) {
                    scale *= grabScale;
                }
                float baseAlpha = carouselDepthEffects ? Math.max(0.55f, 1.0f - normDist * 0.28f) : 1f;
                float swayTx = tiltSwayOvershoot ? scrollSwayPx * (0.50f + Math.min(1f, normDist) * 0.45f) : 0f;
                targetScale = scale;
                targetAlpha = baseAlpha * dismissAlpha * clearAllAlpha * sesameFadeOut;
                targetTx = arcOffset + horizTx + swayTx;
                targetRotation = tiltSwayOvershoot ? clamp(scrollSwayPx * 0.07f, -1.4f, 1.4f) : 0f;
            } else {
                float scale = carouselDepthEffects ? Math.max(0.78f, 0.95f - normDist * 0.14f) : 1f;
                if (carouselDepthEffects && v == shrinkingVisualIdx && shrinkProgress < 1f) {
                    float shrinkBoost = isSnapScrollEnabled() ? 0.28f : 0.10f;
                    scale *= (1f + shrinkBoost * (1f - clamp(shrinkProgress, 0f, 1f)));
                }
                if (carouselDepthEffects && v == grabbedVisualIndex) {
                    scale *= grabScale;
                }
                float baseAlpha = carouselDepthEffects ? Math.max(0.55f, 1.0f - normDist * 0.28f) : 1f;
                float swayTx = tiltSwayOvershoot ? scrollSwayPx * (0.65f + normDist * 0.35f) : 0f;
                targetScale = scale;
                targetAlpha = baseAlpha * dismissAlpha * clearAllAlpha * sesameFadeOut;
                targetTx = arcOffset + horizTx + swayTx;
                targetRotation = tiltSwayOvershoot ? clamp(scrollSwayPx * 0.08f, -1.4f, 1.4f) : 0f;
            }

            float finalScale = targetScale;
            float finalAlpha = targetAlpha;
            float finalTx = targetTx;
            if (reflowStart != null) {
                float startScale = reflowStart.visualHeight / Math.max(1f, childHeight);
                finalScale = startScale + (targetScale - startScale) * reflowProgress;
                float targetScreenLeft = childLeft + targetTx;
                float renderScreenLeft = reflowStart.screenLeft + (targetScreenLeft - reflowStart.screenLeft) * reflowProgress;
                finalTx = renderScreenLeft - childLeft;
                finalAlpha = reflowStart.alpha + (targetAlpha - reflowStart.alpha) * reflowProgress;
            }
            if (launchExitActive) {
                float distSlots = getSpreadDistanceFromCenter(v, count, targetCenterY, height, plainList, selectedVisualIdx);
                float slideStagger = distSlots * 0.045f;
                float slideWindow = Math.max(0.50f, 1f - slideStagger);
                float slideRaw = clamp((launchExitProgress - slideStagger) / slideWindow, 0f, 1f);
                float slideEased = EXIT_EASE_IN.getInterpolation(slideRaw);
                float fadeRaw = clamp((launchExitProgress - slideStagger) / Math.max(0.45f, slideWindow * 0.90f), 0f, 1f);
                float fadeEased = FAST_OUT_SLOW_IN.getInterpolation(fadeRaw);
                finalAlpha *= (1f - fadeEased);
                finalTx += launchSlideDistancePx * slideEased;
            } else if (entranceActive) {
                float entProgress = pendingLaunchEntrance ? 0f : launchEntranceProgress;
                float distSlots = getSpreadDistanceFromCenter(v, count, targetCenterY, height, plainList, selectedVisualIdx);
                float slideStagger = distSlots * 0.055f;
                float slideWindow = Math.max(0.45f, 1f - slideStagger);
                float slideRaw = clamp((entProgress - slideStagger) / slideWindow, 0f, 1f);
                float slideEased = ENTRANCE_EASE_OUT.getInterpolation(slideRaw);
                float fadeDelay = slideStagger + distSlots * 0.028f;
                float fadeWindow = Math.max(0.45f, 1f - fadeDelay);
                float fadeRaw = clamp((entProgress - fadeDelay) / fadeWindow, 0f, 1f);
                float fadeEased = ENTRANCE_EASE_OUT.getInterpolation(fadeRaw);
                finalAlpha *= fadeEased;
                finalTx += launchSlideDistancePx * (1f - slideEased);
            }

            child.setPivotX(0f);
            child.setPivotY(childHeight * 0.5f);
            child.setScaleX(finalScale);
            child.setScaleY(finalScale);
            child.setAlpha(finalAlpha);
            child.setTranslationX(finalTx);
            child.setRotation(targetRotation);
            if (isSelected && !calculatedSizeMode) {
                AppResult.BannerViewHolder selHolder = AppResult.obtainBannerHolder(child, true);
                if (selHolder.outlineView != null) {
                    selHolder.outlineView.setAlpha(1f);
                }
                applyBannerThumbnailParallax(centerY - focalY, finalTx);
            } else if (useUnfocusedBanner) {
                float fallbackScale = 1f;
                if (calculatedSizeMode) {
                    float tComp = clamp(distFromFocal - 1f, 0f, 1f);
                    fallbackScale = 1f - tComp * tComp * (3f - 2f * tComp);
                }
                applyUnfocusedCollapseVisuals(child, adjFactor, fallbackScale);
                applyCardParallax(child, false, centerY - focalY, finalTx);
            }
        }

        if (openSesameActive) {
            applyOpenSesameLayout(width);
        }

        lastSelectedScreenCenterY = selectedScreenCenterY;
        if (!openSesameActive && contextMenuLayer.getVisibility() != View.GONE) {
            layoutChildFrame(contextMenuLayer, 0, 0, width, height);
            layoutContextMenuButtons(selectedScreenCenterY);
        }
        invalidateViewport();
        if (showScrollbar || shouldShowSectionSeparators()) {
            invalidate();
        }
    }

    private float getSpreadDistanceFromCenter(int visualIdx, int count, float targetCenterY, int height,
                                              boolean plainList, int selectedVisualIdx) {
        if (!plainList) {
            float centerSlot = selectedVisualIdx >= 0
                    ? selectedVisualIdx
                    : clamp(scrollItemPos, 0f, Math.max(0, count - 1));
            return Math.min(5f, Math.abs(visualIdx - centerSlot));
        }
        float contentH = getPlainListContentHeight(count);
        float midScreenY = contentH <= height
                ? (reverseOrder ? contentH * 0.5f : height - contentH * 0.5f)
                : height * 0.5f;
        return Math.min(5f, Math.abs(targetCenterY - midScreenY) / Math.max(1f, unfocusedHeightPx));
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
        if (hasCalculatedBannerSize() && !openSesameActive) {
            return;
        }
        if (bannerView != null && selectedAdapterPos >= 0 && selectedAdapterPos < getItemCount()) {
            bindBannerAt(selectedAdapterPos);
            applyBannerThumbnailParallax(lastBannerParallaxDy, lastBannerParallaxTx);
        }
    }

    private void bindBannerAt(int adapterPos) {
        if (bannerView == null || adapterPos < 0 || adapterPos >= getItemCount()) {
            return;
        }
        bannerBoundAdapterPos = adapterPos;
        boolean skipHeavy = thumbnailsSuppressed;
        Result<?> result = adapter.getItem(adapterPos);
        if (result instanceof AppResult) {
            AppResult appResult = (AppResult) result;
            Bitmap cachedSnapshot = getResolvedBannerSnapshot(appResult);
            bannerBoundLightweight = skipHeavy && (cachedSnapshot == null || cachedSnapshot.isRecycled());
            boolean calcMode = hasCalculatedBannerSize();
            appResult.displayBanner(getContext(), bannerView, cachedSnapshot, greyscaleSnapshots, skipHeavy,
                    selectedBannerIcon || calcMode, !selectedBannerOutline || calcMode);
        } else {
            bannerBoundLightweight = skipHeavy;
        }
    }

    @Nullable
    private Bitmap getResolvedBannerSnapshot(@NonNull AppResult app) {
        if (clearDeadThumbnails && app.getPojo().rssKb <= 0L) {
            return null;
        }
        return TaskSnapshotCache.getBest(app.getPojo().taskId);
    }

    @Nullable
    private Bitmap getResolvedUnfocusedSnapshot(@NonNull AppResult app) {
        if (clearDeadThumbnails && app.getPojo().rssKb <= 0L) {
            return null;
        }
        return TaskSnapshotCache.getThumb(app.getPojo().taskId);
    }

    private void applyBannerThumbnailParallax(float dyFromFocal, float cardTx) {
        lastBannerParallaxDy = dyFromFocal;
        lastBannerParallaxTx = cardTx;
        applyCardParallax(bannerView, true, dyFromFocal, cardTx);
    }

    private void applyCardParallax(@Nullable View cardView, boolean isFocusedBanner, float dyFromFocal, float cardTx) {
        if (cardView == null) {
            return;
        }
        AppResult.BannerViewHolder holder = AppResult.obtainBannerHolder(cardView, isFocusedBanner);
        int vw = cardView.getWidth();
        int vh = cardView.getHeight();
        float sesameScale = openSesameActive && isFocusedBanner ? clamp(openSesameProgress, 0f, 1f) : 0f;

        ImageView snapshotView = holder.snapshotView;
        if (snapshotView != null && snapshotView.getVisibility() == View.VISIBLE && snapshotView.getDrawable() != null) {
            if (snapshotView.getScaleType() != ImageView.ScaleType.CENTER_CROP) {
                snapshotView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            }
            if (vw > 0 && vh > 0) {
                snapshotView.setPivotX(vw * 0.5f);
                snapshotView.setPivotY(vh * 0.5f);
            }
            if (!thumbnailParallax) {
                snapshotView.setScaleX(1f);
                snapshotView.setScaleY(1f);
                snapshotView.setTranslationX(0f);
                snapshotView.setTranslationY(0f);
            } else {
                float overScale = 1.26f - 0.26f * sesameScale;
                snapshotView.setScaleX(overScale);
                snapshotView.setScaleY(overScale);
                if (vw > 0 && vh > 0) {
                    float slackX = Math.max(0f, vw * (overScale - 1f) * 0.5f);
                    float slackY = Math.max(0f, vh * (overScale - 1f) * 0.5f);
                    snapshotView.setTranslationX(clamp(-cardTx * 0.22f, -slackX, slackX));
                    snapshotView.setTranslationY(clamp(-dyFromFocal * 0.18f, -slackY, slackY));
                }
            }
        }

        ImageView fallbackIconView = holder.fallbackIconView;
        if (fallbackIconView != null && fallbackIconView.getVisibility() == View.VISIBLE) {
            if (!thumbnailParallax) {
                fallbackIconView.setTranslationX(0f);
                fallbackIconView.setTranslationY(0f);
            } else if (vw > 0 && vh > 0) {
                float iconFactor = 1f - sesameScale;
                float maxIconPanX = vw * 0.08f * iconFactor;
                float maxIconPanY = Math.max(DrawableUtils.dpToPx(getContext(), 12f), vh * 0.18f) * iconFactor;
                fallbackIconView.setTranslationX(clamp(-cardTx * 0.20f, -maxIconPanX, maxIconPanX));
                fallbackIconView.setTranslationY(clamp(-dyFromFocal * 0.16f, -maxIconPanY, maxIconPanY));
            }
        }
    }

    private static void applyUnfocusedCollapseVisuals(@NonNull View cardView, float factor, float fallbackScale) {
        float clamped = clamp(factor, 0f, 1f);
        AppResult.BannerViewHolder holder = AppResult.obtainBannerHolder(cardView, false);
        if (holder.snapshotView != null) {
            holder.snapshotView.setAlpha(clamped);
        }
        if (holder.scrimView != null) {
            holder.scrimView.setAlpha(clamped);
        }
        if (holder.fallbackIconView != null && holder.fallbackIconView.getVisibility() == View.VISIBLE) {
            holder.fallbackIconView.setAlpha(0.28f * clamped * clamp(fallbackScale, 0f, 1f));
        }
    }

    private View obtainAndBindUnfocused(int visualIdx, int adapterPos, boolean wantBanner) {
        View view = boundUnfocusedViews.get(visualIdx);
        if (view != null) {
            if (isUnfocusedBannerView(view) == wantBanner) {
                if (wantBanner && !thumbnailsSuppressed) {
                    Result<?> item = adapter.getItem(adapterPos);
                    if (item instanceof AppResult) {
                        AppResult appResult = (AppResult) item;
                        AppResult.BannerViewHolder holder = AppResult.obtainBannerHolder(view, false);
                        Bitmap cached = getResolvedUnfocusedSnapshot(appResult);
                        if (!holder.boundValid
                                || holder.boundLightweight
                                || holder.boundGreyscale != greyscaleSnapshots
                                || !appResult.getPojoId().equals(holder.boundPojoId)
                                || holder.boundSnapshot != cached) {
                            appResult.displayUnfocusedBanner(getContext(), view, cached, greyscaleSnapshots, false);
                        }
                    }
                }
                return view;
            }
            boundUnfocusedViews.remove(visualIdx);
            recycleUnfocusedView(view);
        }
        View bound;
        if (wantBanner) {
            View convertView = recycledUnfocusedBannerViews.isEmpty()
                    ? null
                    : recycledUnfocusedBannerViews.remove(recycledUnfocusedBannerViews.size() - 1);
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_app_unfocused_banner, this, false);
                convertView.setTag(R.id.unfocused_banner_icon, Boolean.TRUE);
            }
            Result<?> item = adapter.getItem(adapterPos);
            if (item instanceof AppResult) {
                AppResult appResult = (AppResult) item;
                Bitmap cached = getResolvedUnfocusedSnapshot(appResult);
                appResult.displayUnfocusedBanner(getContext(), convertView, cached, greyscaleSnapshots, thumbnailsSuppressed);
            } else {
                convertView = item.display(getContext(), null, this, null);
            }
            bound = convertView;
        } else {
            View convertView = recycledUnfocusedViews.isEmpty()
                    ? null
                    : recycledUnfocusedViews.remove(recycledUnfocusedViews.size() - 1);
            bound = adapter.getItem(adapterPos).display(getContext(), convertView, this, null);
        }
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
            bannerView.setTranslationY(0f);
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
        view.setTranslationY(0f);
        view.setRotation(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setAlpha(1f);
        detachChild(view);
        if (isUnfocusedBannerView(view)) {
            AppResult.BannerViewHolder holder = AppResult.obtainBannerHolder(view, false);
            if (holder.snapshotView != null) {
                holder.snapshotView.setAlpha(1f);
                holder.snapshotView.setScaleX(1f);
                holder.snapshotView.setScaleY(1f);
                holder.snapshotView.setTranslationX(0f);
                holder.snapshotView.setTranslationY(0f);
            }
            if (holder.scrimView != null) {
                holder.scrimView.setAlpha(1f);
            }
            if (holder.fallbackIconView != null) {
                holder.fallbackIconView.setAlpha(0.28f);
                holder.fallbackIconView.setTranslationX(0f);
                holder.fallbackIconView.setTranslationY(0f);
            }
            if (recycledUnfocusedBannerViews.size() < MAX_RECYCLED_POOL_SIZE) {
                recycledUnfocusedBannerViews.add(view);
            }
        } else if (recycledUnfocusedViews.size() < MAX_RECYCLED_POOL_SIZE) {
            recycledUnfocusedViews.add(view);
        }
    }

    private static boolean isUnfocusedBannerView(@Nullable View view) {
        return view != null && Boolean.TRUE.equals(view.getTag(R.id.unfocused_banner_icon));
    }

    private float getAdjacentBannerFactor(int visualIdx) {
        if (!hasMoreBanners()) {
            return 0f;
        }
        if (hasAllUnfocusedBanners()) {
            return 1f;
        }
        float d = Math.abs(visualIdx - scrollItemPos);
        if (d <= 1.05f) {
            return 1f;
        }
        if (d >= 1.85f) {
            return 0f;
        }
        float t = (d - 1.05f) / 0.80f;
        return 1f - t * t * (3f - 2f * t);
    }

    private float getSlotStepPx() {
        if (isPlainListMode()) {
            return unfocusedHeightPx;
        }
        if (hasCalculatedBannerSize()) {
            return ((calculatedFullBannerHeightPx + unfocusedBannerHeightPx) * 0.5f) + itemGapPx;
        }
        int h = hasAllUnfocusedBanners() ? unfocusedBannerHeightPx : unfocusedHeightPx;
        return h + itemGapPx;
    }

    private static void layoutChildAt(View child, int left, int top, int width, int height) {
        if (child.getMeasuredWidth() != width || child.getMeasuredHeight() != height || child.isLayoutRequested()) {
            int wSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
            int hSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
            child.measure(wSpec, hSpec);
            child.layout(left, 0, left + width, height);
        } else if (child.getLeft() != left || child.getTop() != 0
                || child.getWidth() != width || child.getHeight() != height) {
            child.layout(left, 0, left + width, height);
        }
        child.setTranslationY(top);
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
        if (!selectedBanner || !RecentsBridgeClient.isAvailable() || adapter == null || thumbnailsSuppressed) {
            return;
        }
        int count = getItemCount();
        if (selectedAdapterPos < 0 || selectedAdapterPos >= count) {
            return;
        }
        requestSnapshotForAdapterPos(selectedAdapterPos, !hasCalculatedBannerSize(), includeNeighbors);
        if (includeNeighbors || hasMoreBanners()) {
            if (selectedAdapterPos - 1 >= 0) {
                requestSnapshotForAdapterPos(selectedAdapterPos - 1, false, false);
            }
            if (selectedAdapterPos + 1 < count) {
                requestSnapshotForAdapterPos(selectedAdapterPos + 1, false, false);
            }
        }
        if (hasAllUnfocusedBanners()) {
            int selectedVisualIdx = adapterToVisualPos(selectedAdapterPos);
            List<Integer> visibleAdapters = new ArrayList<>(boundUnfocusedViews.size());
            for (int i = 0; i < boundUnfocusedViews.size(); i++) {
                int v = boundUnfocusedViews.keyAt(i);
                int aPos = visualToAdapterPos(v);
                if (aPos >= 0 && aPos < count && isUnfocusedBannerView(boundUnfocusedViews.valueAt(i))) {
                    visibleAdapters.add(aPos);
                }
            }
            visibleAdapters.sort((a, b) -> Integer.compare(
                    Math.abs(adapterToVisualPos(a) - selectedVisualIdx),
                    Math.abs(adapterToVisualPos(b) - selectedVisualIdx)));
            for (int aPos : visibleAdapters) {
                requestSnapshotForAdapterPos(aPos, false, false);
            }
        }
    }

    private void requestSnapshotForAdapterPos(int adapterPos, boolean hiRes, boolean refreshEvenIfCached) {
        Result<?> item = adapter.getItem(adapterPos);
        if (!(item.getPojo() instanceof AppPojo)) {
            return;
        }
        AppPojo pojo = (AppPojo) item.getPojo();
        final int taskId = pojo.taskId;
        if (taskId <= 0 || (clearDeadThumbnails && pojo.rssKb <= 0L)) {
            return;
        }
        if (!refreshEvenIfCached) {
            Bitmap cached = hiRes ? TaskSnapshotCache.get(taskId) : TaskSnapshotCache.getThumb(taskId);
            if (cached != null) {
                return;
            }
        }
        final String key = (hiRes ? "H:" : "T:") + taskId;
        if (!inFlightSnapshots.add(key)) {
            return;
        }
        final int maxDimPx = hiRes ? 960 : 420;
        final boolean preferLowRes = !hiRes;
        HistorySearcher.getSnapshotExecutor().execute(() -> {
            Bitmap fresh = RecentsBridgeClient.getTaskSnapshot(taskId, maxDimPx, preferLowRes);
            post(() -> {
                inFlightSnapshots.remove(key);
                if (fresh != null) {
                    if (hiRes) {
                        TaskSnapshotCache.put(taskId, fresh);
                    } else {
                        TaskSnapshotCache.putThumb(taskId, fresh);
                    }
                    if (!isAttachedToWindow() || adapter == null) {
                        return;
                    }
                    AppResult selected = getSelectedAppResult();
                    if (selected != null && selected.getPojo().taskId == taskId) {
                        rebindBanner();
                    }
                    if (hasMoreBanners()) {
                        for (int i = 0; i < boundUnfocusedViews.size(); i++) {
                            View uv = boundUnfocusedViews.valueAt(i);
                            if (isUnfocusedBannerView(uv)) {
                                int aPos = visualToAdapterPos(boundUnfocusedViews.keyAt(i));
                                if (aPos >= 0 && aPos < getItemCount()) {
                                    Result<?> r = adapter.getItem(aPos);
                                    if (r instanceof AppResult && ((AppResult) r).getPojo().taskId == taskId) {
                                        AppResult appRes = (AppResult) r;
                                        Bitmap thumb = getResolvedUnfocusedSnapshot(appRes);
                                        appRes.displayUnfocusedBanner(getContext(), uv, thumb, greyscaleSnapshots, thumbnailsSuppressed);
                                        float dy = uv.getTop() + uv.getTranslationY() + uv.getHeight() * 0.5f - getHeight() * 0.52f;
                                        applyCardParallax(uv, false, dy, uv.getTranslationX());
                                    }
                                }
                            }
                        }
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
        int selectedVisualIdx = selectedBanner ? adapterToVisualPos(selectedAdapterPos) : -1;
        if (!hasCalculatedBannerSize() && selectedVisualIdx >= 0 && bannerView != null && bannerView.getVisibility() == View.VISIBLE) {
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

    @Nullable
    private View getSelectedAnchorView() {
        if (selectedBanner && !hasCalculatedBannerSize()) {
            return bannerView;
        }
        return boundUnfocusedViews.get(adapterToVisualPos(selectedAdapterPos));
    }

    private int getItemCount() {
        return adapter != null ? adapter.getCount() : 0;
    }

    private int visualToAdapterPos(int visualIdx) {
        int count = getItemCount();
        if (visualIdx < 0 || visualIdx >= count) {
            return -1;
        }
        return reverseOrder ? (count - 1 - visualIdx) : visualIdx;
    }

    private int adapterToVisualPos(int adapterPos) {
        int count = getItemCount();
        if (adapterPos < 0 || adapterPos >= count) {
            return -1;
        }
        return reverseOrder ? (count - 1 - adapterPos) : adapterPos;
    }

    private float computeSlotCenterY(int visualIdx, float focalY, float stepPx, float extraHalf) {
        if (hasCalculatedBannerSize()) {
            return computeCalculatedSlotCenterY(visualIdx, focalY);
        }
        float distIdx = visualIdx - scrollItemPos;
        float d = Math.abs(distIdx);
        float t = clamp(d, 0f, 1f);
        float eased = t * t * (3f - 2f * t);
        float sign = distIdx >= 0f ? 1f : -1f;
        float push = sign * extraHalf * eased;
        if (hasMoreBanners() && !hasAllUnfocusedBanners()) {
            float adjExtraHalf = Math.max(0f, (unfocusedBannerHeightPx - unfocusedHeightPx) * 0.5f);
            float tAdj = clamp(d * 0.5f, 0f, 1f);
            float easedAdj = tAdj * tAdj * (3f - 2f * tAdj);
            push += sign * (2f * adjExtraHalf) * easedAdj;
        }
        float sectionPush = 0f;
        if (shouldShowSectionSeparators()) {
            for (int i = 0; i < sectionBoundaryVisuals.size(); i++) {
                int b = sectionBoundaryVisuals.get(i);
                float slotSide = visualIdx >= b ? 1f : 0f;
                float bFrac = clamp(scrollItemPos - (b - 1f), 0f, 1f);
                float bEased = bFrac * bFrac * (3f - 2f * bFrac);
                sectionPush += (slotSide - bEased) * separatorGapPx;
            }
        }
        return focalY + distIdx * stepPx + push + sectionPush;
    }

    private float computeCalculatedSlotCenterY(int visualIdx, float focalY) {
        int anchor = (int) Math.floor(scrollItemPos);
        float frac = scrollItemPos - anchor;
        float anchorH = getCalculatedBannerHeight(anchor - scrollItemPos);
        float nextH = getCalculatedBannerHeight(anchor + 1 - scrollItemPos);
        boolean sepAllowed = shouldShowSectionSeparators();
        float anchorSep = (sepAllowed && isSectionBoundaryVisual(anchor + 1)) ? separatorGapPx : 0f;
        float anchorSpan = itemGapPx + anchorSep + 0.5f * (anchorH + nextH);
        float curY = focalY - frac * anchorSpan;
        if (visualIdx == anchor) {
            return curY;
        }
        if (visualIdx > anchor) {
            float prevH = anchorH;
            for (int k = anchor; k < visualIdx; k++) {
                float kNextH = getCalculatedBannerHeight(k + 1 - scrollItemPos);
                float sep = (sepAllowed && isSectionBoundaryVisual(k + 1)) ? separatorGapPx : 0f;
                curY += itemGapPx + sep + 0.5f * (prevH + kNextH);
                prevH = kNextH;
            }
            return curY;
        } else {
            float prevH = anchorH;
            for (int k = anchor; k > visualIdx; k--) {
                float kPrevH = getCalculatedBannerHeight(k - 1 - scrollItemPos);
                float sep = (sepAllowed && isSectionBoundaryVisual(k)) ? separatorGapPx : 0f;
                curY -= itemGapPx + sep + 0.5f * (prevH + kPrevH);
                prevH = kPrevH;
            }
            return curY;
        }
    }

    private void scrubToScrollbarY(float y, boolean playHaptic) {
        int count = getItemCount();
        int height = getHeight();
        float maxScroll = getMaxScrollItems();
        if (count <= 1 || height <= 0 || maxScroll <= 0.001f) {
            return;
        }
        showScrollbarTemporarily(true);
        float trackTop = scrollbarPaddingVPx;
        float trackBottom = Math.max(trackTop + 1f, height - scrollbarPaddingVPx);
        float trackHeight = trackBottom - trackTop;
        float thumbHeight = computeScrollbarThumbHeight(trackHeight, count);
        float usable = Math.max(1f, trackHeight - thumbHeight);
        float frac = clamp((y - trackTop - thumbHeight * 0.5f) / usable, 0f, 1f);
        float oldPos = scrollItemPos;
        float newPos = frac * maxScroll;
        scrollItemPos = newPos;
        rawScrollItemPos = unmapScrollPos(newPos, maxScroll);
        updateWheelSpeed(oldPos, newPos);
        thumbnailsSuppressed = isWheelMovingFast();
        scheduleThumbnailStabilize();
        updateAutoSelectionFromScroll(playHaptic && isSnapScrollEnabled());
        layoutCarousel();
        invalidate();
    }

    private float computeScrollbarThumbHeight(float trackHeight, int count) {
        if (count <= 1) {
            return trackHeight;
        }
        int h = getHeight();
        if (isPlainListMode()) {
            float contentH = getPlainListContentHeight(count);
            if (contentH <= h || h <= 0) {
                return trackHeight;
            }
            float visibleFraction = clamp(h / contentH, 0.08f, 0.85f);
            return clamp(trackHeight * visibleFraction, scrollbarMinThumbPx, trackHeight * 0.85f);
        }
        float viewH = Math.max(1f, h - 2f * scrollbarPaddingVPx);
        float baseH = hasCalculatedBannerSize() ? compactBannerHeightPx
                : (hasAllUnfocusedBanners() ? unfocusedBannerHeightPx : unfocusedHeightPx);
        float slotStep = Math.max(1f, baseH + itemGapPx);
        float focusedH = hasCalculatedBannerSize()
                ? calculatedFullBannerHeightPx
                : (selectedBanner ? bannerHeightPx : baseH);
        float extraSelected = Math.max(0f, focusedH - baseH);
        float adjExtra = hasCalculatedBannerSize()
                ? Math.min(2, Math.max(0, count - 1)) * Math.max(0f, unfocusedBannerHeightPx - compactBannerHeightPx)
                : ((hasMoreBanners() && !hasAllUnfocusedBanners())
                    ? Math.min(2, Math.max(0, count - 1)) * Math.max(0f, unfocusedBannerHeightPx - unfocusedHeightPx)
                    : 0f);
        float sepTotal = shouldShowSectionSeparators() ? sectionBoundaryVisuals.size() * separatorGapPx : 0f;
        float totalContentPx = focusedH + Math.max(0, count - 1) * slotStep + adjExtra + sepTotal;
        float visibleFraction = totalContentPx > 0f
                ? clamp((viewH - extraSelected * 0.25f) / totalContentPx, 0.08f, 0.85f)
                : clamp(5.5f / count, 0.12f, 0.72f);
        return clamp(trackHeight * visibleFraction, scrollbarMinThumbPx, trackHeight * 0.82f);
    }

    private void showScrollbarTemporarily(boolean keepAwake) {
        if (!showScrollbar) {
            return;
        }
        removeCallbacks(hideScrollbarRunnable);
        if (!scrollbarVisibilityTargetShown) {
            scrollbarVisibilityTargetShown = true;
            cancelAnimator(scrollbarVisibilityAnimator);
            if (scrollbarVisibility < 0.99f) {
                scrollbarVisibilityAnimator = ValueAnimator.ofFloat(scrollbarVisibility, 1f);
                scrollbarVisibilityAnimator.setDuration(110L);
                scrollbarVisibilityAnimator.setInterpolator(FAST_OUT_SLOW_IN);
                scrollbarVisibilityAnimator.addUpdateListener(anim -> {
                    scrollbarVisibility = (float) anim.getAnimatedValue();
                    invalidate();
                });
                scrollbarVisibilityAnimator.start();
            } else {
                scrollbarVisibility = 1f;
                invalidate();
            }
        }
        if (!keepAwake) {
            postDelayed(hideScrollbarRunnable, SCROLLBAR_HIDE_DELAY_MS);
        }
    }

    private void fadeOutScrollbar() {
        removeCallbacks(hideScrollbarRunnable);
        if (!showScrollbar || scrollbarEngaged
                || dragMode == DragMode.SCROLLBAR_DRAG
                || dragMode == DragMode.SCROLL_VERTICAL) {
            return;
        }
        scrollbarVisibilityTargetShown = false;
        cancelAnimator(scrollbarVisibilityAnimator);
        scrollbarVisibilityAnimator = ValueAnimator.ofFloat(scrollbarVisibility, 0f);
        scrollbarVisibilityAnimator.setDuration(260L);
        scrollbarVisibilityAnimator.setInterpolator(FAST_OUT_SLOW_IN);
        scrollbarVisibilityAnimator.addUpdateListener(anim -> {
            scrollbarVisibility = (float) anim.getAnimatedValue();
            invalidate();
        });
        scrollbarVisibilityAnimator.start();
    }

    private void setScrollbarEngaged(boolean engaged) {
        if (!showScrollbar) {
            return;
        }
        if (engaged) {
            showScrollbarTemporarily(true);
        } else {
            showScrollbarTemporarily(false);
        }
        if (scrollbarEngaged == engaged) {
            return;
        }
        scrollbarEngaged = engaged;
        cancelAnimator(scrollbarAnimator);
        scrollbarAnimator = ValueAnimator.ofFloat(scrollbarEngagement, engaged ? 1f : 0f);
        scrollbarAnimator.setDuration(engaged ? 110 : 220);
        scrollbarAnimator.setInterpolator(FAST_OUT_SLOW_IN);
        scrollbarAnimator.addUpdateListener(anim -> {
            scrollbarEngagement = (float) anim.getAnimatedValue();
            invalidate();
        });
        scrollbarAnimator.start();
    }

    private String getGroupLabel(int group) {
        if (group == 0) {
            return labelRam;
        }
        if (group == 1) {
            return labelHistory;
        }
        return labelDead;
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        int count = getItemCount();
        int width = getWidth();
        int height = getHeight();
        if (shouldShowSectionSeparators() && !openSesameActive && width > 0 && height > 0
                && visualGroups.length == count) {
            boolean plainList = isPlainListMode();
            float focalY = height * 0.52f;
            float stepPx = getSlotStepPx();
            int selectedVisualIdx = selectedBanner ? adapterToVisualPos(selectedAdapterPos) : -1;
            boolean bannerGrabbed = (carouselDepthEffects && selectedVisualIdx >= 0 && grabbedVisualIndex == selectedVisualIdx);
            float bannerGrabScale = bannerGrabbed ? grabScale : 1f;
            float grabInset = selectedBanner ? (bannerHeightPx * 0.5f * (1f - bannerGrabScale)) : 0f;
            int baseUnfocusedH = hasAllUnfocusedBanners() ? unfocusedBannerHeightPx : unfocusedHeightPx;
            float extraHalf = selectedBanner ? Math.max(0f, (bannerHeightPx - baseUnfocusedH) * 0.5f - grabInset) : 0f;
            float marginH = (carouselArc || selectedBanner) ? horizontalMarginPx : 0f;
            float lineLeft = marginH + DrawableUtils.dpToPx(getContext(), 6f);
            float lineRight = width - marginH - DrawableUtils.dpToPx(getContext(), 6f);
            float pillPadX = DrawableUtils.dpToPx(getContext(), 9f);
            float pillPadY = DrawableUtils.dpToPx(getContext(), 3.5f);
            float pillRadius = DrawableUtils.dpToPx(getContext(), 10f);
            Paint.FontMetrics fm = separatorTextPaint.getFontMetrics();
            float textHalfHeight = (fm.descent - fm.ascent) * 0.5f;
            float textBaselineOffset = -(fm.ascent + fm.descent) * 0.5f;

            for (int i = 0; i < sectionBoundaryVisuals.size(); i++) {
                int b = sectionBoundaryVisuals.get(i);
                if (b <= 0 || b >= count) {
                    continue;
                }
                int prevV = b - 1;
                float prevCenterY = plainList
                        ? computePlainListCenterY(prevV, count, height)
                        : computeSlotCenterY(prevV, focalY, stepPx, extraHalf);
                float currCenterY = plainList
                        ? computePlainListCenterY(b, count, height)
                        : computeSlotCenterY(b, focalY, stepPx, extraHalf);
                int prevH = hasCalculatedBannerSize()
                        ? Math.round(getCalculatedBannerHeight(prevV - scrollItemPos))
                        : ((selectedBanner && prevV == selectedVisualIdx) ? bannerHeightPx
                            : Math.round(unfocusedHeightPx + (unfocusedBannerHeightPx - unfocusedHeightPx) * getAdjacentBannerFactor(prevV)));
                int currH = hasCalculatedBannerSize()
                        ? Math.round(getCalculatedBannerHeight(b - scrollItemPos))
                        : ((selectedBanner && b == selectedVisualIdx) ? bannerHeightPx
                            : Math.round(unfocusedHeightPx + (unfocusedBannerHeightPx - unfocusedHeightPx) * getAdjacentBannerFactor(b)));
                float prevBottom = prevCenterY + prevH * 0.5f;
                float currTop = currCenterY - currH * 0.5f;
                float sepY = (prevBottom + currTop) * 0.5f;
                if (sepY < -separatorGapPx || sepY > height + separatorGapPx) {
                    continue;
                }
                float distFactor = carouselDepthEffects
                        ? (1f - Math.min(0.6f, Math.abs(sepY - focalY) / Math.max(1f, height * 0.55f)))
                        : 1f;
                if (launchExitActive) {
                    distFactor *= (1f - clamp(launchExitProgress * 1.4f, 0f, 1f));
                } else if (launchEntranceAnim && (pendingLaunchEntrance || launchEntranceProgress < 1f)) {
                    distFactor *= pendingLaunchEntrance ? 0f : clamp(launchEntranceProgress * 1.4f, 0f, 1f);
                }
                if (animatingClearAll) {
                    distFactor *= (1f - clamp(clearAllProgress * 1.4f, 0f, 1f));
                }
                String text = "\u25B2 " + getGroupLabel(visualGroups[prevV]) + "  \u00B7  " + getGroupLabel(visualGroups[b]) + " \u25BC";
                float textWidth = separatorTextPaint.measureText(text);
                float pillHalfW = textWidth * 0.5f + pillPadX;
                float centerX = width * 0.5f;

                separatorLinePaint.setAlpha(Math.round(Color.alpha(0x3DFFFFFF) * distFactor));
                if (centerX - pillHalfW > lineLeft + 4f) {
                    canvas.drawLine(lineLeft, sepY, centerX - pillHalfW - 4f, sepY, separatorLinePaint);
                    canvas.drawLine(centerX + pillHalfW + 4f, sepY, lineRight, sepY, separatorLinePaint);
                }

                separatorPillRect.set(
                        centerX - pillHalfW,
                        sepY - textHalfHeight - pillPadY,
                        centerX + pillHalfW,
                        sepY + textHalfHeight + pillPadY);
                separatorPillPaint.setAlpha(Math.round(Color.alpha(0x9A181B22) * distFactor));
                canvas.drawRoundRect(separatorPillRect, pillRadius, pillRadius, separatorPillPaint);

                separatorTextPaint.setAlpha(Math.round(Color.alpha(0xCCFFFFFF) * distFactor));
                canvas.drawText(text, centerX - textWidth * 0.5f, sepY + textBaselineOffset, separatorTextPaint);
            }
        }

        super.dispatchDraw(canvas);
        float maxScroll = getMaxScrollItems();
        if (!showScrollbar || count <= 1 || height <= 0 || openSesameActive || maxScroll <= 0.001f
                || (scrollbarVisibility <= 0.005f && !scrollbarEngaged)) {
            return;
        }
        float visAlpha = clamp(scrollbarVisibility, 0f, 1f);
        if (launchExitActive) {
            visAlpha *= (1f - clamp(launchExitProgress * 1.6f, 0f, 1f));
        } else if (animatingClearAll) {
            visAlpha *= (1f - clamp(clearAllProgress * 1.6f, 0f, 1f));
        }
        float trackTop = scrollbarPaddingVPx;
        float trackBottom = Math.max(trackTop + 1f, height - scrollbarPaddingVPx);
        float trackHeight = trackBottom - trackTop;
        float baseThumbH = computeScrollbarThumbHeight(trackHeight, count);
        float norm = clamp(scrollItemPos / maxScroll, 0f, 1f);
        float overscroll = 0f;
        if (scrollItemPos < 0f) {
            overscroll = Math.min(0.45f, -scrollItemPos * 0.25f);
        } else if (scrollItemPos > maxScroll) {
            overscroll = Math.min(0.45f, (scrollItemPos - maxScroll) * 0.25f);
        }
        float thumbH = Math.max(scrollbarMinThumbPx * 0.6f, baseThumbH * (1f - overscroll));
        float thumbTop = trackTop + norm * (trackHeight - thumbH);
        float widthBar = scrollbarIdleWidthPx + (scrollbarActiveWidthPx - scrollbarIdleWidthPx) * scrollbarEngagement;
        float left = scrollbarOffsetXPx;
        float right = left + widthBar;
        float radius = widthBar * 0.5f;

        scrollbarTrackPaint.setAlpha(Math.round(Color.alpha(0x28FFFFFF) * (0.55f + 0.45f * scrollbarEngagement) * visAlpha));
        scrollbarTrackRect.set(left, trackTop, right, trackBottom);
        canvas.drawRoundRect(scrollbarTrackRect, radius, radius, scrollbarTrackPaint);

        scrollbarThumbPaint.setAlpha(Math.round(255f * (0.45f + 0.50f * scrollbarEngagement) * visAlpha));
        scrollbarThumbRect.set(left, thumbTop, right, thumbTop + thumbH);
        canvas.drawRoundRect(scrollbarThumbRect, radius, radius, scrollbarThumbPaint);
    }

    private void startOpenSesameLaunch(final Result<?> result, final int tappedAdapterPos, @Nullable View tappedView) {
        if (openSesameActive) {
            return;
        }
        removeCallbacks(flingTick);
        removeCallbacks(stabilizeThumbnailsRunnable);
        removeCallbacks(longPressRunnable);
        if (!scrollScroller.isFinished()) {
            scrollScroller.abortAnimation();
        }
        cancelAnimator(scrollAnimator);
        cancelAnimator(swayAnimator);
        cancelAnimator(horizontalAnimator);
        cancelAnimator(grabAnimator);
        cancelAnimator(openSesameAnimator);
        cancelAnimator(launchEntranceAnimator);
        pendingLaunchEntrance = false;
        launchEntranceProgress = 1f;

        View sourceRectView = tappedView != null ? tappedView : bannerView;
        if (sourceRectView != null) {
            sesameStartLeft = sourceRectView.getLeft() + sourceRectView.getTranslationX();
            sesameStartTop = sourceRectView.getTop() + sourceRectView.getTranslationY();
            sesameStartWidth = Math.max(1f, sourceRectView.getWidth() * sourceRectView.getScaleX());
            sesameStartHeight = Math.max(1f, sourceRectView.getHeight() * sourceRectView.getScaleY());
        } else {
            sesameStartLeft = bannerHorizontalMarginPx;
            sesameStartTop = (getHeight() - bannerHeightPx) * 0.5f;
            sesameStartWidth = Math.max(1f, getWidth() - 2 * bannerHorizontalMarginPx);
            sesameStartHeight = bannerHeightPx;
        }

        sesameTargetHeight = getHeight();
        ViewParent p = getParent();
        if (p instanceof View && ((View) p).getParent() instanceof View) {
            View root = (View) ((View) p).getParent();
            int rootH = root.getHeight();
            int topInRoot = ((View) p).getTop() + getTop();
            if (rootH > topInRoot) {
                sesameTargetHeight = rootH - topInRoot;
            }
        }

        if (tappedAdapterPos != selectedAdapterPos) {
            selectedAdapterPos = tappedAdapterPos;
            selectedPojoId = result.getPojoId();
            bannerBoundAdapterPos = -1;
        }
        thumbnailsSuppressed = false;
        View bv = obtainAndBindBanner(tappedAdapterPos);
        bv.setVisibility(View.VISIBLE);
        bv.bringToFront();
        contextMenuLayer.setVisibility(View.GONE);

        openSesameActive = true;
        openSesameProgress = 0f;
        if (callback != null) {
            callback.onOpenSesameProgress(0f);
        }

        openSesameAnimator = ValueAnimator.ofFloat(0f, 1f);
        openSesameAnimator.setDuration(190L);
        openSesameAnimator.setInterpolator(FAST_OUT_SLOW_IN);
        openSesameAnimator.addUpdateListener(anim -> {
            openSesameProgress = (float) anim.getAnimatedValue();
            if (callback != null) {
                callback.onOpenSesameProgress(openSesameProgress);
            }
            layoutCarousel();
        });
        openSesameAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                openSesameProgress = 1f;
                applyOpenSesameLayout(getWidth());
                if (bannerView != null) {
                    bannerView.setTag(R.id.banner_scrim, Boolean.TRUE);
                }
                if (callback != null) {
                    callback.onOpenSesameProgress(1f);
                    callback.onLaunchSelected(result, bannerView);
                }
            }
        });
        openSesameAnimator.start();
    }

    private void applyOpenSesameLayout(int width) {
        if (bannerView == null || width <= 0) {
            return;
        }
        float p = clamp(openSesameProgress, 0f, 1f);
        float curLeft = sesameStartLeft + (0f - sesameStartLeft) * p;
        float curTop = sesameStartTop + (0f - sesameStartTop) * p;
        float curW = sesameStartWidth + (width - sesameStartWidth) * p;
        float targetH = sesameTargetHeight > 0f ? sesameTargetHeight : getHeight();
        float curH = sesameStartHeight + (targetH - sesameStartHeight) * p;

        layoutChildAt(bannerView, Math.round(curLeft), Math.round(curTop), Math.max(1, Math.round(curW)), Math.max(1, Math.round(curH)));
        bannerView.setPivotX(0f);
        bannerView.setPivotY(0f);
        bannerView.setScaleX(1f);
        bannerView.setScaleY(1f);
        bannerView.setTranslationX(0f);
        bannerView.setRotation(0f);
        bannerView.setAlpha(1f);

        float baseCorner = hasCalculatedBannerSize() ? unfocusedBannerCornerRadiusPx : bannerCornerRadiusPx;
        float curRadius = baseCorner * (1f - p);
        bannerView.setTag(R.id.banner_app_name, Float.valueOf(curRadius));
        bannerView.invalidateOutline();

        float uiFade = 1f - clamp(p * 2.1f, 0f, 1f);
        AppResult.BannerViewHolder holder = AppResult.obtainBannerHolder(bannerView, true);
        if (holder.outlineView != null) {
            holder.outlineView.setAlpha(uiFade);
        }
        if (holder.scrimView != null) {
            holder.scrimView.setAlpha(uiFade);
        }
        if (holder.headerIconView != null && holder.headerIconView.getVisibility() == View.VISIBLE) {
            holder.headerIconView.setAlpha(uiFade);
        }
        holder.appNameView.setAlpha(uiFade);
        holder.metaView.setAlpha(uiFade);

        if (holder.snapshotView != null && greyscaleSnapshots && holder.snapshotView.getVisibility() == View.VISIBLE) {
            float sat = clamp((p - 0.1f) * 1.4f, 0f, 1f);
            sesameSatMatrix.setSaturation(sat);
            holder.snapshotView.setColorFilter(new ColorMatrixColorFilter(sesameSatMatrix));
        }
        applyBannerThumbnailParallax(lastBannerParallaxDy * (1f - p), lastBannerParallaxTx * (1f - p));
    }

    private float applyWheelDetent(float rawPos, float maxScroll) {
        if (rawPos < 0f || rawPos > maxScroll) {
            return tiltSwayOvershoot ? mapOverscroll(rawPos, maxScroll) : clamp(rawPos, 0f, maxScroll);
        }
        if (!isSnapScrollEnabled()) {
            return rawPos;
        }
        float base = (float) Math.floor(rawPos);
        float frac = rawPos - base;
        return base + frac - WHEEL_DETENT_AMP * (float) Math.sin(2.0 * Math.PI * frac);
    }

    private float unmapScrollPos(float pos, float maxScroll) {
        if (pos < 0f || pos > maxScroll) {
            return tiltSwayOvershoot ? unmapOverscroll(pos, maxScroll) : clamp(pos, 0f, maxScroll);
        }
        if (!isSnapScrollEnabled()) {
            return pos;
        }
        float base = (float) Math.floor(pos);
        float target = pos - base;
        float x = target;
        double twoPi = 2.0 * Math.PI;
        for (int i = 0; i < 4; i++) {
            double fx = x - WHEEL_DETENT_AMP * Math.sin(twoPi * x) - target;
            double dfx = 1.0 - twoPi * WHEEL_DETENT_AMP * Math.cos(twoPi * x);
            x = clamp((float) (x - fx / Math.max(0.1, dfx)), 0f, 1f);
        }
        return base + x;
    }

    private static float mapOverscroll(float rawPos, float maxScroll) {
        if (rawPos < 0f) {
            return -rubberBand(-rawPos, EDGE_OVERSCROLL_RANGE_ITEMS);
        }
        if (rawPos > maxScroll) {
            return maxScroll + rubberBand(rawPos - maxScroll, EDGE_OVERSCROLL_RANGE_ITEMS);
        }
        return rawPos;
    }

    private static float mapMomentumOvershoot(float edge, float rawOvershoot) {
        if (rawOvershoot < 0f) {
            return edge - rubberBand(-rawOvershoot, MOMENTUM_OVERSHOOT_MAX_ITEMS);
        }
        return edge + rubberBand(rawOvershoot, MOMENTUM_OVERSHOOT_MAX_ITEMS);
    }

    private static float unmapOverscroll(float mappedPos, float maxScroll) {
        if (mappedPos < 0f) {
            float d = Math.min(EDGE_OVERSCROLL_RANGE_ITEMS * 0.92f, -mappedPos);
            return -(d * EDGE_OVERSCROLL_RANGE_ITEMS) / Math.max(0.05f, EDGE_OVERSCROLL_RANGE_ITEMS - d);
        }
        if (mappedPos > maxScroll) {
            float d = Math.min(EDGE_OVERSCROLL_RANGE_ITEMS * 0.92f, mappedPos - maxScroll);
            return maxScroll + (d * EDGE_OVERSCROLL_RANGE_ITEMS) / Math.max(0.05f, EDGE_OVERSCROLL_RANGE_ITEMS - d);
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
