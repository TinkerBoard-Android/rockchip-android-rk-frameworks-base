/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.RecentsComponent;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.BaseStatusBar;

import static android.view.WindowManager.*;

/**
 * Class to detect gestures on the navigation bar.
 */
public class NavigationBarGestureHelper extends GestureDetector.SimpleOnGestureListener {

    private static final String DOCK_WINDOW_GESTURE_ENABLED_PROP = "persist.dock_gesture_enabled";

    /**
     * When dragging from the navigation bar, we drag in recents.
     */
    private static final int DRAG_MODE_RECENTS = 0;

    /**
     * When dragging from the navigation bar, we drag the divider.
     */
    private static final int DRAG_MODE_DIVIDER = 1;

    private RecentsComponent mRecentsComponent;
    private Divider mDivider;
    private boolean mIsVertical;
    private boolean mIsRTL;

    private final GestureDetector mTaskSwitcherDetector;
    private final int mScrollTouchSlop;
    private final int mTouchSlop;
    private final int mMinFlingVelocity;
    private int mTouchDownX;
    private int mTouchDownY;
    private VelocityTracker mVelocityTracker;

    private boolean mDockWindowEnabled;
    private boolean mDockWindowTouchSlopExceeded;
    private int mDragMode;

    public NavigationBarGestureHelper(Context context) {
        ViewConfiguration configuration = ViewConfiguration.get(context);
        Resources r = context.getResources();
        mScrollTouchSlop = r.getDimensionPixelSize(R.dimen.navigation_bar_min_swipe_distance);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mMinFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        mTaskSwitcherDetector = new GestureDetector(context, this);
        mDockWindowEnabled = SystemProperties.getBoolean(DOCK_WINDOW_GESTURE_ENABLED_PROP, false);
    }

    public void setComponents(RecentsComponent recentsComponent, Divider divider) {
        mRecentsComponent = recentsComponent;
        mDivider = divider;
    }

    public void setBarState(boolean isVertical, boolean isRTL) {
        mIsVertical = isVertical;
        mIsRTL = isRTL;
    }

    public boolean onInterceptTouchEvent(MotionEvent event) {
        // If we move more than a fixed amount, then start capturing for the
        // task switcher detector
        mTaskSwitcherDetector.onTouchEvent(event);
        int action = event.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                mTouchDownX = (int) event.getX();
                mTouchDownY = (int) event.getY();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int x = (int) event.getX();
                int y = (int) event.getY();
                int xDiff = Math.abs(x - mTouchDownX);
                int yDiff = Math.abs(y - mTouchDownY);
                boolean exceededTouchSlop = !mIsVertical
                        ? xDiff > mScrollTouchSlop && xDiff > yDiff
                        : yDiff > mScrollTouchSlop && yDiff > xDiff;
                if (exceededTouchSlop) {
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                break;
        }
        return mDockWindowEnabled && interceptDockWindowEvent(event);
    }

