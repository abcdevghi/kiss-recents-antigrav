package fr.neamar.kiss.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.AbsListView;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.result.Result;

/**
 * Swipe a row sideways to remove it.
 * <p>
 * The row follows the finger and fades. Past a third of the width, or on a fling, it slides out;
 * otherwise it springs back. Once the slide-out is over the callback removes the item, and the
 * list's own change animation ({@link AnimatedListView}) closes the gap.
 */
public class SwipeToDismiss implements View.OnTouchListener {

    public interface Callback {
        /**
         * @return true if this row may be swiped away
         */
        boolean canDismiss(Result<?> result);

        /**
         * @return false to leave this row alone when everything is removed at once
         */
        boolean includeInClearAll(Result<?> result);

        /**
         * The slide-out is over: remove these items from the adapter. Called once for a group of swipes
         * that overlapped in time.
         */
        void onDismissed(List<Result<?>> results);
    }

    /** Share of the width the row has to be dragged over to be dismissed */
    private static final float DISMISS_THRESHOLD = 0.35f;
    /** How far the row has to be dragged for it to be fully transparent, relative to the width */
    private static final float FADE_DISTANCE = 0.8f;
    private static final int STAGGER_DELAY_MS = 35;
    private static final int RESET_FALLBACK_DELAY_MS = 500;

    private final ListView listView;
    private final RecordAdapter adapter;
    private final Callback callback;

    private final int slop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private final int slideOutDuration;
    private final int snapBackDuration;
    private final Interpolator slideOutInterpolator = new AccelerateInterpolator(1.4f);
    private final Interpolator snapBackInterpolator = new OvershootInterpolator(1.3f);

    private int viewWidth = 1;
    private float downX;
    private float downY;
    private boolean swiping;
    private boolean pastThreshold;
    private boolean paused;
    private int swipingSlop;
    private VelocityTracker velocityTracker;
    private View downView;
    private int downPosition = ListView.INVALID_POSITION;

    private int slideOutsRunning = 0;
    private boolean dismissingAll = false;
    private final List<View> pendingViews = new ArrayList<>();
    private final List<Result<?>> pendingResults = new ArrayList<>();

    public SwipeToDismiss(ListView listView, RecordAdapter adapter, Callback callback) {
        ViewConfiguration configuration = ViewConfiguration.get(listView.getContext());
        this.slop = configuration.getScaledTouchSlop();
        this.minFlingVelocity = configuration.getScaledMinimumFlingVelocity() * 16;
        this.maxFlingVelocity = configuration.getScaledMaximumFlingVelocity();
        this.slideOutDuration = listView.getResources().getInteger(android.R.integer.config_shortAnimTime);
        this.snapBackDuration = listView.getResources().getInteger(android.R.integer.config_mediumAnimTime);
        this.listView = listView;
        this.adapter = adapter;
        this.callback = callback;
    }

