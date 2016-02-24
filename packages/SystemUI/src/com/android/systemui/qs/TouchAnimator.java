/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.animation.Keyframe;
import android.util.Log;
import android.util.MathUtils;
import android.util.Property;
import android.view.View;
import android.view.animation.Interpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class, that handles similar properties as animators (delay, interpolators)
 * but can have a float input as to the amount they should be in effect.  This allows
 * easier animation that tracks input.
 *
 * All "delays" and "times" are as fractions from 0-1.
 */
public class TouchAnimator {

    private final Object[] mTargets;
    private final Property[] mProperties;
    private final KeyframeSet[] mKeyframeSets;
    private final float mStartDelay;
    private final float mEndDelay;
    private final float mSpan;
    private final Interpolator mInterpolator;
    private final Listener mListener;
    private float mLastT;

    private TouchAnimator(Object[] targets, Property[] properties, KeyframeSet[] keyframeSets,
            float startDelay, float endDelay, Interpolator interpolator, Listener listener) {
        mTargets = targets;
        mProperties = properties;
        mKeyframeSets = keyframeSets;
        mStartDelay = startDelay;
        mEndDelay = endDelay;
        mSpan = (1 - mEndDelay - mStartDelay);
        mInterpolator = interpolator;
        mListener = listener;
    }

    public void setPosition(float fraction) {
        float t = MathUtils.constrain((fraction - mStartDelay) / mSpan, 0, 1);
        if (mInterpolator != null) {
            t = mInterpolator.getInterpolation(t);
        }
        if (mListener != null) {
            if (mLastT == 0 || mLastT == 1) {
                if (t != 0) {
                    mListener.onAnimationStarted();
                }
            } else if (t == 1) {
                mListener.onAnimationAtEnd();
            } else if (t == 0) {
                mListener.onAnimationAtStart();
            }
            mLastT = t;
        }
        for (int i = 0; i < mTargets.length; i++) {
            Object value = mKeyframeSets[i].getValue(t);
            mProperties[i].set(mTargets[i], value);
        }
    }

    public static class ListenerAdapter implements Listener {
        @Override
        public void onAnimationAtStart() { }

        @Override
        public void onAnimationAtEnd() { }

        @Override
        public void onAnimationStarted() { }
    }

    public interface Listener {
        /**
         * Called when the animator moves into a position of "0". Start and end delays are
         * taken into account, so this position may cover a range of fractional inputs.
         */
        void onAnimationAtStart();

        /**
         * Called when the animator moves into a position of "0". Start and end delays are
         * taken into account, so this position may cover a range of fractional inputs.
         */
        void onAnimationAtEnd();

        /**
         * Called when the animator moves out of the start or end position and is in a transient
         * state.
         */
        void onAnimationStarted();
    }

    public static class Builder {
        private List<Object> mTargets = new ArrayList<>();
        private List<Property> mProperties = new ArrayList<>();
        private List<KeyframeSet> mValues = new ArrayList<>();

        private float mStartDelay;
        private float mEndDelay;
        private Interpolator mInterpolator;
        private Listener mListener;

        public Builder addFloat(Object target, String property, float... values) {
            add(target, property, KeyframeSet.ofFloat(values));
            return this;
        }

        public Builder addInt(Object target, String property, int... values) {
            add(target, property, KeyframeSet.ofInt(values));
            return this;
        }

        private void add(Object target, String property, KeyframeSet keyframeSet) {
            mTargets.add(target);
            mProperties.add(getProperty(target, property));
            mValues.add(keyframeSet);
        }

        private static Property getProperty(Object target, String property) {
            if (target instanceof View) {
                switch (property) {
                    case "translationX":
                        return View.TRANSLATION_X;
                    case "translationY":
                        return View.TRANSLATION_Y;
                    case "translationZ":
                        return View.TRANSLATION_Z;
                    case "alpha":
                        return View.ALPHA;
                    case "rotation":
                        return View.ROTATION;
                    case "x":
                        return View.X;
                    case "y":
                        return View.Y;
                    case "scaleX":
                        return View.SCALE_X;
                    case "scaleY":
                        return View.SCALE_Y;
                }
            }
            return Property.of(target.getClass(), float.class, property);
        }

        public Builder setStartDelay(float startDelay) {
            mStartDelay = startDelay;
            return this;
        }

        public Builder setEndDelay(float endDelay) {
            mEndDelay = endDelay;
            return this;
        }

        public Builder setInterpolator(Interpolator intepolator) {
            mInterpolator = intepolator;
            return this;
        }

        public Builder setListener(Listener listener) {
            mListener = listener;
            return this;
        }

        public TouchAnimator build() {
            return new TouchAnimator(mTargets.toArray(new Object[mTargets.size()]),
                    mProperties.toArray(new Property[mProperties.size()]),
                    mValues.toArray(new KeyframeSet[mValues.size()]),
                    mStartDelay, mEndDelay, mInterpolator, mListener);
        }
    }

    private static abstract class KeyframeSet {

        private final float mFrameWidth;
        private final int mSize;

        public KeyframeSet(int size) {
            mSize = size;
            mFrameWidth = 1 / (float) (size - 1);
        }

        Object getValue(float fraction) {
            int i;
            for (i = 1; i < mSize - 1 && fraction > mFrameWidth; i++);
            float amount = fraction / mFrameWidth;
            return interpolate(i, amount);
        }

        protected abstract Object interpolate(int index, float amount);

        public static KeyframeSet ofInt(int... values) {
            return new IntKeyframeSet(values);
        }

        public static KeyframeSet ofFloat(float... values) {
            return new FloatKeyframeSet(values);
        }
    }

    private static class FloatKeyframeSet extends KeyframeSet {
        private final float[] mValues;

        public FloatKeyframeSet(float[] values) {
            super(values.length);
            mValues = values;
        }

        @Override
        protected Object interpolate(int index, float amount) {
            float firstFloat = mValues[index - 1];
            float secondFloat = mValues[index];
            return firstFloat + (secondFloat - firstFloat) * amount;
        }
    }

    private static class IntKeyframeSet extends KeyframeSet {

        private final int[] mValues;

        public IntKeyframeSet(int[] values) {
            super(values.length);
            mValues = values;
        }

        @Override
        protected Object interpolate(int index, float amount) {
            int firstFloat = mValues[index - 1];
            int secondFloat = mValues[index];
            return (int) (firstFloat + (secondFloat - firstFloat) * amount);
        }
    }
}