    private boolean interceptDockWindowEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handleDragActionDownEvent(event);
                break;
            case MotionEvent.ACTION_MOVE:
                return handleDragActionMoveEvent(event);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handleDragActionUpEvent(event);
                break;
        }
        return false;
    }

    private boolean handleDockWindowEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handleDragActionDownEvent(event);
                break;
            case MotionEvent.ACTION_MOVE:
                handleDragActionMoveEvent(event);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handleDragActionUpEvent(event);
                break;
        }
        return true;
    }

    private void handleDragActionDownEvent(MotionEvent event) {
        mVelocityTracker = VelocityTracker.obtain();
        mVelocityTracker.addMovement(event);
        mDockWindowTouchSlopExceeded = false;
        mTouchDownX = (int) event.getX();
        mTouchDownY = (int) event.getY();
    }

    private boolean handleDragActionMoveEvent(MotionEvent event) {
        mVelocityTracker.addMovement(event);
        int x = (int) event.getX();
        int y = (int) event.getY();
        int xDiff = Math.abs(x - mTouchDownX);
        int yDiff = Math.abs(y - mTouchDownY);
        if (!mDockWindowTouchSlopExceeded) {
            boolean touchSlopExceeded = !mIsVertical
                    ? yDiff > mTouchSlop && yDiff > xDiff
                    : xDiff > mTouchSlop && xDiff > yDiff;
            if (touchSlopExceeded && mDivider.getView().getWindowManagerProxy().getDockSide()
                    == DOCKED_INVALID) {
                mDragMode = calculateDragMode();
                Rect initialBounds = null;
                if (mDragMode == DRAG_MODE_DIVIDER) {
                    initialBounds = new Rect();
                    mDivider.getView().calculateBoundsForPosition(mIsVertical
                                    ? (int) event.getRawX()
                                    : (int) event.getRawY(),
                            mDivider.getView().isHorizontalDivision()
                                    ? DOCKED_TOP
                                    : DOCKED_LEFT,
                            initialBounds);
                }
                mRecentsComponent.dockTopTask(mDragMode == DRAG_MODE_RECENTS, initialBounds);
                if (mDragMode == DRAG_MODE_DIVIDER) {
                    mDivider.getView().startDragging();
                }
                mDockWindowTouchSlopExceeded = true;
                return true;
            }
        } else {
            if (mDragMode == DRAG_MODE_DIVIDER) {
                mDivider.getView().resizeStack(
                        !mIsVertical ? (int) event.getRawY() : (int) event.getRawX());
            } else if (mDragMode == DRAG_MODE_RECENTS) {
                mRecentsComponent.onDraggingInRecents(event.getRawY());
            }
        }
        return false;
    }

    private void handleDragActionUpEvent(MotionEvent event) {
        mVelocityTracker.addMovement(event);
        mVelocityTracker.computeCurrentVelocity(1000);
        if (mDockWindowTouchSlopExceeded) {
            if (mDragMode == DRAG_MODE_DIVIDER) {
                mDivider.getView().stopDragging(mIsVertical
                                ? (int) event.getRawX()
                                : (int) event.getRawY(),
                        mIsVertical
                                ? mVelocityTracker.getXVelocity()
                                : mVelocityTracker.getYVelocity());
            } else if (mDragMode == DRAG_MODE_RECENTS) {
                mRecentsComponent.onDraggingInRecentsEnded(mVelocityTracker.getYVelocity());
            }
        }
        mVelocityTracker.recycle();
        mVelocityTracker = null;
    }

    private int calculateDragMode() {
        if (mIsVertical && !mDivider.getView().isHorizontalDivision()) {
            return DRAG_MODE_DIVIDER;
        }
        if (!mIsVertical && mDivider.getView().isHorizontalDivision()) {
            return DRAG_MODE_DIVIDER;
        }
        return DRAG_MODE_RECENTS;
    }

    public boolean onTouchEvent(MotionEvent event) {
        boolean result = mTaskSwitcherDetector.onTouchEvent(event);
        if (mDockWindowEnabled) {
            result |= handleDockWindowEvent(event);
        }
        return result;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        float absVelX = Math.abs(velocityX);
        float absVelY = Math.abs(velocityY);
        boolean isValidFling = absVelX > mMinFlingVelocity &&
                mIsVertical ? (absVelY > absVelX) : (absVelX > absVelY);
        if (isValidFling) {
            boolean showNext;
            if (!mIsRTL) {
                showNext = mIsVertical ? (velocityY < 0) : (velocityX < 0);
            } else {
                // In RTL, vertical is still the same, but horizontal is flipped
                showNext = mIsVertical ? (velocityY < 0) : (velocityX > 0);
            }
            if (showNext) {
                mRecentsComponent.showNextAffiliatedTask();
            } else {
                mRecentsComponent.showPrevAffiliatedTask();
            }
        }
        return true;
    }
}