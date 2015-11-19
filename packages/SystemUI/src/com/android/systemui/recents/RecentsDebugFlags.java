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

package com.android.systemui.recents;

import android.content.Context;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.DebugFlagsChangedEvent;
import com.android.systemui.tuner.TunerService;

/**
 * Tunable debug flags
 */
public class RecentsDebugFlags implements TunerService.Tunable {

    private static final String KEY_FAST_TOGGLE = "overview_fast_toggle";

    public static class Static {
        // Enables debug drawing for the transition thumbnail
        public static final boolean EnableTransitionThumbnailDebugMode = false;
        // This disables the search bar integration
        public static final boolean DisableSearchBar = true;
        // This disables the bitmap and icon caches
        public static final boolean DisableBackgroundCache = false;
        // Enables the simulated task affiliations
        public static final boolean EnableSimulatedTaskGroups = false;
        // Defines the number of mock task affiliations per group
        public static final int TaskAffiliationsGroupCount = 12;
        // Enables us to create mock recents tasks
        public static final boolean EnableSystemServicesProxy = false;
        // Defines the number of mock recents packages to create
        public static final int SystemServicesProxyMockPackageCount = 3;
        // Defines the number of mock recents tasks to create
        public static final int SystemServicesProxyMockTaskCount = 100;
    }

    private boolean mForceEnableFreeformWorkspace;
    private boolean mEnableFastToggleRecents;

    /**
     * We read the prefs once when we start the activity, then update them as the tuner changes
     * the flags.
     */
    public RecentsDebugFlags(Context context) {
        // Register all our flags, this will also call onTuningChanged() for each key, which will
        // initialize the current state of each flag
        TunerService.get(context).addTunable(this, KEY_FAST_TOGGLE);
    }

    /**
     * @return whether we are enabling fast toggling.
     */
    public boolean isFastToggleRecentsEnabled() {
        return mEnableFastToggleRecents;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case KEY_FAST_TOGGLE:
                mEnableFastToggleRecents = (newValue != null) &&
                        (Integer.parseInt(newValue) != 0);
                break;
        }
        EventBus.getDefault().send(new DebugFlagsChangedEvent());
    }
}