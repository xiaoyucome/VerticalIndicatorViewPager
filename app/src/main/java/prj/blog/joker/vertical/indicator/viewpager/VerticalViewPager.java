package prj.blog.joker.vertical.indicator.viewpager;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.support.v4.os.ParcelableCompat;
import android.support.v4.os.ParcelableCompatCreatorCallbacks;
import android.support.v4.view.AccessibilityDelegateCompat;
import android.support.v4.view.KeyEventCompat;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.VelocityTrackerCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.ViewConfigurationCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.view.accessibility.AccessibilityEventCompat;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.support.v4.view.accessibility.AccessibilityRecordCompat;
import android.support.v4.widget.EdgeEffectCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.Interpolator;
import android.widget.Scroller;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class VerticalViewPager extends ViewGroup {

    private static final String TAG = "ViewPager";
    private static final boolean DEBUG = false;

    private static final boolean USE_CACHE = false;

    private static final int DEFAULT_OFFSCREEN_PAGES = 1;
    private static final int MAX_SETTLE_DURATION = 600; // ms
    private static final int MIN_DISTANCE_FOR_FLING = 25; // dips

    private static final int DEFAULT_GUTTER_SIZE = 16; // dips

    private static final int MIN_FLING_VELOCITY = 400; // dips

    private static final int[] LAYOUT_ATTRS = new int[] { android.R.attr.layout_gravity };

    /**
     * Used to track what the expected number of items in the adapter should be.
     * If the app changes this when we don't expect it, we'll throw a big
     * obnoxious exception.
     */
    private int mExpectedAdapterCount;

    static class ItemInfo {
        Object object;
        int position;
        boolean scrolling;
        float heightFactor;
        float offset;
    }

    private static final Comparator<ItemInfo> COMPARATOR = new Comparator<ItemInfo>() {
        @Override
        public int compare(ItemInfo lhs, ItemInfo rhs) {
            return lhs.position - rhs.position;
        }
    };

    private static final Interpolator sInterpolator = new Interpolator() {
        @Override
        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * t * t * t + 1.0f;
        }
    };

    private final ArrayList<ItemInfo> mItems = new ArrayList<ItemInfo>();
    private final ItemInfo mTempItem = new ItemInfo();

    private final Rect mTempRect = new Rect();

    private PagerAdapter mAdapter;
    private int mCurItem; // Index of currently displayed page.
    private int mRestoredCurItem = -1;
    private Parcelable mRestoredAdapterState = null;
    private ClassLoader mRestoredClassLoader = null;
    private Scroller mScroller;
    private PagerObserver mObserver;

    private int mPageMargin;
    private Drawable mMarginDrawable;
    private int mLeftPageBounds;
    private int mRightPageBounds;

    // Offsets of the first and last items, if known.
    // Set during population, used to determine if we are at the beginning
    // or end of the pager data set during touch scrolling.
    private float mFirstOffset = -Float.MAX_VALUE;
    private float mLastOffset = Float.MAX_VALUE;

    private int mChildWidthMeasureSpec;
    private int mChildHeightMeasureSpec;
    private boolean mInLayout;

    private boolean mScrollingCacheEnabled;

    private boolean mPopulatePending;
    private int mOffscreenPageLimit = DEFAULT_OFFSCREEN_PAGES;

    private boolean mIsBeingDragged;
    private boolean mIsUnableToDrag;
    private boolean mIgnoreGutter;
    private int mDefaultGutterSize;
    private int mGutterSize;
    private int mTouchSlop;
    /**
     * Position of the last motion event.
     */
    private float mLastMotionX;
    private float mLastMotionY;
    private float mInitialMotionX;
    private float mInitialMotionY;
    /**
     * ID of the active pointer. This is used to retain consistency during
     * drags/flings if multiple pointers are used.
     */
    private int mActivePointerId = INVALID_POINTER;
    /**
     * Sentinel value for no current active pointer. Used by
     * {@link #mActivePointerId}.
     */
    private static final int INVALID_POINTER = -1;

    /**
     * Determines speed during touch scrolling
     */
    private VelocityTracker mVelocityTracker;
    private int mMinimumVelocity;
    private int mMaximumVelocity;
    private int mFlingDistance;
    private int mCloseEnough;

    // If the pager is at least this close to its final position, complete the
    // scroll
    // on touch down and let the user interact with the content inside instead
    // of
    // "catching" the flinging pager.
    private static final int CLOSE_ENOUGH = 2; // dp

    private boolean mFakeDragging;
    private long mFakeDragBeginTime;

    private EdgeEffectCompat mTopEdge;
    private EdgeEffectCompat mBottomEdge;

    private boolean mFirstLayout = true;
    private boolean mNeedCalculatePageOffsets = false;
    private boolean mCalledSuper;
    private int mDecorChildCount;

    private ViewPager.OnPageChangeListener mOnPageChangeListener;
    private ViewPager.OnPageChangeListener mInternalPageChangeListener;
    private OnAdapterChangeListener mAdapterChangeListener;
    private ViewPager.PageTransformer mPageTransformer;
    private Method mSetChildrenDrawingOrderEnabled;

    private static final int DRAW_ORDER_DEFAULT = 0;
    private static final int DRAW_ORDER_FORWARD = 1;
    private static final int DRAW_ORDER_REVERSE = 2;
    private int mDrawingOrder;
    private ArrayList<View> mDrawingOrderedChildren;
    private static final ViewPositionComparator sPositionComparator = new ViewPositionComparator();

    /**
     * Indicates that the pager is in an idle, settled state. The current page
     * is fully in view and no animation is in progress.
     */
    public static final int SCROLL_STATE_IDLE = 0;

    /**
     * Indicates that the pager is currently being dragged by the user.
     */
    public static final int SCROLL_STATE_DRAGGING = 1;

    /**
     * Indicates that the pager is in the process of settling to a final
     * position.
     */
    public static final int SCROLL_STATE_SETTLING = 2;

    private final Runnable mEndScrollRunnable = new Runnable() {
        @Override
        public void run() {
            VerticalViewPager.this.setScrollState(SCROLL_STATE_IDLE);
            VerticalViewPager.this.populate();
        }
    };

    private int mScrollState = SCROLL_STATE_IDLE;

    /**
     * Used internally to monitor when adapters are switched.
     */
    interface OnAdapterChangeListener {
        public void onAdapterChanged(PagerAdapter oldAdapter,
                                     PagerAdapter newAdapter);
    }

    /**
     * Used internally to tag special types of child views that should be added
     * as pager decorations by default.
     */
    interface Decor {
    }

    public VerticalViewPager(Context context) {
        super(context);
        this.initViewPager();
    }

    public VerticalViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.initViewPager();
    }

    void initViewPager() {
        this.setWillNotDraw(false);
        this.setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        this.setFocusable(true);
        final Context context = this.getContext();
        this.mScroller = new Scroller(context, sInterpolator);
        final ViewConfiguration configuration = ViewConfiguration.get(context);
        final float density = context.getResources().getDisplayMetrics().density;

        this.mTouchSlop = ViewConfigurationCompat
                .getScaledPagingTouchSlop(configuration);
        this.mMinimumVelocity = (int) (MIN_FLING_VELOCITY * density);
        this.mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        this.mTopEdge = new EdgeEffectCompat(context);
        this.mBottomEdge = new EdgeEffectCompat(context);

        this.mFlingDistance = (int) (MIN_DISTANCE_FOR_FLING * density);
        this.mCloseEnough = (int) (CLOSE_ENOUGH * density);
        this.mDefaultGutterSize = (int) (DEFAULT_GUTTER_SIZE * density);

        ViewCompat
                .setAccessibilityDelegate(this, new MyAccessibilityDelegate());

        if (ViewCompat.getImportantForAccessibility(this) == ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            ViewCompat.setImportantForAccessibility(this,
                    ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        this.removeCallbacks(this.mEndScrollRunnable);
        super.onDetachedFromWindow();
    }

    private void setScrollState(int newState) {
        if (this.mScrollState == newState) {
            return;
        }

        this.mScrollState = newState;
        if (this.mPageTransformer != null) {
            // PageTransformers can do complex things that benefit from hardware
            // layers.
            this.enableLayers(newState != SCROLL_STATE_IDLE);
        }
        if (this.mOnPageChangeListener != null) {
            this.mOnPageChangeListener.onPageScrollStateChanged(newState);
        }
    }

    /**
     * Set a PagerAdapter that will supply views for this pager as needed.
     * @param adapter
     *            Adapter to use
     */
    public void setAdapter(PagerAdapter adapter) {
        if (this.mAdapter != null) {
            this.mAdapter.unregisterDataSetObserver(this.mObserver);
            this.mAdapter.startUpdate(this);
            for (int i = 0; i < this.mItems.size(); i++) {
                final ItemInfo ii = this.mItems.get(i);
                this.mAdapter.destroyItem(this, ii.position, ii.object);
            }
            this.mAdapter.finishUpdate(this);
            this.mItems.clear();
            this.removeNonDecorViews();
            this.mCurItem = 0;
            this.scrollTo(0, 0);
        }

        final PagerAdapter oldAdapter = this.mAdapter;
        this.mAdapter = adapter;
        this.mExpectedAdapterCount = 0;

        if (this.mAdapter != null) {
            if (this.mObserver == null) {
                this.mObserver = new PagerObserver();
            }
            this.mAdapter.registerDataSetObserver(this.mObserver);
            this.mPopulatePending = false;
            final boolean wasFirstLayout = this.mFirstLayout;
            this.mFirstLayout = true;
            this.mExpectedAdapterCount = this.mAdapter.getCount();
            if (this.mRestoredCurItem >= 0) {
                this.mAdapter.restoreState(this.mRestoredAdapterState,
                        this.mRestoredClassLoader);
                this.setCurrentItemInternal(this.mRestoredCurItem, false, true);
                this.mRestoredCurItem = -1;
                this.mRestoredAdapterState = null;
                this.mRestoredClassLoader = null;
            } else if (!wasFirstLayout) {
                this.populate();
            } else {
                this.requestLayout();
            }
        }

        if (this.mAdapterChangeListener != null && oldAdapter != adapter) {
            this.mAdapterChangeListener.onAdapterChanged(oldAdapter, adapter);
        }
    }

    private void removeNonDecorViews() {
        for (int i = 0; i < this.getChildCount(); i++) {
            final View child = this.getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (!lp.isDecor) {
                this.removeViewAt(i);
                i--;
            }
        }
    }

    /**
     * Retrieve the current adapter supplying pages.
     * @return The currently registered PagerAdapter
     */
    public PagerAdapter getAdapter() {
        return this.mAdapter;
    }

    void setOnAdapterChangeListener(OnAdapterChangeListener listener) {
        this.mAdapterChangeListener = listener;
    }

    // private int getClientWidth() {
    // return getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
    // }

    private int getClientHeight() {
        return this.getMeasuredHeight() - this.getPaddingTop()
                - this.getPaddingBottom();
    }

    /**
     * Set the currently selected page. If the ViewPager has already been
     * through its first layout with its current adapter there will be a smooth
     * animated transition between the current item and the specified item.
     * @param item
     *            Item index to select
     */
    public void setCurrentItem(int item) {
        this.mPopulatePending = false;
        this.setCurrentItemInternal(item, !this.mFirstLayout, false);
    }

    /**
     * Set the currently selected page.
     * @param item
     *            Item index to select
     * @param smoothScroll
     *            True to smoothly scroll to the new item, false to transition
     *            immediately
     */
    public void setCurrentItem(int item, boolean smoothScroll) {
        this.mPopulatePending = false;
        this.setCurrentItemInternal(item, smoothScroll, false);
    }

    public int getCurrentItem() {
        return this.mCurItem;
    }

    void setCurrentItemInternal(int item, boolean smoothScroll, boolean always) {
        this.setCurrentItemInternal(item, smoothScroll, always, 0);
    }

    void setCurrentItemInternal(int item, boolean smoothScroll, boolean always,
            int velocity) {
        if (this.mAdapter == null || this.mAdapter.getCount() <= 0) {
            this.setScrollingCacheEnabled(false);
            return;
        }
        if (!always && this.mCurItem == item && this.mItems.size() != 0) {
            this.setScrollingCacheEnabled(false);
            return;
        }

        if (item < 0) {
            item = 0;
        } else if (item >= this.mAdapter.getCount()) {
            item = this.mAdapter.getCount() - 1;
        }
        final int pageLimit = this.mOffscreenPageLimit;
        if (item > (this.mCurItem + pageLimit)
                || item < (this.mCurItem - pageLimit)) {
            // We are doing a jump by more than one page. To avoid
            // glitches, we want to keep all current pages in the view
            // until the scroll ends.
            for (int i = 0; i < this.mItems.size(); i++) {
                this.mItems.get(i).scrolling = true;
            }
        }
        final boolean dispatchSelected = this.mCurItem != item;

        if (this.mFirstLayout) {
            // We don't have any idea how big we are yet and shouldn't have any
            // pages either.
            // Just set things up and let the pending layout handle things.
            this.mCurItem = item;
            if (dispatchSelected && this.mOnPageChangeListener != null) {
                this.mOnPageChangeListener.onPageSelected(item);
            }
            if (dispatchSelected && this.mInternalPageChangeListener != null) {
                this.mInternalPageChangeListener.onPageSelected(item);
            }
            this.requestLayout();
        } else {
            this.populate(item);
            this.scrollToItem(item, smoothScroll, velocity, dispatchSelected);
        }
    }

    private void scrollToItem(int item, boolean smoothScroll, int velocity,
            boolean dispatchSelected) {
        final ItemInfo curInfo = this.infoForPosition(item);
        int destY = 0;
        if (curInfo != null) {
            final int height = this.getClientHeight();
            destY = (int) (height * Math.max(this.mFirstOffset,
                    Math.min(curInfo.offset, this.mLastOffset)));
        }
        if (smoothScroll) {
            this.smoothScrollTo(0, destY, velocity);
            if (dispatchSelected && this.mOnPageChangeListener != null) {
                this.mOnPageChangeListener.onPageSelected(item);
            }
            if (dispatchSelected && this.mInternalPageChangeListener != null) {
                this.mInternalPageChangeListener.onPageSelected(item);
            }
        } else {
            if (dispatchSelected && this.mOnPageChangeListener != null) {
                this.mOnPageChangeListener.onPageSelected(item);
            }
            if (dispatchSelected && this.mInternalPageChangeListener != null) {
                this.mInternalPageChangeListener.onPageSelected(item);
            }
            this.completeScroll(false);
            this.scrollTo(0, destY);
            this.pageScrolled(destY);
        }
    }

    /**
     * Set a listener that will be invoked whenever the page changes or is
     * incrementally scrolled. See
     * {@link android.support.v4.view.ViewPager.OnPageChangeListener}.
     * @param listener
     *            Listener to set
     */
    public void setOnPageChangeListener(ViewPager.OnPageChangeListener listener) {
        this.mOnPageChangeListener = listener;
    }

    /**
     * Set a {@link android.support.v4.view.ViewPager.PageTransformer} that will
     * be called for each attached page whenever the scroll position is changed.
     * This allows the application to apply custom property transformations to
     * each page, overriding the default sliding look and feel.
     * <p/>
     * <p>
     * <em>Note:</em> Prior to Android 3.0 the property animation APIs did not
     * exist. As a result, setting a PageTransformer prior to Android 3.0 (API
     * 11) will have no effect.
     * </p>
     * @param reverseDrawingOrder
     *            true if the supplied PageTransformer requires page views to be
     *            drawn from last to first instead of first to last.
     * @param transformer
     *            PageTransformer that will modify each page's animation
     *            properties
     */
    public void setPageTransformer(boolean reverseDrawingOrder,
            ViewPager.PageTransformer transformer) {
        if (Build.VERSION.SDK_INT >= 11) {
            final boolean hasTransformer = transformer != null;
            final boolean needsPopulate = hasTransformer != (this.mPageTransformer != null);
            this.mPageTransformer = transformer;
            this.setChildrenDrawingOrderEnabledCompat(hasTransformer);
            if (hasTransformer) {
                this.mDrawingOrder = reverseDrawingOrder ? DRAW_ORDER_REVERSE
                        : DRAW_ORDER_FORWARD;
            } else {
                this.mDrawingOrder = DRAW_ORDER_DEFAULT;
            }
            if (needsPopulate) {
                this.populate();
            }
        }
    }

    void setChildrenDrawingOrderEnabledCompat(boolean enable) {
        if (Build.VERSION.SDK_INT >= 7) {
            if (this.mSetChildrenDrawingOrderEnabled == null) {
                try {
                    this.mSetChildrenDrawingOrderEnabled = ViewGroup.class
                            .getDeclaredMethod(
                                    "setChildrenDrawingOrderEnabled",
                                    new Class[] { Boolean.TYPE });
                } catch (NoSuchMethodException e) {
                    Log.e(TAG, "Can't find setChildrenDrawingOrderEnabled", e);
                }
            }
            try {
                this.mSetChildrenDrawingOrderEnabled.invoke(this, enable);
            } catch (Exception e) {
                Log.e(TAG, "Error changing children drawing order", e);
            }
        }
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int i) {
        final int index = this.mDrawingOrder == DRAW_ORDER_REVERSE ? childCount
                - 1 - i : i;
        final int result = ((LayoutParams) this.mDrawingOrderedChildren.get(
                index).getLayoutParams()).childIndex;
        return result;
    }

    /**
     * Set a separate OnPageChangeListener for internal use by the support
     * library.
     * @param listener
     *            Listener to set
     * @return The old listener that was set, if any.
     */
    ViewPager.OnPageChangeListener setInternalPageChangeListener(
            ViewPager.OnPageChangeListener listener) {
        ViewPager.OnPageChangeListener oldListener = this.mInternalPageChangeListener;
        this.mInternalPageChangeListener = listener;
        return oldListener;
    }

    /**
     * Returns the number of pages that will be retained to either side of the
     * current page in the view hierarchy in an idle state. Defaults to 1.
     * @return How many pages will be kept offscreen on either side
     * @see #setOffscreenPageLimit(int)
     */
    public int getOffscreenPageLimit() {
        return this.mOffscreenPageLimit;
    }

    /**
     * Set the number of pages that should be retained to either side of the
     * current page in the view hierarchy in an idle state. Pages beyond this
     * limit will be recreated from the adapter when needed.
     * <p/>
     * <p>
     * This is offered as an optimization. If you know in advance the number of
     * pages you will need to support or have lazy-loading mechanisms in place
     * on your pages, tweaking this setting can have benefits in perceived
     * smoothness of paging animations and interaction. If you have a small
     * number of pages (3-4) that you can keep active all at once, less time
     * will be spent in layout for newly created view subtrees as the user pages
     * back and forth.
     * </p>
     * <p/>
     * <p>
     * You should keep this limit low, especially if your pages have complex
     * layouts. This setting defaults to 1.
     * </p>
     * @param limit
     *            How many pages will be kept offscreen in an idle state.
     */
    public void setOffscreenPageLimit(int limit) {
        if (limit < DEFAULT_OFFSCREEN_PAGES) {
            Log.w(TAG, "Requested offscreen page limit " + limit
                    + " too small; defaulting to " + DEFAULT_OFFSCREEN_PAGES);
            limit = DEFAULT_OFFSCREEN_PAGES;
        }
        if (limit != this.mOffscreenPageLimit) {
            this.mOffscreenPageLimit = limit;
            this.populate();
        }
    }

    /**
     * Set the margin between pages.
     * @param marginPixels
     *            Distance between adjacent pages in pixels
     * @see #getPageMargin()
     * @see #setPageMarginDrawable(Drawable)
     * @see #setPageMarginDrawable(int)
     */
    public void setPageMargin(int marginPixels) {
        final int oldMargin = this.mPageMargin;
        this.mPageMargin = marginPixels;

        final int height = this.getHeight();
        this.recomputeScrollPosition(height, height, marginPixels, oldMargin);

        this.requestLayout();
    }

    /**
     * Return the margin between pages.
     * @return The size of the margin in pixels
     */
    public int getPageMargin() {
        return this.mPageMargin;
    }

    /**
     * Set a drawable that will be used to fill the margin between pages.
     * @param d
     *            Drawable to display between pages
     */
    public void setPageMarginDrawable(Drawable d) {
        this.mMarginDrawable = d;
        if (d != null) {
            this.refreshDrawableState();
        }
        this.setWillNotDraw(d == null);
        this.invalidate();
    }

    /**
     * Set a drawable that will be used to fill the margin between pages.
     * @param resId
     *            Resource ID of a drawable to display between pages
     */
    public void setPageMarginDrawable(int resId) {
        this.setPageMarginDrawable(this.getContext().getResources()
                .getDrawable(resId));
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == this.mMarginDrawable;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        final Drawable d = this.mMarginDrawable;
        if (d != null && d.isStateful()) {
            d.setState(this.getDrawableState());
        }
    }

    // We want the duration of the page snap animation to be influenced by the
    // distance that
    // the screen has to travel, however, we don't want this duration to be
    // effected in a
    // purely linear fashion. Instead, we use this method to moderate the effect
    // that the distance
    // of travel has on the overall snap duration.
    float distanceInfluenceForSnapDuration(float f) {
        f -= 0.5f; // center the values about 0.
        f *= 0.3f * Math.PI / 2.0f;
        return (float) Math.sin(f);
    }

    /**
     * Like {@link View#scrollBy}, but scroll smoothly instead of immediately.
     * @param x
     *            the number of pixels to scroll by on the X axis
     * @param y
     *            the number of pixels to scroll by on the Y axis
     */
    void smoothScrollTo(int x, int y) {
        this.smoothScrollTo(x, y, 0);
    }

    /**
     * Like {@link View#scrollBy}, but scroll smoothly instead of immediately.
     * @param x
     *            the number of pixels to scroll by on the X axis
     * @param y
     *            the number of pixels to scroll by on the Y axis
     * @param velocity
     *            the velocity associated with a fling, if applicable. (0
     *            otherwise)
     */
    void smoothScrollTo(int x, int y, int velocity) {
        if (this.getChildCount() == 0) {
            // Nothing to do.
            this.setScrollingCacheEnabled(false);
            return;
        }
        int sx = this.getScrollX();
        int sy = this.getScrollY();
        int dx = x - sx;
        int dy = y - sy;
        if (dx == 0 && dy == 0) {
            this.completeScroll(false);
            this.populate();
            this.setScrollState(SCROLL_STATE_IDLE);
            return;
        }

        this.setScrollingCacheEnabled(true);
        this.setScrollState(SCROLL_STATE_SETTLING);

        final int height = this.getClientHeight();
        final int halfHeight = height / 2;
        final float distanceRatio = Math.min(1f, 1.0f * Math.abs(dx) / height);
        final float distance = halfHeight + halfHeight
                * this.distanceInfluenceForSnapDuration(distanceRatio);

        int duration = 0;
        velocity = Math.abs(velocity);
        if (velocity > 0) {
            duration = 4 * Math.round(1000 * Math.abs(distance / velocity));
        } else {
            final float pageHeight = height
                    * this.mAdapter.getPageWidth(this.mCurItem);
            final float pageDelta = Math.abs(dx)
                    / (pageHeight + this.mPageMargin);
            duration = (int) ((pageDelta + 1) * 100);
        }
        duration = Math.min(duration, MAX_SETTLE_DURATION);

        this.mScroller.startScroll(sx, sy, dx, dy, duration);
        ViewCompat.postInvalidateOnAnimation(this);
    }

    ItemInfo addNewItem(int position, int index) {
        ItemInfo ii = new ItemInfo();
        ii.position = position;
        ii.object = this.mAdapter.instantiateItem(this, position);
        ii.heightFactor = this.mAdapter.getPageWidth(position);
        if (index < 0 || index >= this.mItems.size()) {
            this.mItems.add(ii);
        } else {
            this.mItems.add(index, ii);
        }
        return ii;
    }

    void dataSetChanged() {
        // This method only gets called if our observer is attached, so mAdapter
        // is non-null.

        final int adapterCount = this.mAdapter.getCount();
        this.mExpectedAdapterCount = adapterCount;
        boolean needPopulate = this.mItems.size() < this.mOffscreenPageLimit * 2 + 1
                && this.mItems.size() < adapterCount;
        int newCurrItem = this.mCurItem;

        boolean isUpdating = false;
        for (int i = 0; i < this.mItems.size(); i++) {
            final ItemInfo ii = this.mItems.get(i);
            final int newPos = this.mAdapter.getItemPosition(ii.object);

            if (newPos == PagerAdapter.POSITION_UNCHANGED) {
                continue;
            }

            if (newPos == PagerAdapter.POSITION_NONE) {
                this.mItems.remove(i);
                i--;

                if (!isUpdating) {
                    this.mAdapter.startUpdate(this);
                    isUpdating = true;
                }

                this.mAdapter.destroyItem(this, ii.position, ii.object);
                needPopulate = true;

                if (this.mCurItem == ii.position) {
                    // Keep the current item in the valid range
                    newCurrItem = Math.max(0,
                            Math.min(this.mCurItem, adapterCount - 1));
                    needPopulate = true;
                }
                continue;
            }

            if (ii.position != newPos) {
                if (ii.position == this.mCurItem) {
                    // Our current item changed position. Follow it.
                    newCurrItem = newPos;
                }

                ii.position = newPos;
                needPopulate = true;
            }
        }

        if (isUpdating) {
            this.mAdapter.finishUpdate(this);
        }

        Collections.sort(this.mItems, COMPARATOR);

        if (needPopulate) {
            // Reset our known page widths; populate will recompute them.
            final int childCount = this.getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = this.getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (!lp.isDecor) {
                    lp.heightFactor = 0.f;
                }
            }

            this.setCurrentItemInternal(newCurrItem, false, true);
            this.requestLayout();
        }
    }

    void populate() {
        this.populate(this.mCurItem);
    }

    void populate(int newCurrentItem) {
        ItemInfo oldCurInfo = null;
        int focusDirection = View.FOCUS_FORWARD;
        if (this.mCurItem != newCurrentItem) {
            focusDirection = this.mCurItem < newCurrentItem ? View.FOCUS_DOWN
                    : View.FOCUS_UP;
            oldCurInfo = this.infoForPosition(this.mCurItem);
            this.mCurItem = newCurrentItem;
        }

        if (this.mAdapter == null) {
            this.sortChildDrawingOrder();
            return;
        }

        // Bail now if we are waiting to populate. This is to hold off
        // on creating views from the time the user releases their finger to
        // fling to a new position until we have finished the scroll to
        // that position, avoiding glitches from happening at that point.
        if (this.mPopulatePending) {
            if (DEBUG) {
                Log.i(TAG, "populate is pending, skipping for now...");
            }
            this.sortChildDrawingOrder();
            return;
        }

        // Also, don't populate until we are attached to a window. This is to
        // avoid trying to populate before we have restored our view hierarchy
        // state and conflicting with what is restored.
        if (this.getWindowToken() == null) {
            return;
        }

        this.mAdapter.startUpdate(this);

        final int pageLimit = this.mOffscreenPageLimit;
        final int startPos = Math.max(0, this.mCurItem - pageLimit);
        final int N = this.mAdapter.getCount();
        final int endPos = Math.min(N - 1, this.mCurItem + pageLimit);

        if (N != this.mExpectedAdapterCount) {
            String resName;
            try {
                resName = this.getResources().getResourceName(this.getId());
            } catch (Resources.NotFoundException e) {
                resName = Integer.toHexString(this.getId());
            }
            throw new IllegalStateException(
                    "The application's PagerAdapter changed the adapter's"
                            + " contents without calling PagerAdapter#notifyDataSetChanged!"
                            + " Expected adapter item count: "
                            + this.mExpectedAdapterCount + ", found: " + N
                            + " Pager id: " + resName + " Pager class: "
                            + this.getClass() + " Problematic adapter: "
                            + this.mAdapter.getClass());
        }

        // Locate the currently focused item or add it if needed.
        int curIndex = -1;
        ItemInfo curItem = null;
        for (curIndex = 0; curIndex < this.mItems.size(); curIndex++) {
            final ItemInfo ii = this.mItems.get(curIndex);
            if (ii.position >= this.mCurItem) {
                if (ii.position == this.mCurItem) {
                    curItem = ii;
                }
                break;
            }
        }

        if (curItem == null && N > 0) {
            curItem = this.addNewItem(this.mCurItem, curIndex);
        }

        // Fill 3x the available width or up to the number of offscreen
        // pages requested to either side, whichever is larger.
        // If we have no current item we have no work to do.
        if (curItem != null) {
            float extraHeightTop = 0.f;
            int itemIndex = curIndex - 1;
            ItemInfo ii = itemIndex >= 0 ? this.mItems.get(itemIndex) : null;
            final int clientHeight = this.getClientHeight();
            final float topHeightNeeded = clientHeight <= 0 ? 0 : 2.f
                    - curItem.heightFactor + (float) this.getPaddingLeft()
                    / (float) clientHeight;
            for (int pos = this.mCurItem - 1; pos >= 0; pos--) {
                if (extraHeightTop >= topHeightNeeded && pos < startPos) {
                    if (ii == null) {
                        break;
                    }
                    if (pos == ii.position && !ii.scrolling) {
                        this.mItems.remove(itemIndex);
                        this.mAdapter.destroyItem(this, pos, ii.object);
                        if (DEBUG) {
                            Log.i(TAG, "populate() - destroyItem() with pos: "
                                    + pos + " view: " + (ii.object));
                        }
                        itemIndex--;
                        curIndex--;
                        ii = itemIndex >= 0 ? this.mItems.get(itemIndex) : null;
                    }
                } else if (ii != null && pos == ii.position) {
                    extraHeightTop += ii.heightFactor;
                    itemIndex--;
                    ii = itemIndex >= 0 ? this.mItems.get(itemIndex) : null;
                } else {
                    ii = this.addNewItem(pos, itemIndex + 1);
                    extraHeightTop += ii.heightFactor;
                    curIndex++;
                    ii = itemIndex >= 0 ? this.mItems.get(itemIndex) : null;
                }
            }

            float extraHeightBottom = curItem.heightFactor;
            itemIndex = curIndex + 1;
            if (extraHeightBottom < 2.f) {
                ii = itemIndex < this.mItems.size() ? this.mItems
                        .get(itemIndex) : null;
                final float bottomHeightNeeded = clientHeight <= 0 ? 0
                        : (float) this.getPaddingRight() / (float) clientHeight
                                + 2.f;
                for (int pos = this.mCurItem + 1; pos < N; pos++) {
                    if (extraHeightBottom >= bottomHeightNeeded && pos > endPos) {
                        if (ii == null) {
                            break;
                        }
                        if (pos == ii.position && !ii.scrolling) {
                            this.mItems.remove(itemIndex);
                            this.mAdapter.destroyItem(this, pos, ii.object);
                            if (DEBUG) {
                                Log.i(TAG,
                                        "populate() - destroyItem() with pos: "
                                                + pos + " view: " + (ii.object));
                            }
                            ii = itemIndex < this.mItems.size() ? this.mItems
                                    .get(itemIndex) : null;
                        }
                    } else if (ii != null && pos == ii.position) {
                        extraHeightBottom += ii.heightFactor;
                        itemIndex++;
                        ii = itemIndex < this.mItems.size() ? this.mItems
                                .get(itemIndex) : null;
                    } else {
                        ii = this.addNewItem(pos, itemIndex);
                        itemIndex++;
                        extraHeightBottom += ii.heightFactor;
                        ii = itemIndex < this.mItems.size() ? this.mItems
                                .get(itemIndex) : null;
                    }
                }
            }

            this.calculatePageOffsets(curItem, curIndex, oldCurInfo);
        }

        if (DEBUG) {
            Log.i(TAG, "Current page list:");
            for (int i = 0; i < this.mItems.size(); i++) {
                Log.i(TAG, "#" + i + ": page " + this.mItems.get(i).position);
            }
        }

        this.mAdapter.setPrimaryItem(this, this.mCurItem,
                curItem != null ? curItem.object : null);

        this.mAdapter.finishUpdate(this);

        // Check width measurement of current pages and drawing sort order.
        // Update LayoutParams as needed.
        final int childCount = this.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = this.getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            lp.childIndex = i;
            if (!lp.isDecor && lp.heightFactor == 0.f) {
                // 0 means requery the adapter for this, it doesn't have a valid
                // width.
                final ItemInfo ii = this.infoForChild(child);
                if (ii != null) {
                    lp.heightFactor = ii.heightFactor;
                    lp.position = ii.position;
                }
            }
        }
        this.sortChildDrawingOrder();

        if (this.hasFocus()) {
            View currentFocused = this.findFocus();
            ItemInfo ii = currentFocused != null ? this
                    .infoForAnyChild(currentFocused) : null;
            if (ii == null || ii.position != this.mCurItem) {
                for (int i = 0; i < this.getChildCount(); i++) {
                    View child = this.getChildAt(i);
                    ii = this.infoForChild(child);
                    if (ii != null && ii.position == this.mCurItem) {
                        if (child.requestFocus(focusDirection)) {
                            break;
                        }
                    }
                }
            }
        }
    }

    private void sortChildDrawingOrder() {
        if (this.mDrawingOrder != DRAW_ORDER_DEFAULT) {
            if (this.mDrawingOrderedChildren == null) {
                this.mDrawingOrderedChildren = new ArrayList<View>();
            } else {
                this.mDrawingOrderedChildren.clear();
            }
            final int childCount = this.getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = this.getChildAt(i);
                this.mDrawingOrderedChildren.add(child);
            }
            Collections.sort(this.mDrawingOrderedChildren, sPositionComparator);
        }
    }

    private void calculatePageOffsets(ItemInfo curItem, int curIndex,
            ItemInfo oldCurInfo) {
        final int N = this.mAdapter.getCount();
        final int height = this.getClientHeight();
        final float marginOffset = height > 0 ? (float) this.mPageMargin
                / height : 0;
        // Fix up offsets for later layout.
        if (oldCurInfo != null) {
            final int oldCurPosition = oldCurInfo.position;
            // Base offsets off of oldCurInfo.
            if (oldCurPosition < curItem.position) {
                int itemIndex = 0;
                ItemInfo ii = null;
                float offset = oldCurInfo.offset + oldCurInfo.heightFactor
                        + marginOffset;
                for (int pos = oldCurPosition + 1; pos <= curItem.position
                        && itemIndex < this.mItems.size(); pos++) {
                    ii = this.mItems.get(itemIndex);
                    while (pos > ii.position
                            && itemIndex < this.mItems.size() - 1) {
                        itemIndex++;
                        ii = this.mItems.get(itemIndex);
                    }
                    while (pos < ii.position) {
                        // We don't have an item populated for this,
                        // ask the adapter for an offset.
                        offset += this.mAdapter.getPageWidth(pos)
                                + marginOffset;
                        pos++;
                    }
                    ii.offset = offset;
                    offset += ii.heightFactor + marginOffset;
                }
            } else if (oldCurPosition > curItem.position) {
                int itemIndex = this.mItems.size() - 1;
                ItemInfo ii = null;
                float offset = oldCurInfo.offset;
                for (int pos = oldCurPosition - 1; pos >= curItem.position
                        && itemIndex >= 0; pos--) {
                    ii = this.mItems.get(itemIndex);
                    while (pos < ii.position && itemIndex > 0) {
                        itemIndex--;
                        ii = this.mItems.get(itemIndex);
                    }
                    while (pos > ii.position) {
                        // We don't have an item populated for this,
                        // ask the adapter for an offset.
                        offset -= this.mAdapter.getPageWidth(pos)
                                + marginOffset;
                        pos--;
                    }
                    offset -= ii.heightFactor + marginOffset;
                    ii.offset = offset;
                }
            }
        }

        // Base all offsets off of curItem.
        final int itemCount = this.mItems.size();
        float offset = curItem.offset;
        int pos = curItem.position - 1;
        this.mFirstOffset = curItem.position == 0 ? curItem.offset
                : -Float.MAX_VALUE;
        this.mLastOffset = curItem.position == N - 1 ? curItem.offset
                + curItem.heightFactor - 1 : Float.MAX_VALUE;
        // Previous pages
        for (int i = curIndex - 1; i >= 0; i--, pos--) {
            final ItemInfo ii = this.mItems.get(i);
            while (pos > ii.position) {
                offset -= this.mAdapter.getPageWidth(pos--) + marginOffset;
            }
            offset -= ii.heightFactor + marginOffset;
            ii.offset = offset;
            if (ii.position == 0) {
                this.mFirstOffset = offset;
            }
        }
        offset = curItem.offset + curItem.heightFactor + marginOffset;
        pos = curItem.position + 1;
        // Next pages
        for (int i = curIndex + 1; i < itemCount; i++, pos++) {
            final ItemInfo ii = this.mItems.get(i);
            while (pos < ii.position) {
                offset += this.mAdapter.getPageWidth(pos++) + marginOffset;
            }
            if (ii.position == N - 1) {
                this.mLastOffset = offset + ii.heightFactor - 1;
            }
            ii.offset = offset;
            offset += ii.heightFactor + marginOffset;
        }

        this.mNeedCalculatePageOffsets = false;
    }

    /**
     * This is the persistent state that is saved by ViewPager. Only needed if
     * you are creating a sublass of ViewPager that must save its own state, in
     * which case it should implement a subclass of this which contains that
     * state.
     */
    public static class SavedState extends BaseSavedState {
        int position;
        Parcelable adapterState;
        ClassLoader loader;

        public SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(this.position);
            out.writeParcelable(this.adapterState, flags);
        }

        @Override
        public String toString() {
            return "FragmentPager.SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " position=" + this.position + "}";
        }

        public static final Parcelable.Creator<SavedState> CREATOR = ParcelableCompat
                .newCreator(new ParcelableCompatCreatorCallbacks<SavedState>() {
                    @Override
                    public SavedState createFromParcel(Parcel in,
                            ClassLoader loader) {
                        return new SavedState(in, loader);
                    }

                    @Override
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                });

        SavedState(Parcel in, ClassLoader loader) {
            super(in);
            if (loader == null) {
                loader = this.getClass().getClassLoader();
            }
            this.position = in.readInt();
            this.adapterState = in.readParcelable(loader);
            this.loader = loader;
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);
        ss.position = this.mCurItem;
        if (this.mAdapter != null) {
            ss.adapterState = this.mAdapter.saveState();
        }
        return ss;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }

        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());

        if (this.mAdapter != null) {
            this.mAdapter.restoreState(ss.adapterState, ss.loader);
            this.setCurrentItemInternal(ss.position, false, true);
        } else {
            this.mRestoredCurItem = ss.position;
            this.mRestoredAdapterState = ss.adapterState;
            this.mRestoredClassLoader = ss.loader;
        }
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (!this.checkLayoutParams(params)) {
            params = this.generateLayoutParams(params);
        }
        final LayoutParams lp = (LayoutParams) params;
        lp.isDecor |= child instanceof Decor;
        if (this.mInLayout) {
            if (lp != null && lp.isDecor) {
                throw new IllegalStateException(
                        "Cannot add pager decor view during layout");
            }
            lp.needsMeasure = true;
            this.addViewInLayout(child, index, params);
        } else {
            super.addView(child, index, params);
        }

        if (USE_CACHE) {
            if (child.getVisibility() != GONE) {
                child.setDrawingCacheEnabled(this.mScrollingCacheEnabled);
            } else {
                child.setDrawingCacheEnabled(false);
            }
        }
    }

    @Override
    public void removeView(View view) {
        if (this.mInLayout) {
            this.removeViewInLayout(view);
        } else {
            super.removeView(view);
        }
    }

    ItemInfo infoForChild(View child) {
        for (int i = 0; i < this.mItems.size(); i++) {
            ItemInfo ii = this.mItems.get(i);
            if (this.mAdapter.isViewFromObject(child, ii.object)) {
                return ii;
            }
        }
        return null;
    }

    ItemInfo infoForAnyChild(View child) {
        ViewParent parent;
        while ((parent = child.getParent()) != this) {
            if (parent == null || !(parent instanceof View)) {
                return null;
            }
            child = (View) parent;
        }
        return this.infoForChild(child);
    }

    ItemInfo infoForPosition(int position) {
        for (int i = 0; i < this.mItems.size(); i++) {
            ItemInfo ii = this.mItems.get(i);
            if (ii.position == position) {
                return ii;
            }
        }
        return null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        this.mFirstLayout = true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // For simple implementation, our internal size is always 0.
        // We depend on the container to specify the layout size of
        // our view. We can't really know what it is since we will be
        // adding and removing different arbitrary views and do not
        // want the layout to change as this happens.
        this.setMeasuredDimension(getDefaultSize(0, widthMeasureSpec),
                getDefaultSize(0, heightMeasureSpec));

        final int measuredHeight = this.getMeasuredHeight();
        final int maxGutterSize = measuredHeight / 10;
        this.mGutterSize = Math.min(maxGutterSize, this.mDefaultGutterSize);

        // Children are just made to fill our space.
        int childWidthSize = this.getMeasuredWidth() - this.getPaddingLeft()
                - this.getPaddingRight();
        int childHeightSize = measuredHeight - this.getPaddingTop()
                - this.getPaddingBottom();

        /*
         * Make sure all children have been properly measured. Decor views
         * first. Right now we cheat and make this less complicated by assuming
         * decor views won't intersect. We will pin to edges based on gravity.
         */
        int size = this.getChildCount();
        for (int i = 0; i < size; ++i) {
            final View child = this.getChildAt(i);
            if (child.getVisibility() != GONE) {
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (lp != null && lp.isDecor) {
                    final int hgrav = lp.gravity
                            & Gravity.HORIZONTAL_GRAVITY_MASK;
                    final int vgrav = lp.gravity
                            & Gravity.VERTICAL_GRAVITY_MASK;
                    int widthMode = MeasureSpec.AT_MOST;
                    int heightMode = MeasureSpec.AT_MOST;
                    boolean consumeVertical = vgrav == Gravity.TOP
                            || vgrav == Gravity.BOTTOM;
                    boolean consumeHorizontal = hgrav == Gravity.LEFT
                            || hgrav == Gravity.RIGHT;

                    if (consumeVertical) {
                        widthMode = MeasureSpec.EXACTLY;
                    } else if (consumeHorizontal) {
                        heightMode = MeasureSpec.EXACTLY;
                    }

                    int widthSize = childWidthSize;
                    int heightSize = childHeightSize;
                    if (lp.width != LayoutParams.WRAP_CONTENT) {
                        widthMode = MeasureSpec.EXACTLY;
                        if (lp.width != LayoutParams.FILL_PARENT) {
                            widthSize = lp.width;
                        }
                    }
                    if (lp.height != LayoutParams.WRAP_CONTENT) {
                        heightMode = MeasureSpec.EXACTLY;
                        if (lp.height != LayoutParams.FILL_PARENT) {
                            heightSize = lp.height;
                        }
                    }
                    final int widthSpec = MeasureSpec.makeMeasureSpec(
                            widthSize, widthMode);
                    final int heightSpec = MeasureSpec.makeMeasureSpec(
                            heightSize, heightMode);
                    child.measure(widthSpec, heightSpec);

                    if (consumeVertical) {
                        childHeightSize -= child.getMeasuredHeight();
                    } else if (consumeHorizontal) {
                        childWidthSize -= child.getMeasuredWidth();
                    }
                }
            }
        }

        this.mChildWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                childWidthSize, MeasureSpec.EXACTLY);
        this.mChildHeightMeasureSpec = MeasureSpec.makeMeasureSpec(
                childHeightSize, MeasureSpec.EXACTLY);

        // Make sure we have created all fragments that we need to have shown.
        this.mInLayout = true;
        this.populate();
        this.mInLayout = false;

        // Page views next.
        size = this.getChildCount();
        for (int i = 0; i < size; ++i) {
            final View child = this.getChildAt(i);
            if (child.getVisibility() != GONE) {
                if (DEBUG) {
                    Log.v(TAG, "Measuring #" + i + " " + child + ": "
                            + this.mChildWidthMeasureSpec);
                }

                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (lp == null || !lp.isDecor) {
                    final int heightSpec = MeasureSpec.makeMeasureSpec(
                            (int) (childHeightSize * lp.heightFactor),
                            MeasureSpec.EXACTLY);
                    child.measure(this.mChildWidthMeasureSpec, heightSpec);
                }
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // Make sure scroll position is set correctly.
        if (h != oldh) {
            this.recomputeScrollPosition(h, oldh, this.mPageMargin,
                    this.mPageMargin);
        }
    }

    private void recomputeScrollPosition(int height, int oldHeight, int margin,
            int oldMargin) {
        if (oldHeight > 0 && !this.mItems.isEmpty()) {
            final int heightWithMargin = height - this.getPaddingTop()
                    - this.getPaddingBottom() + margin;
            final int oldHeightWithMargin = oldHeight - this.getPaddingTop()
                    - this.getPaddingBottom() + oldMargin;
            final int ypos = this.getScrollY();
            final float pageOffset = (float) ypos / oldHeightWithMargin;
            final int newOffsetPixels = (int) (pageOffset * heightWithMargin);

            this.scrollTo(this.getScrollX(), newOffsetPixels);
            if (!this.mScroller.isFinished()) {
                // We now return to your regularly scheduled scroll, already in
                // progress.
                final int newDuration = this.mScroller.getDuration()
                        - this.mScroller.timePassed();
                ItemInfo targetInfo = this.infoForPosition(this.mCurItem);
                this.mScroller.startScroll(0, newOffsetPixels, 0,
                        (int) (targetInfo.offset * height), newDuration);
            }
        } else {
            final ItemInfo ii = this.infoForPosition(this.mCurItem);
            final float scrollOffset = ii != null ? Math.min(ii.offset,
                    this.mLastOffset) : 0;
            final int scrollPos = (int) (scrollOffset * (height
                    - this.getPaddingTop() - this.getPaddingBottom()));
            if (scrollPos != this.getScrollY()) {
                this.completeScroll(false);
                this.scrollTo(this.getScrollX(), scrollPos);
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int count = this.getChildCount();
        int width = r - l;
        int height = b - t;
        int paddingLeft = this.getPaddingLeft();
        int paddingTop = this.getPaddingTop();
        int paddingRight = this.getPaddingRight();
        int paddingBottom = this.getPaddingBottom();
        final int scrollY = this.getScrollY();

        int decorCount = 0;

        // First pass - decor views. We need to do this in two passes so that
        // we have the proper offsets for non-decor views later.
        for (int i = 0; i < count; i++) {
            final View child = this.getChildAt(i);
            if (child.getVisibility() != GONE) {
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                int childLeft = 0;
                int childTop = 0;
                if (lp.isDecor) {
                    final int hgrav = lp.gravity
                            & Gravity.HORIZONTAL_GRAVITY_MASK;
                    final int vgrav = lp.gravity
                            & Gravity.VERTICAL_GRAVITY_MASK;
                    switch (hgrav) {
                    default:
                        childLeft = paddingLeft;
                        break;
                    case Gravity.LEFT:
                        childLeft = paddingLeft;
                        paddingLeft += child.getMeasuredWidth();
                        break;
                    case Gravity.CENTER_HORIZONTAL:
                        childLeft = Math.max(
                                (width - child.getMeasuredWidth()) / 2,
                                paddingLeft);
                        break;
                    case Gravity.RIGHT:
                        childLeft = width - paddingRight
                                - child.getMeasuredWidth();
                        paddingRight += child.getMeasuredWidth();
                        break;
                    }
                    switch (vgrav) {
                    default:
                        childTop = paddingTop;
                        break;
                    case Gravity.TOP:
                        childTop = paddingTop;
                        paddingTop += child.getMeasuredHeight();
                        break;
                    case Gravity.CENTER_VERTICAL:
                        childTop = Math.max(
                                (height - child.getMeasuredHeight()) / 2,
                                paddingTop);
                        break;
                    case Gravity.BOTTOM:
                        childTop = height - paddingBottom
                                - child.getMeasuredHeight();
                        paddingBottom += child.getMeasuredHeight();
                        break;
                    }
                    childTop += scrollY;
                    child.layout(childLeft, childTop,
                            childLeft + child.getMeasuredWidth(), childTop
                                    + child.getMeasuredHeight());
                    decorCount++;
                }
            }
        }

        final int childHeight = height - paddingTop - paddingBottom;
        // Page views. Do this once we have the right padding offsets from
        // above.
        for (int i = 0; i < count; i++) {
            final View child = this.getChildAt(i);
            if (child.getVisibility() != GONE) {
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                ItemInfo ii;
                if (!lp.isDecor && (ii = this.infoForChild(child)) != null) {
                    int toff = (int) (childHeight * ii.offset);
                    int childLeft = paddingLeft;
                    int childTop = paddingTop + toff;
                    if (lp.needsMeasure) {
                        // This was added during layout and needs measurement.
                        // Do it now that we know what we're working with.
                        lp.needsMeasure = false;
                        final int widthSpec = MeasureSpec.makeMeasureSpec(width
                                - paddingLeft - paddingRight,
                                MeasureSpec.EXACTLY);
                        final int heightSpec = MeasureSpec.makeMeasureSpec(
                                (int) (childHeight * lp.heightFactor),
                                MeasureSpec.EXACTLY);
                        child.measure(widthSpec, heightSpec);
                    }
                    if (DEBUG) {
                        Log.v(TAG,
                                "Positioning #" + i + " " + child + " f="
                                        + ii.object + ":" + childLeft + ","
                                        + childTop + " "
                                        + child.getMeasuredWidth() + "x"
                                        + child.getMeasuredHeight());
                    }
                    child.layout(childLeft, childTop,
                            childLeft + child.getMeasuredWidth(), childTop
                                    + child.getMeasuredHeight());
                }
            }
        }
        this.mLeftPageBounds = paddingLeft;
        this.mRightPageBounds = width - paddingRight;
        this.mDecorChildCount = decorCount;

        if (this.mFirstLayout) {
            this.scrollToItem(this.mCurItem, false, 0, false);
        }
        this.mFirstLayout = false;
    }

    @Override
    public void computeScroll() {
        if (!this.mScroller.isFinished()
                && this.mScroller.computeScrollOffset()) {
            int oldX = this.getScrollX();
            int oldY = this.getScrollY();
            int x = this.mScroller.getCurrX();
            int y = this.mScroller.getCurrY();

            if (oldX != x || oldY != y) {
                this.scrollTo(x, y);
                if (!this.pageScrolled(y)) {
                    this.mScroller.abortAnimation();
                    this.scrollTo(x, 0);
                }
            }

            // Keep on drawing until the animation has finished.
            ViewCompat.postInvalidateOnAnimation(this);
            return;
        }

        // Done with scroll, clean up state.
        this.completeScroll(true);
    }

    private boolean pageScrolled(int ypos) {
        if (this.mItems.size() == 0) {
            this.mCalledSuper = false;
            this.onPageScrolled(0, 0, 0);
            if (!this.mCalledSuper) {
                throw new IllegalStateException(
                        "onPageScrolled did not call superclass implementation");
            }
            return false;
        }
        final ItemInfo ii = this.infoForCurrentScrollPosition();
        final int height = this.getClientHeight();
        final int heightWithMargin = height + this.mPageMargin;
        final float marginOffset = (float) this.mPageMargin / height;
        final int currentPage = ii.position;
        final float pageOffset = (((float) ypos / height) - ii.offset)
                / (ii.heightFactor + marginOffset);
        final int offsetPixels = (int) (pageOffset * heightWithMargin);

        this.mCalledSuper = false;
        this.onPageScrolled(currentPage, pageOffset, offsetPixels);
        if (!this.mCalledSuper) {
            throw new IllegalStateException(
                    "onPageScrolled did not call superclass implementation");
        }
        return true;
    }

    /**
     * This method will be invoked when the current page is scrolled, either as
     * part of a programmatically initiated smooth scroll or a user initiated
     * touch scroll. If you override this method you must call through to the
     * superclass implementation (e.g. super.onPageScrolled(position, offset,
     * offsetPixels)) before onPageScrolled returns.
     * @param position
     *            Position index of the first page currently being displayed.
     *            Page position+1 will be visible if positionOffset is nonzero.
     * @param offset
     *            Value from [0, 1) indicating the offset from the page at
     *            position.
     * @param offsetPixels
     *            Value in pixels indicating the offset from position.
     */
    protected void onPageScrolled(int position, float offset, int offsetPixels) {
        // Offset any decor views if needed - keep them on-screen at all times.
        if (this.mDecorChildCount > 0) {
            final int scrollY = this.getScrollY();
            int paddingTop = this.getPaddingTop();
            int paddingBottom = this.getPaddingBottom();
            final int height = this.getHeight();
            final int childCount = this.getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = this.getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (!lp.isDecor) {
                    continue;
                }

                final int vgrav = lp.gravity & Gravity.VERTICAL_GRAVITY_MASK;
                int childTop = 0;
                switch (vgrav) {
                default:
                    childTop = paddingTop;
                    break;
                case Gravity.TOP:
                    childTop = paddingTop;
                    paddingTop += child.getHeight();
                    break;
                case Gravity.CENTER_VERTICAL:
                    childTop = Math.max(
                            (height - child.getMeasuredHeight()) / 2,
                            paddingTop);
                    break;
                case Gravity.BOTTOM:
                    childTop = height - paddingBottom
                            - child.getMeasuredHeight();
                    paddingBottom += child.getMeasuredHeight();
                    break;
                }
                childTop += scrollY;

                final int childOffset = childTop - child.getTop();
                if (childOffset != 0) {
                    child.offsetTopAndBottom(childOffset);
                }
            }
        }

        if (this.mOnPageChangeListener != null) {
            this.mOnPageChangeListener.onPageScrolled(position, offset,
                    offsetPixels);
        }
        if (this.mInternalPageChangeListener != null) {
            this.mInternalPageChangeListener.onPageScrolled(position, offset,
                    offsetPixels);
        }

        if (this.mPageTransformer != null) {
            final int scrollY = this.getScrollY();
            final int childCount = this.getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = this.getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();

                if (lp.isDecor) {
                    continue;
                }

                final float transformPos = (float) (child.getTop() - scrollY)
                        / this.getClientHeight();
                this.mPageTransformer.transformPage(child, transformPos);
            }
        }

        this.mCalledSuper = true;
    }

    private void completeScroll(boolean postEvents) {
        boolean needPopulate = this.mScrollState == SCROLL_STATE_SETTLING;
        if (needPopulate) {
            // Done with scroll, no longer want to cache view drawing.
            this.setScrollingCacheEnabled(false);
            this.mScroller.abortAnimation();
            int oldX = this.getScrollX();
            int oldY = this.getScrollY();
            int x = this.mScroller.getCurrX();
            int y = this.mScroller.getCurrY();
            if (oldX != x || oldY != y) {
                this.scrollTo(x, y);
            }
        }
        this.mPopulatePending = false;
        for (int i = 0; i < this.mItems.size(); i++) {
            ItemInfo ii = this.mItems.get(i);
            if (ii.scrolling) {
                needPopulate = true;
                ii.scrolling = false;
            }
        }
        if (needPopulate) {
            if (postEvents) {
                ViewCompat.postOnAnimation(this, this.mEndScrollRunnable);
            } else {
                this.mEndScrollRunnable.run();
            }
        }
    }

    private boolean isGutterDrag(float y, float dy) {
        return (y < this.mGutterSize && dy > 0)
                || (y > this.getHeight() - this.mGutterSize && dy < 0);
    }

    private void enableLayers(boolean enable) {
        final int childCount = this.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final int layerType = enable ? ViewCompat.LAYER_TYPE_HARDWARE
                    : ViewCompat.LAYER_TYPE_NONE;
            ViewCompat.setLayerType(this.getChildAt(i), layerType, null);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        try {
        /*
         * This method JUST determines whether we want to intercept the motion.
         * If we return true, onMotionEvent will be called and we do the actual
         * scrolling there.
         */

            final int action = ev.getAction() & MotionEventCompat.ACTION_MASK;

            // Always take care of the touch gesture being complete.
            if (action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_UP) {
                // Release the drag.
                if (DEBUG) {
                    Log.v(TAG, "Intercept done!");
                }
                this.mIsBeingDragged = false;
                this.mIsUnableToDrag = false;
                this.mActivePointerId = INVALID_POINTER;
                if (this.mVelocityTracker != null) {
                    this.mVelocityTracker.recycle();
                    this.mVelocityTracker = null;
                }
                return false;
            }

            // Nothing more to do here if we have decided whether or not we
            // are dragging.
            if (action != MotionEvent.ACTION_DOWN) {
                if (this.mIsBeingDragged) {
                    if (DEBUG) {
                        Log.v(TAG, "Intercept returning true!");
                    }
                    return true;
                }
                if (this.mIsUnableToDrag) {
                    if (DEBUG) {
                        Log.v(TAG, "Intercept returning false!");
                    }
                    return false;
                }
            }

            switch (action) {
                case MotionEvent.ACTION_MOVE: {
            /*
             * mIsBeingDragged == false, otherwise the shortcut would have
             * caught it. Check whether the user has moved far enough from his
             * original down touch.
             */

            /*
             * Locally do absolute value. mLastMotionY is set to the y value of
             * the down event.
             */
                    final int activePointerId = this.mActivePointerId;
                    if (activePointerId == INVALID_POINTER) {
                        // If we don't have a valid id, the touch down wasn't on
                        // content.
                        break;
                    }

                    final int pointerIndex = MotionEventCompat.findPointerIndex(ev,
                            activePointerId);
                    final float y = MotionEventCompat.getY(ev, pointerIndex);
                    final float dy = y - this.mLastMotionY;
                    final float yDiff = Math.abs(dy);
                    final float x = MotionEventCompat.getX(ev, pointerIndex);
                    final float xDiff = Math.abs(x - this.mInitialMotionX);
                    if (DEBUG) {
                        Log.v(TAG, "Moved x to " + x + "," + y + " diff=" + xDiff + ","
                                + yDiff);
                    }

                    if (dy != 0 && !this.isGutterDrag(this.mLastMotionY, dy)
                            && this.canScroll(this, false, (int) dy, (int) x, (int) y)) {
                        // Nested view has scrollable area under this point. Let it be
                        // handled there.
                        this.mLastMotionX = x;
                        this.mLastMotionY = y;
                        this.mIsUnableToDrag = true;
                        return false;
                    }
                    if (yDiff > this.mTouchSlop && yDiff * 0.5f > xDiff) {
                        if (DEBUG) {
                            Log.v(TAG, "Starting drag!");
                        }
                        this.mIsBeingDragged = true;
                        this.requestParentDisallowInterceptTouchEvent(true);
                        this.setScrollState(SCROLL_STATE_DRAGGING);
                        this.mLastMotionY = dy > 0 ? this.mInitialMotionY
                                + this.mTouchSlop : this.mInitialMotionY
                                - this.mTouchSlop;
                        this.mLastMotionX = x;
                        this.setScrollingCacheEnabled(true);
                    } else if (xDiff > this.mTouchSlop) {
                        // The finger has moved enough in the vertical
                        // direction to be counted as a drag... abort
                        // any attempt to drag horizontally, to work correctly
                        // with children that have scrolling containers.
                        if (DEBUG) {
                            Log.v(TAG, "Starting unable to drag!");
                        }
                        this.mIsUnableToDrag = true;
                    }
                    if (this.mIsBeingDragged) {
                        // Scroll to follow the motion event
                        if (this.performDrag(y)) {
                            ViewCompat.postInvalidateOnAnimation(this);
                        }
                    }
                    break;
                }

                case MotionEvent.ACTION_DOWN: {
            /*
             * Remember location of down touch. ACTION_DOWN always refers to
             * pointer index 0.
             */
                    this.mLastMotionX = this.mInitialMotionX = ev.getX();
                    this.mLastMotionY = this.mInitialMotionY = ev.getY();
                    this.mActivePointerId = MotionEventCompat.getPointerId(ev, 0);
                    this.mIsUnableToDrag = false;

                    this.mScroller.computeScrollOffset();
                    if (this.mScrollState == SCROLL_STATE_SETTLING
                            && Math.abs(this.mScroller.getFinalY()
                            - this.mScroller.getCurrY()) > this.mCloseEnough) {
                        // Let the user 'catch' the pager as it animates.
                        this.mScroller.abortAnimation();
                        this.mPopulatePending = false;
                        this.populate();
                        this.mIsBeingDragged = true;
                        this.requestParentDisallowInterceptTouchEvent(true);
                        this.setScrollState(SCROLL_STATE_DRAGGING);
                    } else {
                        this.completeScroll(false);
                        this.mIsBeingDragged = false;
                    }

                    if (DEBUG) {
                        Log.v(TAG, "Down at " + this.mLastMotionX + ","
                                + this.mLastMotionY + " mIsBeingDragged="
                                + this.mIsBeingDragged + "mIsUnableToDrag="
                                + this.mIsUnableToDrag);
                    }
                    break;
                }

                case MotionEventCompat.ACTION_POINTER_UP:
                    this.onSecondaryPointerUp(ev);
                    break;
            }

            if (this.mVelocityTracker == null) {
                this.mVelocityTracker = VelocityTracker.obtain();
            }
            this.mVelocityTracker.addMovement(ev);
        }catch (IllegalArgumentException ex){
            ex.printStackTrace();
        }
        /*
         * The only time we want to intercept motion events is if we are in the
         * drag mode.
         */
        return this.mIsBeingDragged;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        try {
            if (this.mFakeDragging) {
                // A fake drag is in progress already, ignore this real one
                // but still eat the touch events.
                // (It is likely that the user is multi-touching the screen.)
                return true;
            }

            if (ev.getAction() == MotionEvent.ACTION_DOWN && ev.getEdgeFlags() != 0) {
                // Don't handle edge touches immediately -- they may actually belong
                // to one of our
                // descendants.
                return false;
            }

            if (this.mAdapter == null || this.mAdapter.getCount() == 0) {
                // Nothing to present or scroll; nothing to touch.
                return false;
            }

            if (this.mVelocityTracker == null) {
                this.mVelocityTracker = VelocityTracker.obtain();
            }
            this.mVelocityTracker.addMovement(ev);

            final int action = ev.getAction();
            boolean needsInvalidate = false;

            switch (action & MotionEventCompat.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN: {
                    this.mScroller.abortAnimation();
                    this.mPopulatePending = false;
                    this.populate();

                    // Remember where the motion event started
                    this.mLastMotionX = this.mInitialMotionX = ev.getX();
                    this.mLastMotionY = this.mInitialMotionY = ev.getY();
                    this.mActivePointerId = MotionEventCompat.getPointerId(ev, 0);
                    break;
                }
                case MotionEvent.ACTION_MOVE:
                    if (!this.mIsBeingDragged) {
                        final int pointerIndex = MotionEventCompat.findPointerIndex(ev,
                                this.mActivePointerId);
                        final float y = MotionEventCompat.getY(ev, pointerIndex);
                        final float yDiff = Math.abs(y - this.mLastMotionY);
                        final float x = MotionEventCompat.getX(ev, pointerIndex);
                        final float xDiff = Math.abs(x - this.mLastMotionX);
                        if (DEBUG) {
                            Log.v(TAG, "Moved x to " + x + "," + y + " diff=" + xDiff
                                    + "," + yDiff);
                        }
                        if (yDiff > this.mTouchSlop && yDiff > xDiff) {
                            if (DEBUG) {
                                Log.v(TAG, "Starting drag!");
                            }
                            this.mIsBeingDragged = true;
                            this.requestParentDisallowInterceptTouchEvent(true);
                            this.mLastMotionY = y - this.mInitialMotionY > 0 ? this.mInitialMotionY
                                    + this.mTouchSlop
                                    : this.mInitialMotionY - this.mTouchSlop;
                            this.mLastMotionX = x;
                            this.setScrollState(SCROLL_STATE_DRAGGING);
                            this.setScrollingCacheEnabled(true);

                            // Disallow Parent Intercept, just in case
                            ViewParent parent = this.getParent();
                            if (parent != null) {
                                parent.requestDisallowInterceptTouchEvent(true);
                            }
                        }
                    }
                    // Not else! Note that mIsBeingDragged can be set above.
                    if (this.mIsBeingDragged) {
                        // Scroll to follow the motion event
                        final int activePointerIndex = MotionEventCompat
                                .findPointerIndex(ev, this.mActivePointerId);
                        final float y = MotionEventCompat.getY(ev, activePointerIndex);
                        needsInvalidate |= this.performDrag(y);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (this.mIsBeingDragged) {
                        final VelocityTracker velocityTracker = this.mVelocityTracker;
                        velocityTracker.computeCurrentVelocity(1000,
                                this.mMaximumVelocity);
                        int initialVelocity = (int) VelocityTrackerCompat.getYVelocity(
                                velocityTracker, this.mActivePointerId);
                        this.mPopulatePending = true;
                        final int height = this.getClientHeight();
                        final int scrollY = this.getScrollY();
                        final ItemInfo ii = this.infoForCurrentScrollPosition();
                        final int currentPage = ii.position;
                        final float pageOffset = (((float) scrollY / height) - ii.offset)
                                / ii.heightFactor;
                        final int activePointerIndex = MotionEventCompat
                                .findPointerIndex(ev, this.mActivePointerId);
                        final float y = MotionEventCompat.getY(ev, activePointerIndex);
                        final int totalDelta = (int) (y - this.mInitialMotionY);
                        int nextPage = this.determineTargetPage(currentPage,
                                pageOffset, initialVelocity, totalDelta);
                        this.setCurrentItemInternal(nextPage, true, true,
                                initialVelocity);

                        this.mActivePointerId = INVALID_POINTER;
                        this.endDrag();
                        needsInvalidate = this.mTopEdge.onRelease()
                                | this.mBottomEdge.onRelease();
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    if (this.mIsBeingDragged) {
                        this.scrollToItem(this.mCurItem, true, 0, false);
                        this.mActivePointerId = INVALID_POINTER;
                        this.endDrag();
                        needsInvalidate = this.mTopEdge.onRelease()
                                | this.mBottomEdge.onRelease();
                    }
                    break;
                case MotionEventCompat.ACTION_POINTER_DOWN: {
                    final int index = MotionEventCompat.getActionIndex(ev);
                    final float y = MotionEventCompat.getY(ev, index);
                    this.mLastMotionY = y;
                    this.mActivePointerId = MotionEventCompat.getPointerId(ev, index);
                    break;
                }
                case MotionEventCompat.ACTION_POINTER_UP:
                    this.onSecondaryPointerUp(ev);
                    this.mLastMotionY = MotionEventCompat.getY(ev, MotionEventCompat
                            .findPointerIndex(ev, this.mActivePointerId));
                    break;
            }
            if (needsInvalidate) {
                ViewCompat.postInvalidateOnAnimation(this);
            }
        }catch (IllegalArgumentException ex){
            ex.printStackTrace();
        }
        return true;
    }

    private void requestParentDisallowInterceptTouchEvent(
            boolean disallowIntercept) {
        final ViewParent parent = this.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }

    private boolean performDrag(float y) {
        boolean needsInvalidate = false;

        final float deltaY = this.mLastMotionY - y;
        this.mLastMotionY = y;

        float oldScrollY = this.getScrollY();
        float scrollY = oldScrollY + deltaY;
        final int height = this.getClientHeight();

        float topBound = height * this.mFirstOffset;
        float bottomBound = height * this.mLastOffset;
        boolean topAbsolute = true;
        boolean bottomAbsolute = true;

        if(this.mItems.size()>0) {
            final ItemInfo firstItem = this.mItems.get(0);
            final ItemInfo lastItem = this.mItems.get(this.mItems.size() - 1);
            if (firstItem.position != 0) {
                topAbsolute = false;
                topBound = firstItem.offset * height;
            }
            if (lastItem.position != this.mAdapter.getCount() - 1) {
                bottomAbsolute = false;
                bottomBound = lastItem.offset * height;
            }

            if (scrollY < topBound) {
                if (topAbsolute) {
                    float over = topBound - scrollY;
                    needsInvalidate = this.mTopEdge.onPull(Math.abs(over) / height);
                }
                scrollY = topBound;
            } else if (scrollY > bottomBound) {
                if (bottomAbsolute) {
                    float over = scrollY - bottomBound;
                    needsInvalidate = this.mBottomEdge.onPull(Math.abs(over)
                            / height);
                }
                scrollY = bottomBound;
            }
            // Don't lose the rounded component
            this.mLastMotionX += scrollY - (int) scrollY;
            this.scrollTo(this.getScrollX(), (int) scrollY);
            this.pageScrolled((int) scrollY);
        }
        return needsInvalidate;
    }

    /**
     * @return Info about the page at the current scroll position. This can be
     *         synthetic for a missing middle page; the 'object' field can be
     *         null.
     */
    private ItemInfo infoForCurrentScrollPosition() {
        final int height = this.getClientHeight();
        final float scrollOffset = height > 0 ? (float) this.getScrollY()
                / height : 0;
        final float marginOffset = height > 0 ? (float) this.mPageMargin
                / height : 0;
        int lastPos = -1;
        float lastOffset = 0.f;
        float lastHeight = 0.f;
        boolean first = true;

        ItemInfo lastItem = null;
        for (int i = 0; i < this.mItems.size(); i++) {
            ItemInfo ii = this.mItems.get(i);
            float offset;
            if (!first && ii.position != lastPos + 1) {
                // Create a synthetic item for a missing page.
                ii = this.mTempItem;
                ii.offset = lastOffset + lastHeight + marginOffset;
                ii.position = lastPos + 1;
                ii.heightFactor = this.mAdapter.getPageWidth(ii.position);
                i--;
            }
            offset = ii.offset;

            final float topBound = offset;
            final float bottomBound = offset + ii.heightFactor + marginOffset;
            if (first || scrollOffset >= topBound) {
                if (scrollOffset < bottomBound || i == this.mItems.size() - 1) {
                    return ii;
                }
            } else {
                return lastItem;
            }
            first = false;
            lastPos = ii.position;
            lastOffset = offset;
            lastHeight = ii.heightFactor;
            lastItem = ii;
        }

        return lastItem;
    }

    private int determineTargetPage(int currentPage, float pageOffset,
            int velocity, int deltaY) {
        int targetPage;
        if (Math.abs(deltaY) > this.mFlingDistance
                && Math.abs(velocity) > this.mMinimumVelocity) {
            targetPage = velocity > 0 ? currentPage : currentPage + 1;
        } else {
            final float truncator = currentPage >= this.mCurItem ? 0.4f : 0.6f;
            targetPage = (int) (currentPage + pageOffset + truncator);
        }

        if (this.mItems.size() > 0) {
            final ItemInfo firstItem = this.mItems.get(0);
            final ItemInfo lastItem = this.mItems.get(this.mItems.size() - 1);

            // Only let the user target pages we have items for
            targetPage = Math.max(firstItem.position,
                    Math.min(targetPage, lastItem.position));
        }

        return targetPage;
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        boolean needsInvalidate = false;

        final int overScrollMode = ViewCompat.getOverScrollMode(this);
        if (overScrollMode == ViewCompat.OVER_SCROLL_ALWAYS
                || (overScrollMode == ViewCompat.OVER_SCROLL_IF_CONTENT_SCROLLS
                        && this.mAdapter != null && this.mAdapter.getCount() > 1)) {
            if (!this.mTopEdge.isFinished()) {
                final int restoreCount = canvas.save();
                final int height = this.getHeight();
                final int width = this.getWidth() - this.getPaddingLeft()
                        - this.getPaddingRight();

                canvas.translate(this.getPaddingLeft(), this.mFirstOffset
                        * height);
                this.mTopEdge.setSize(width, height);
                needsInvalidate |= this.mTopEdge.draw(canvas);
                canvas.restoreToCount(restoreCount);
            }
            if (!this.mBottomEdge.isFinished()) {
                final int restoreCount = canvas.save();
                final int height = this.getHeight();
                final int width = this.getWidth() - this.getPaddingLeft()
                        - this.getPaddingRight();

                canvas.rotate(180);
                canvas.translate(-width - this.getPaddingLeft(),
                        -(this.mLastOffset + 1) * height);
                this.mBottomEdge.setSize(width, height);
                needsInvalidate |= this.mBottomEdge.draw(canvas);
                canvas.restoreToCount(restoreCount);
            }
        } else {
            this.mTopEdge.finish();
            this.mBottomEdge.finish();
        }

        if (needsInvalidate) {
            // Keep animating
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw the margin drawable between pages if needed.
        if (this.mPageMargin > 0 && this.mMarginDrawable != null
                && this.mItems.size() > 0 && this.mAdapter != null) {
            final int scrollY = this.getScrollY();
            final int height = this.getHeight();

            final float marginOffset = (float) this.mPageMargin / height;
            int itemIndex = 0;
            ItemInfo ii = this.mItems.get(0);
            float offset = ii.offset;
            final int itemCount = this.mItems.size();
            final int firstPos = ii.position;
            final int lastPos = this.mItems.get(itemCount - 1).position;
            for (int pos = firstPos; pos < lastPos; pos++) {
                while (pos > ii.position && itemIndex < itemCount) {
                    ii = this.mItems.get(++itemIndex);
                }

                float drawAt;
                if (pos == ii.position) {
                    drawAt = (ii.offset + ii.heightFactor) * height;
                    offset = ii.offset + ii.heightFactor + marginOffset;
                } else {
                    float heightFactor = this.mAdapter.getPageWidth(pos);
                    drawAt = (offset + heightFactor) * height;
                    offset += heightFactor + marginOffset;
                }

                if (drawAt + this.mPageMargin > scrollY) {
                    this.mMarginDrawable.setBounds(this.mLeftPageBounds,
                            (int) drawAt, this.mRightPageBounds, (int) (drawAt
                                    + this.mPageMargin + 0.5f));
                    this.mMarginDrawable.draw(canvas);
                }

                if (drawAt > scrollY + height) {
                    break; // No more visible, no sense in continuing
                }
            }
        }
    }

    /**
     * Start a fake drag of the pager.
     * <p/>
     * <p>
     * A fake drag can be useful if you want to synchronize the motion of the
     * ViewPager with the touch scrolling of another view, while still letting
     * the ViewPager control the snapping motion and fling behavior. (e.g.
     * parallax-scrolling tabs.) Call {@link #fakeDragBy(float)} to simulate the
     * actual drag motion. Call {@link #endFakeDrag()} to complete the fake drag
     * and fling as necessary.
     * <p/>
     * <p>
     * During a fake drag the ViewPager will ignore all touch events. If a real
     * drag is already in progress, this method will return false.
     * @return true if the fake drag began successfully, false if it could not
     *         be started.
     * @see #fakeDragBy(float)
     * @see #endFakeDrag()
     */
    public boolean beginFakeDrag() {
        if (this.mIsBeingDragged) {
            return false;
        }
        this.mFakeDragging = true;
        this.setScrollState(SCROLL_STATE_DRAGGING);
        this.mInitialMotionY = this.mLastMotionY = 0;
        if (this.mVelocityTracker == null) {
            this.mVelocityTracker = VelocityTracker.obtain();
        } else {
            this.mVelocityTracker.clear();
        }
        final long time = SystemClock.uptimeMillis();
        final MotionEvent ev = MotionEvent.obtain(time, time,
                MotionEvent.ACTION_DOWN, 0, 0, 0);
        this.mVelocityTracker.addMovement(ev);
        ev.recycle();
        this.mFakeDragBeginTime = time;
        return true;
    }

    /**
     * End a fake drag of the pager.
     * @see #beginFakeDrag()
     * @see #fakeDragBy(float)
     */
    public void endFakeDrag() {
        if (!this.mFakeDragging) {
            throw new IllegalStateException(
                    "No fake drag in progress. Call beginFakeDrag first.");
        }

        final VelocityTracker velocityTracker = this.mVelocityTracker;
        velocityTracker.computeCurrentVelocity(1000, this.mMaximumVelocity);
        int initialVelocity = (int) VelocityTrackerCompat.getYVelocity(
                velocityTracker, this.mActivePointerId);
        this.mPopulatePending = true;
        final int height = this.getClientHeight();
        final int scrollY = this.getScrollY();
        final ItemInfo ii = this.infoForCurrentScrollPosition();
        final int currentPage = ii.position;
        final float pageOffset = (((float) scrollY / height) - ii.offset)
                / ii.heightFactor;
        final int totalDelta = (int) (this.mLastMotionY - this.mInitialMotionY);
        int nextPage = this.determineTargetPage(currentPage, pageOffset,
                initialVelocity, totalDelta);
        this.setCurrentItemInternal(nextPage, true, true, initialVelocity);
        this.endDrag();

        this.mFakeDragging = false;
    }

    /**
     * Fake drag by an offset in pixels. You must have called
     * {@link #beginFakeDrag()} first.
     * @param yOffset
     *            Offset in pixels to drag by.
     * @see #beginFakeDrag()
     * @see #endFakeDrag()
     */
    public void fakeDragBy(float yOffset) {
        if (!this.mFakeDragging) {
            throw new IllegalStateException(
                    "No fake drag in progress. Call beginFakeDrag first.");
        }

        this.mLastMotionY += yOffset;

        float oldScrollY = this.getScrollY();
        float scrollY = oldScrollY - yOffset;
        final int height = this.getClientHeight();

        float topBound = height * this.mFirstOffset;
        float bottomBound = height * this.mLastOffset;

        final ItemInfo firstItem = this.mItems.get(0);
        final ItemInfo lastItem = this.mItems.get(this.mItems.size() - 1);
        if (firstItem.position != 0) {
            topBound = firstItem.offset * height;
        }
        if (lastItem.position != this.mAdapter.getCount() - 1) {
            bottomBound = lastItem.offset * height;
        }

        if (scrollY < topBound) {
            scrollY = topBound;
        } else if (scrollY > bottomBound) {
            scrollY = bottomBound;
        }
        // Don't lose the rounded component
        this.mLastMotionY += scrollY - (int) scrollY;
        this.scrollTo(this.getScrollX(), (int) scrollY);
        this.pageScrolled((int) scrollY);

        // Synthesize an event for the VelocityTracker.
        final long time = SystemClock.uptimeMillis();
        final MotionEvent ev = MotionEvent.obtain(this.mFakeDragBeginTime,
                time, MotionEvent.ACTION_MOVE, 0, this.mLastMotionY, 0);
        this.mVelocityTracker.addMovement(ev);
        ev.recycle();
    }

    /**
     * Returns true if a fake drag is in progress.
     * @return true if currently in a fake drag, false otherwise.
     * @see #beginFakeDrag()
     * @see #fakeDragBy(float)
     * @see #endFakeDrag()
     */
    public boolean isFakeDragging() {
        return this.mFakeDragging;
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = MotionEventCompat.getActionIndex(ev);
        final int pointerId = MotionEventCompat.getPointerId(ev, pointerIndex);
        if (pointerId == this.mActivePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            this.mLastMotionY = MotionEventCompat.getY(ev, newPointerIndex);
            this.mActivePointerId = MotionEventCompat.getPointerId(ev,
                    newPointerIndex);
            if (this.mVelocityTracker != null) {
                this.mVelocityTracker.clear();
            }
        }
    }

    private void endDrag() {
        this.mIsBeingDragged = false;
        this.mIsUnableToDrag = false;

        if (this.mVelocityTracker != null) {
            this.mVelocityTracker.recycle();
            this.mVelocityTracker = null;
        }
    }

    private void setScrollingCacheEnabled(boolean enabled) {
        if (this.mScrollingCacheEnabled != enabled) {
            this.mScrollingCacheEnabled = enabled;
            if (USE_CACHE) {
                final int size = this.getChildCount();
                for (int i = 0; i < size; ++i) {
                    final View child = this.getChildAt(i);
                    if (child.getVisibility() != GONE) {
                        child.setDrawingCacheEnabled(enabled);
                    }
                }
            }
        }
    }

    public boolean internalCanScrollVertically(int direction) {
        if (this.mAdapter == null) {
            return false;
        }

        final int height = this.getClientHeight();
        final int scrollY = this.getScrollY();
        if (direction < 0) {
            return (scrollY > (int) (height * this.mFirstOffset));
        } else if (direction > 0) {
            return (scrollY < (int) (height * this.mLastOffset));
        } else {
            return false;
        }
    }

    /**
     * Tests scrollability within child views of v given a delta of dx.
     * @param v
     *            View to test for horizontal scrollability
     * @param checkV
     *            Whether the view v passed should itself be checked for
     *            scrollability (true), or just its children (false).
     * @param dy
     *            Delta scrolled in pixels
     * @param x
     *            X coordinate of the active touch point
     * @param y
     *            Y coordinate of the active touch point
     * @return true if child views of v can be scrolled by delta of dx.
     */
    protected boolean canScroll(View v, boolean checkV, int dy, int x, int y) {
        if (v instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) v;
            final int scrollX = v.getScrollX();
            final int scrollY = v.getScrollY();
            final int count = group.getChildCount();
            // Count backwards - let topmost views consume scroll distance
            // first.
            for (int i = count - 1; i >= 0; i--) {
                // TODO: Add versioned support here for transformed views.
                // This will not work for transformed views in Honeycomb+
                final View child = group.getChildAt(i);
                if (y + scrollY >= child.getTop()
                        && y + scrollY < child.getBottom()
                        && x + scrollX >= child.getLeft()
                        && x + scrollX < child.getRight()
                        && this.canScroll(child, true, dy,
                                x + scrollX - child.getLeft(), y + scrollY
                                        - child.getTop())) {
                    return true;
                }
            }
        }

        return checkV && ViewCompat.canScrollVertically(v, -dy);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Let the focused view and/or our descendants get the key first
        return super.dispatchKeyEvent(event) || this.executeKeyEvent(event);
    }

    /**
     * You can call this function yourself to have the scroll view perform
     * scrolling from a key event, just as if the event had been dispatched to
     * it by the view hierarchy.
     * @param event
     *            The key event to execute.
     * @return Return true if the event was handled, else false.
     */
    public boolean executeKeyEvent(KeyEvent event) {
        boolean handled = false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                handled = this.arrowScroll(FOCUS_LEFT);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                handled = this.arrowScroll(FOCUS_RIGHT);
                break;
            case KeyEvent.KEYCODE_TAB:
                if (Build.VERSION.SDK_INT >= 11) {
                    // The focus finder had a bug handling FOCUS_FORWARD and
                    // FOCUS_BACKWARD
                    // before Android 3.0. Ignore the tab key on those devices.
                    if (KeyEventCompat.hasNoModifiers(event)) {
                        handled = this.arrowScroll(FOCUS_FORWARD);
                    } else if (KeyEventCompat.hasModifiers(event,
                            KeyEvent.META_SHIFT_ON)) {
                        handled = this.arrowScroll(FOCUS_BACKWARD);
                    }
                }
                break;
            }
        }
        return handled;
    }

    public boolean arrowScroll(int direction) {
        View currentFocused = this.findFocus();
        if (currentFocused == this) {
            currentFocused = null;
        } else if (currentFocused != null) {
            boolean isChild = false;
            for (ViewParent parent = currentFocused.getParent(); parent instanceof ViewGroup; parent = parent
                    .getParent()) {
                if (parent == this) {
                    isChild = true;
                    break;
                }
            }
            if (!isChild) {
                // This would cause the focus search down below to fail in fun
                // ways.
                final StringBuilder sb = new StringBuilder();
                sb.append(currentFocused.getClass().getSimpleName());
                for (ViewParent parent = currentFocused.getParent(); parent instanceof ViewGroup; parent = parent
                        .getParent()) {
                    sb.append(" => ").append(parent.getClass().getSimpleName());
                }
                Log.e(TAG,
                        "arrowScroll tried to find focus based on non-child "
                                + "current focused view " + sb.toString());
                currentFocused = null;
            }
        }

        boolean handled = false;

        View nextFocused = FocusFinder.getInstance().findNextFocus(this,
                currentFocused, direction);
        if (nextFocused != null && nextFocused != currentFocused) {
            if (direction == View.FOCUS_UP) {
                // If there is nothing to the left, or this is causing us to
                // jump to the right, then what we really want to do is page
                // left.
                final int nextTop = this.getChildRectInPagerCoordinates(
                        this.mTempRect, nextFocused).top;
                final int currTop = this.getChildRectInPagerCoordinates(
                        this.mTempRect, currentFocused).top;
                if (currentFocused != null && nextTop >= currTop) {
                    handled = this.pageUp();
                } else {
                    handled = nextFocused.requestFocus();
                }
            } else if (direction == View.FOCUS_DOWN) {
                // If there is nothing to the right, or this is causing us to
                // jump to the left, then what we really want to do is page
                // right.
                final int nextDown = this.getChildRectInPagerCoordinates(
                        this.mTempRect, nextFocused).bottom;
                final int currDown = this.getChildRectInPagerCoordinates(
                        this.mTempRect, currentFocused).bottom;
                if (currentFocused != null && nextDown <= currDown) {
                    handled = this.pageDown();
                } else {
                    handled = nextFocused.requestFocus();
                }
            }
        } else if (direction == FOCUS_UP || direction == FOCUS_BACKWARD) {
            // Trying to move left and nothing there; try to page.
            handled = this.pageUp();
        } else if (direction == FOCUS_DOWN || direction == FOCUS_FORWARD) {
            // Trying to move right and nothing there; try to page.
            handled = this.pageDown();
        }
        if (handled) {
            this.playSoundEffect(SoundEffectConstants
                    .getContantForFocusDirection(direction));
        }
        return handled;
    }

    private Rect getChildRectInPagerCoordinates(Rect outRect, View child) {
        if (outRect == null) {
            outRect = new Rect();
        }
        if (child == null) {
            outRect.set(0, 0, 0, 0);
            return outRect;
        }
        outRect.left = child.getLeft();
        outRect.right = child.getRight();
        outRect.top = child.getTop();
        outRect.bottom = child.getBottom();

        ViewParent parent = child.getParent();
        while (parent instanceof ViewGroup && parent != this) {
            final ViewGroup group = (ViewGroup) parent;
            outRect.left += group.getLeft();
            outRect.right += group.getRight();
            outRect.top += group.getTop();
            outRect.bottom += group.getBottom();

            parent = group.getParent();
        }
        return outRect;
    }

    boolean pageUp() {
        if (this.mCurItem > 0) {
            this.setCurrentItem(this.mCurItem - 1, true);
            return true;
        }
        return false;
    }

    boolean pageDown() {
        if (this.mAdapter != null
                && this.mCurItem < (this.mAdapter.getCount() - 1)) {
            this.setCurrentItem(this.mCurItem + 1, true);
            return true;
        }
        return false;
    }

    /**
     * We only want the current page that is being shown to be focusable.
     */
    @Override
    public void addFocusables(ArrayList<View> views, int direction,
                              int focusableMode) {
        final int focusableCount = views.size();

        final int descendantFocusability = this.getDescendantFocusability();

        if (descendantFocusability != FOCUS_BLOCK_DESCENDANTS) {
            for (int i = 0; i < this.getChildCount(); i++) {
                final View child = this.getChildAt(i);
                if (child.getVisibility() == VISIBLE) {
                    ItemInfo ii = this.infoForChild(child);
                    if (ii != null && ii.position == this.mCurItem) {
                        child.addFocusables(views, direction, focusableMode);
                    }
                }
            }
        }

        // we add ourselves (if focusable) in all cases except for when we are
        // FOCUS_AFTER_DESCENDANTS and there are some descendants focusable.
        // this is
        // to avoid the focus search finding layouts when a more precise search
        // among the focusable children would be more interesting.
        if (descendantFocusability != FOCUS_AFTER_DESCENDANTS ||
        // No focusable descendants
                (focusableCount == views.size())) {
            // Note that we can't call the superclass here, because it will
            // add all views in. So we need to do the same thing View does.
            if (!this.isFocusable()) {
                return;
            }
            if ((focusableMode & FOCUSABLES_TOUCH_MODE) == FOCUSABLES_TOUCH_MODE
                    && this.isInTouchMode() && !this.isFocusableInTouchMode()) {
                return;
            }
            if (views != null) {
                views.add(this);
            }
        }
    }

    /**
     * We only want the current page that is being shown to be touchable.
     */
    @Override
    public void addTouchables(ArrayList<View> views) {
        // Note that we don't call super.addTouchables(), which means that
        // we don't call View.addTouchables(). This is okay because a ViewPager
        // is itself not touchable.
        for (int i = 0; i < this.getChildCount(); i++) {
            final View child = this.getChildAt(i);
            if (child.getVisibility() == VISIBLE) {
                ItemInfo ii = this.infoForChild(child);
                if (ii != null && ii.position == this.mCurItem) {
                    child.addTouchables(views);
                }
            }
        }
    }

    /**
     * We only want the current page that is being shown to be focusable.
     */
    @Override
    protected boolean onRequestFocusInDescendants(int direction,
            Rect previouslyFocusedRect) {
        int index;
        int increment;
        int end;
        int count = this.getChildCount();
        if ((direction & FOCUS_FORWARD) != 0) {
            index = 0;
            increment = 1;
            end = count;
        } else {
            index = count - 1;
            increment = -1;
            end = -1;
        }
        for (int i = index; i != end; i += increment) {
            View child = this.getChildAt(i);
            if (child.getVisibility() == VISIBLE) {
                ItemInfo ii = this.infoForChild(child);
                if (ii != null && ii.position == this.mCurItem) {
                    if (child.requestFocus(direction, previouslyFocusedRect)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        // Dispatch scroll events from this ViewPager.
        if (event.getEventType() == AccessibilityEventCompat.TYPE_VIEW_SCROLLED) {
            return super.dispatchPopulateAccessibilityEvent(event);
        }

        // Dispatch all other accessibility events from the current page.
        final int childCount = this.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = this.getChildAt(i);
            if (child.getVisibility() == VISIBLE) {
                final ItemInfo ii = this.infoForChild(child);
                if (ii != null && ii.position == this.mCurItem
                        && child.dispatchPopulateAccessibilityEvent(event)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams();
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(
            ViewGroup.LayoutParams p) {
        return this.generateDefaultLayoutParams();
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams && super.checkLayoutParams(p);
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(this.getContext(), attrs);
    }

    class MyAccessibilityDelegate extends AccessibilityDelegateCompat {

        @Override
        public void onInitializeAccessibilityEvent(View host,
                AccessibilityEvent event) {
            super.onInitializeAccessibilityEvent(host, event);
            event.setClassName(ViewPager.class.getName());
            final AccessibilityRecordCompat recordCompat = AccessibilityRecordCompat
                    .obtain();
            recordCompat.setScrollable(this.canScroll());
            if (event.getEventType() == AccessibilityEventCompat.TYPE_VIEW_SCROLLED
                    && VerticalViewPager.this.mAdapter != null) {
                recordCompat.setItemCount(VerticalViewPager.this.mAdapter
                        .getCount());
                recordCompat.setFromIndex(VerticalViewPager.this.mCurItem);
                recordCompat.setToIndex(VerticalViewPager.this.mCurItem);
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host,
                AccessibilityNodeInfoCompat info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(ViewPager.class.getName());
            info.setScrollable(this.canScroll());
            if (VerticalViewPager.this.internalCanScrollVertically(1)) {
                info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD);
            }
            if (VerticalViewPager.this.internalCanScrollVertically(-1)) {
                info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD);
            }
        }

        @Override
        public boolean performAccessibilityAction(View host, int action,
                                                  Bundle args) {
            if (super.performAccessibilityAction(host, action, args)) {
                return true;
            }
            switch (action) {
            case AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD: {
                if (VerticalViewPager.this.internalCanScrollVertically(1)) {
                    VerticalViewPager.this
                            .setCurrentItem(VerticalViewPager.this.mCurItem + 1);
                    return true;
                }
            }
                return false;
            case AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD: {
                if (VerticalViewPager.this.internalCanScrollVertically(-1)) {
                    VerticalViewPager.this
                            .setCurrentItem(VerticalViewPager.this.mCurItem - 1);
                    return true;
                }
            }
                return false;
            }
            return false;
        }

        private boolean canScroll() {
            return (VerticalViewPager.this.mAdapter != null)
                    && (VerticalViewPager.this.mAdapter.getCount() > 1);
        }
    }

    private class PagerObserver extends DataSetObserver {
        @Override
        public void onChanged() {
            VerticalViewPager.this.dataSetChanged();
        }

        @Override
        public void onInvalidated() {
            VerticalViewPager.this.dataSetChanged();
        }
    }

    /**
     * Layout parameters that should be supplied for views added to a ViewPager.
     */
    public static class LayoutParams extends ViewGroup.LayoutParams {
        /**
         * true if this view is a decoration on the pager itself and not a view
         * supplied by the adapter.
         */
        public boolean isDecor;

        /**
         * Gravity setting for use on decor views only: Where to position the
         * view page within the overall ViewPager container; constants are
         * defined in {@link android.view.Gravity}.
         */
        public int gravity;

        /**
         * Width as a 0-1 multiplier of the measured pager width
         */
        float heightFactor = 0.f;

        /**
         * true if this view was added during layout and needs to be measured
         * before being positioned.
         */
        boolean needsMeasure;

        /**
         * Adapter position this view is for if !isDecor
         */
        int position;

        /**
         * Current child index within the ViewPager that this view occupies
         */
        int childIndex;

        public LayoutParams() {
            super(FILL_PARENT, FILL_PARENT);
        }

        public LayoutParams(Context context, AttributeSet attrs) {
            super(context, attrs);

            final TypedArray a = context.obtainStyledAttributes(attrs,
                    LAYOUT_ATTRS);
            this.gravity = a.getInteger(0, Gravity.TOP);
            a.recycle();
        }
    }

    static class ViewPositionComparator implements Comparator<View> {
        @Override
        public int compare(View lhs, View rhs) {
            final LayoutParams llp = (LayoutParams) lhs.getLayoutParams();
            final LayoutParams rlp = (LayoutParams) rhs.getLayoutParams();
            if (llp.isDecor != rlp.isDecor) {
                return llp.isDecor ? 1 : -1;
            }
            return llp.position - rlp.position;
        }
    }
}
