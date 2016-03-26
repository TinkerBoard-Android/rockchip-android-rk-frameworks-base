/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.recents.tv.animations;


import android.animation.Animator;
import android.content.res.Resources;
import android.support.v4.view.animation.FastOutSlowInInterpolator;
import android.view.View;
import android.widget.LinearLayout;
import com.android.systemui.recents.tv.views.TaskCardView;

import com.android.systemui.R;

public class DismissAnimationsHolder {
    private LinearLayout mDismissArea;
    private LinearLayout mTaskCardView;
    private FastOutSlowInInterpolator mFastOutSlowIn;
    private int mCardYDelta;
    private long mShortDuration;
    private long mLongDuration;

    public DismissAnimationsHolder(TaskCardView taskCardView) {
        mTaskCardView = (LinearLayout) taskCardView.findViewById(R.id.recents_tv_card);
        mDismissArea = (LinearLayout) taskCardView.findViewById(R.id.card_dismiss);
        mFastOutSlowIn = new FastOutSlowInInterpolator();

        Resources res = taskCardView.getResources();
        mCardYDelta = res.getDimensionPixelOffset(R.dimen.recents_tv_dismiss_shift_down);
        mShortDuration =  res.getInteger(R.integer.dismiss_short_duration);
        mLongDuration =  res.getInteger(R.integer.dismiss_long_duration);
    }

    public void startEnterAnimation() {
        mDismissArea.animate().setDuration(mShortDuration);
        mDismissArea.animate().setInterpolator(mFastOutSlowIn);
        mDismissArea.animate().alpha(1.0f);

        mTaskCardView.animate().setDuration(mShortDuration);
        mTaskCardView.animate().setInterpolator(mFastOutSlowIn);
        mTaskCardView.animate().translationYBy(mCardYDelta);
        mTaskCardView.animate().alpha(0.5f);
    }

    public void startExitAnimation() {
        mDismissArea.animate().setDuration(mShortDuration);
        mDismissArea.animate().setInterpolator(mFastOutSlowIn);
        mDismissArea.animate().alpha(0.0f);

        mTaskCardView.animate().setDuration(mShortDuration);
        mTaskCardView.animate().setInterpolator(mFastOutSlowIn);
        mTaskCardView.animate().translationYBy(-mCardYDelta);
        mTaskCardView.animate().alpha(1.0f);
    }

    public void startDismissAnimation(Animator.AnimatorListener listener) {
        mDismissArea.animate().setDuration(mShortDuration);
        mDismissArea.animate().setInterpolator(mFastOutSlowIn);
        mDismissArea.animate().alpha(0.0f);

        mTaskCardView.animate().setDuration(mLongDuration);
        mTaskCardView.animate().setInterpolator(mFastOutSlowIn);
        mTaskCardView.animate().translationYBy(mCardYDelta);
        mTaskCardView.animate().alpha(0.0f);
        mTaskCardView.animate().setListener(listener);
    }
}