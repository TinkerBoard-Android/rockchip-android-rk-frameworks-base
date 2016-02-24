/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.tuner;

import android.annotation.Nullable;
import android.app.UiModeManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.support.v14.preference.PreferenceFragment;
import android.support.v14.preference.SwitchPreference;
import android.support.v7.preference.DropDownPreference;
import android.support.v7.preference.Preference;
import android.support.v7.preference.Preference.OnPreferenceChangeListener;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.policy.NightModeController;
import com.android.systemui.statusbar.policy.NightModeController.Listener;
import com.android.systemui.tuner.TunerService.Tunable;

public class NightModeFragment extends PreferenceFragment implements Tunable,
        Listener, OnPreferenceChangeListener {

    private static final String TAG = "NightModeFragment";

    public static final String EXTRA_SHOW_NIGHT_MODE = "show_night_mode";

    private static final CharSequence KEY_AUTO = "auto";
    private static final CharSequence KEY_DARK_THEME = "dark_theme";
    private static final CharSequence KEY_ADJUST_TINT = "adjust_tint";
    private static final CharSequence KEY_ADJUST_BRIGHTNESS = "adjust_brightness";

    private Switch mSwitch;

    private NightModeController mNightModeController;
    private SwitchPreference mAutoSwitch;
    private SwitchPreference mDarkTheme;
    private SwitchPreference mAdjustTint;
    private SwitchPreference mAdjustBrightness;
    private UiModeManager mUiModeManager;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mNightModeController = new NightModeController(getContext());
        mUiModeManager = getContext().getSystemService(UiModeManager.class);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View view = LayoutInflater.from(getContext()).inflate(
                R.layout.night_mode_settings, container, false);
        ((ViewGroup) view).addView(super.onCreateView(inflater, container, savedInstanceState));
        return view;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        final Context context = getPreferenceManager().getContext();

        addPreferencesFromResource(R.xml.night_mode);
        mAutoSwitch = (SwitchPreference) findPreference(KEY_AUTO);
        mAutoSwitch.setOnPreferenceChangeListener(this);
        mDarkTheme = (SwitchPreference) findPreference(KEY_DARK_THEME);
        mDarkTheme.setOnPreferenceChangeListener(this);
        mAdjustTint = (SwitchPreference) findPreference(KEY_ADJUST_TINT);
        mAdjustTint.setOnPreferenceChangeListener(this);
        mAdjustBrightness = (SwitchPreference) findPreference(KEY_ADJUST_BRIGHTNESS);
        mAdjustBrightness.setOnPreferenceChangeListener(this);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        View switchBar = view.findViewById(R.id.switch_bar);
        mSwitch = (Switch) switchBar.findViewById(android.R.id.switch_widget);
        mSwitch.setChecked(mNightModeController.isEnabled());
        switchBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean newState = !mNightModeController.isEnabled();
                mNightModeController.setNightMode(newState);
                mSwitch.setChecked(newState);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        mNightModeController.addListener(this);
        TunerService.get(getContext()).addTunable(this, Secure.BRIGHTNESS_USE_TWILIGHT,
                NightModeController.NIGHT_MODE_ADJUST_TINT);
        mDarkTheme.setChecked(mUiModeManager.getNightMode() == UiModeManager.MODE_NIGHT_AUTO);
        calculateDisabled();
    }

    @Override
    public void onPause() {
        super.onPause();
        mNightModeController.removeListener(this);
        TunerService.get(getContext()).removeTunable(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (mAutoSwitch == preference) {
            mNightModeController.setAuto((Boolean) newValue);
        } else if (mDarkTheme == preference) {
            mUiModeManager.setNightMode(((Boolean) newValue) ? UiModeManager.MODE_NIGHT_AUTO
                    : UiModeManager.MODE_NIGHT_NO);
            postCalculateDisabled();
        } else if (mAdjustTint == preference) {
            mNightModeController.setAdjustTint((Boolean) newValue);
            postCalculateDisabled();
        } else if (mAdjustBrightness == preference) {
            TunerService.get(getContext()).setValue(Secure.BRIGHTNESS_USE_TWILIGHT,
                    ((Boolean) newValue) ? 1 : 0);
            postCalculateDisabled();
        } else {
            return false;
        }
        return true;
    }

    private void postCalculateDisabled() {
        // Post this because its the easiest way to wait for all state to be calculated.
        getView().post(new Runnable() {
            @Override
            public void run() {
                calculateDisabled();
            }
        });
    }

    private void calculateDisabled() {
        int enabledCount = (mDarkTheme.isChecked() ? 1 : 0)
                + (mAdjustTint.isChecked() ? 1 : 0)
                + (mAdjustBrightness.isChecked() ? 1 : 0);
        if (enabledCount == 1) {
            if (mDarkTheme.isChecked()) {
                mDarkTheme.setEnabled(false);
            } else if (mAdjustTint.isChecked()) {
                mAdjustTint.setEnabled(false);
            } else {
                mAdjustBrightness.setEnabled(false);
            }
        } else {
            mDarkTheme.setEnabled(true);
            mAdjustTint.setEnabled(true);
            mAdjustBrightness.setEnabled(true);
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (Secure.BRIGHTNESS_USE_TWILIGHT.equals(key)) {
            mAdjustBrightness.setChecked(newValue != null && Integer.parseInt(newValue) != 0);
        } else if (NightModeController.NIGHT_MODE_ADJUST_TINT.equals(key)) {
            // Default on.
            mAdjustTint.setChecked(newValue == null || Integer.parseInt(newValue) != 0);
        }
    }

    @Override
    public void onNightModeChanged() {
        mSwitch.setChecked(mNightModeController.isEnabled());
    }

    @Override
    public void onTwilightAutoChanged() {
        mAutoSwitch.setChecked(mNightModeController.isAuto());
    }
}