    /**
     * Do not start a swipe while the list is being scrolled by touch.
     * Set with {@link ListView#setOnScrollListener(AbsListView.OnScrollListener)}.
     */
    public AbsListView.OnScrollListener makeScrollListener() {
        return new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                paused = scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL;
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
            }
        };
    }

    /**
     * True while rows are sliding out. The list must not be rebuilt meanwhile, as a row could be
     * reused for another item in mid-animation.
     */
    public boolean isAnimating() {
        return slideOutsRunning > 0 || dismissingAll;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (viewWidth < 2) {
            viewWidth = listView.getWidth();
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (paused || dismissingAll) {
                    return false;
                }
                int position = listView.pointToPosition((int) event.getX(), (int) event.getY());
                if (position == ListView.INVALID_POSITION || position >= adapter.getCount()) {
                    return false;
                }
                View child = listView.getChildAt(position - listView.getFirstVisiblePosition());
                if (child != null && !pendingViews.contains(child) && callback.canDismiss(adapter.getItem(position))) {
                    downView = child;
                    downPosition = position;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    velocityTracker = VelocityTracker.obtain();
                    velocityTracker.addMovement(event);
                }
                // Never consume: taps and long presses still reach the list
                return false;
            }

            case MotionEvent.ACTION_CANCEL: {
                if (velocityTracker == null) {
                    break;
                }
                if (downView != null && swiping) {
                    snapBack(downView);
                }
                reset();
                break;
            }

            case MotionEvent.ACTION_UP: {
                if (velocityTracker == null) {
                    break;
                }
                float deltaX = event.getRawX() - downX;
                velocityTracker.addMovement(event);
                velocityTracker.computeCurrentVelocity(1000);
                float velocityX = velocityTracker.getXVelocity();
                float absVelocityX = Math.abs(velocityX);
                float absVelocityY = Math.abs(velocityTracker.getYVelocity());

                boolean dismiss = false;
                boolean dismissRight = false;
                if (swiping && Math.abs(deltaX) > viewWidth * DISMISS_THRESHOLD) {
                    dismiss = true;
                    dismissRight = deltaX > 0;
                } else if (swiping && minFlingVelocity <= absVelocityX && absVelocityX <= maxFlingVelocity
                        && absVelocityY < absVelocityX) {
                    // A fling in the direction of the drag
                    dismiss = (velocityX < 0) == (deltaX < 0);
                    dismissRight = velocityX > 0;
                }

                if (dismiss && downView != null && downPosition != ListView.INVALID_POSITION) {
                    slideOut(downView, downPosition, dismissRight);
                } else if (swiping && downView != null) {
                    snapBack(downView);
                }
                reset();
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                if (velocityTracker == null || paused) {
                    break;
                }
                velocityTracker.addMovement(event);
                float deltaX = event.getRawX() - downX;
                float deltaY = event.getRawY() - downY;
                if (!swiping && Math.abs(deltaX) > slop && Math.abs(deltaY) < Math.abs(deltaX) / 2) {
                    swiping = true;
                    swipingSlop = deltaX > 0 ? slop : -slop;
                    listView.requestDisallowInterceptTouchEvent(true);

                    // Stop the list from handling this gesture as a scroll, tap or long press
                    MotionEvent cancelEvent = MotionEvent.obtain(event);
                    cancelEvent.setAction(MotionEvent.ACTION_CANCEL
                            | (event.getActionIndex() << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
                    listView.onTouchEvent(cancelEvent);
                    cancelEvent.recycle();
                }

                if (swiping && downView != null) {
                    downView.setTranslationX(deltaX - swipingSlop);
                    downView.setAlpha(Math.max(0f, Math.min(1f, 1f - Math.abs(deltaX) / (viewWidth * FADE_DISTANCE))));

                    // A short tick when the release would remove the row, and again when it no longer would
                    boolean over = Math.abs(deltaX) > viewWidth * DISMISS_THRESHOLD;
                    if (over != pastThreshold) {
                        pastThreshold = over;
                        listView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    return true;
                }
                break;
            }

            default:
                break;
        }
        return false;
    }

    /**
     * Remove every item the callback agrees to: the rows on screen slide out one after the other,
     * starting from the bottom. The others stay where they are.
     */
    public void dismissAll() {
        if (dismissingAll || adapter.getCount() == 0) {
            return;
        }
        final int count = adapter.getCount();
        final List<Result<?>> everything = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Result<?> result = adapter.getItem(i);
            if (callback.includeInClearAll(result)) {
                everything.add(result);
            }
        }
        if (everything.isEmpty()) {
            return;
        }

        // The rows on screen that go
        final Set<Result<?>> everythingSet = new HashSet<>(everything);
        final List<View> views = new ArrayList<>();
        int firstPosition = listView.getFirstVisiblePosition();
        for (int i = 0; i < listView.getChildCount(); i++) {
            int position = firstPosition + i;
            if (position < count && everythingSet.contains(adapter.getItem(position))) {
                views.add(listView.getChildAt(i));
            }
        }
        if (views.isEmpty()) {
            callback.onDismissed(everything);
            return;
        }

        dismissingAll = true;
        final int[] remaining = {views.size()};
        for (int i = 0; i < views.size(); i++) {
            final View child = views.get(i);
            // The row nearest to the bar goes first
            int order = views.size() - 1 - i;
            child.animate()
                    .translationX(viewWidthOrList())
                    .alpha(0f)
                    .setStartDelay((long) order * STAGGER_DELAY_MS)
                    .setDuration(slideOutDuration)
                    .setInterpolator(slideOutInterpolator)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            child.animate().setListener(null).setStartDelay(0);
                            remaining[0]--;
                            if (remaining[0] == 0) {
                                dismissingAll = false;
                                callback.onDismissed(everything);
                                resetViewsLater(views);
                            }
                        }
                    });
        }
    }

    private int viewWidthOrList() {
        return Math.max(viewWidth, listView.getWidth());
    }

    private void slideOut(final View view, int position, boolean toRight) {
        if (position < 0 || position >= adapter.getCount()) {
            return;
        }
        Result<?> result = adapter.getItem(position);
        slideOutsRunning++;
        pendingViews.add(view);
        pendingResults.add(result);

        // The less distance is left, the shorter the animation
        float travelled = Math.min(1f, Math.abs(view.getTranslationX()) / viewWidthOrList());
        long duration = Math.max(90, (long) (slideOutDuration * (1f - travelled * 0.6f)));

        view.animate()
                .translationX(toRight ? viewWidthOrList() : -viewWidthOrList())
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(slideOutInterpolator)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.animate().setListener(null);
                        slideOutFinished();
                    }
                });
    }

    private void slideOutFinished() {
        slideOutsRunning--;
        if (slideOutsRunning > 0) {
            // Other swipes are still sliding out: remove everything together at the end
            return;
        }
        List<View> views = new ArrayList<>(pendingViews);
        List<Result<?>> results = new ArrayList<>(pendingResults);
        pendingViews.clear();
        pendingResults.clear();

        callback.onDismissed(results);
        resetViewsLater(views);
    }

    private void snapBack(View view) {
        view.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(snapBackDuration)
                .setInterpolator(snapBackInterpolator)
                .setListener(null);
    }

    private void reset() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
        }
        velocityTracker = null;
        downX = 0;
        downY = 0;
        downView = null;
        downPosition = ListView.INVALID_POSITION;
        swiping = false;
        pastThreshold = false;
    }

    /**
     * Rows are recycled: one that slid out must not stay transparent and shifted when it is reused.
     * Done once the list has been laid out for the removal but before it is drawn, so the row is never
     * visible in its old place. A delayed second pass covers the case where nothing is drawn (empty list).
     */
    private void resetViewsLater(final List<View> views) {
        final Runnable resetNow = new Runnable() {
            @Override
            public void run() {
                for (View view : views) {
                    view.animate().cancel();
                    view.setAlpha(1f);
                    view.setTranslationX(0f);
                }
            }
        };

        listView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewTreeObserver observer = listView.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(this);
                }
                resetNow.run();
                return true;
            }
        });
        listView.postDelayed(resetNow, RESET_FALLBACK_DELAY_MS);
    }
}
