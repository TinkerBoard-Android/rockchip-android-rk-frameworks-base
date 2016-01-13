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

package com.android.settingslib;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.view.MenuItem;
import android.widget.TextView;

import java.util.List;

/**
 * Utility class to host methods usable in adding a restricted padlock icon and showing admin
 * support message dialog.
 */
public class RestrictedLockUtils {
    /**
     * @return drawables for displaying with settings that are locked by a device admin.
     */
    public static Drawable getRestrictedPadlock(Context context) {
        Drawable restrictedPadlock = context.getDrawable(R.drawable.ic_settings_lock_outline);
        final int iconSize = context.getResources().getDimensionPixelSize(
                R.dimen.restricted_lock_icon_size);
        restrictedPadlock.setBounds(0, 0, iconSize, iconSize);
        return restrictedPadlock;
    }

    /**
     * Checks if a restriction is enforced on a user and returns the enforced admin and
     * admin userId.
     *
     * @param userRestriction Restriction to check
     * @param userId User which we need to check if restriction is enforced on.
     * @return EnforcedAdmin Object containing the enforce admin and admin user details, or
     * {@code null} If the restriction is not set. If the restriction is set by both device owner
     * and profile owner, then the admin will be set to {@code null} and userId to
     * {@link UserHandle#USER_NULL}.
     */
    public static EnforcedAdmin checkIfRestrictionEnforced(Context context,
            String userRestriction, int userId) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName deviceOwner = dpm.getDeviceOwnerComponentOnAnyUser();
        int deviceOwnerUserId = dpm.getDeviceOwnerUserId();
        boolean enforcedByDeviceOwner = false;
        if (deviceOwner != null && deviceOwnerUserId != UserHandle.USER_NULL) {
            Bundle enforcedRestrictions = dpm.getUserRestrictions(deviceOwner, deviceOwnerUserId);
            if (enforcedRestrictions != null
                    && enforcedRestrictions.getBoolean(userRestriction, false)) {
                enforcedByDeviceOwner = true;
            }
        }

        ComponentName profileOwner = null;
        boolean enforcedByProfileOwner = false;
        if (userId != UserHandle.USER_NULL) {
            profileOwner = dpm.getProfileOwnerAsUser(userId);
            if (profileOwner != null) {
                Bundle enforcedRestrictions = dpm.getUserRestrictions(profileOwner, userId);
                if (enforcedRestrictions != null
                        && enforcedRestrictions.getBoolean(userRestriction, false)) {
                    enforcedByProfileOwner = true;
                }
            }
        }

        if (!enforcedByDeviceOwner && !enforcedByProfileOwner) {
            return null;
        }

        EnforcedAdmin admin = null;
        if (enforcedByDeviceOwner && enforcedByProfileOwner) {
            admin = new EnforcedAdmin();
        } else if (enforcedByDeviceOwner) {
            admin = new EnforcedAdmin(deviceOwner, deviceOwnerUserId);
        } else {
            admin = new EnforcedAdmin(profileOwner, userId);
        }
        return admin;
    }

    /**
     * Checks if lock screen notification features are disabled by policy. This should be
     * only used for keyguard notification features but not the keyguard features
     * (e.g. KEYGUARD_DISABLE_FINGERPRINT) where a profile owner can set them on the parent user
     * as it won't work for that case.
     *
     * @param keyguardNotificationFeatures Could be any of notification features that can be
     * disabled by {@link android.app.admin.DevicePolicyManager#setKeyguardDisabledFeatures}.
     * @return EnforcedAdmin Object containing the enforce admin and admin user details, or
     * {@code null} If the notification features are not disabled. If the restriction is set by
     * multiple admins, then the admin will be set to {@code null} and userId to
     * {@link UserHandle#USER_NULL}.
     */
    public static EnforcedAdmin checkIfKeyguardNotificationFeaturesDisabled(Context context,
            int keyguardNotificationFeatures) {
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        boolean isDisabledByMultipleAdmins = false;
        ComponentName adminComponent = null;
        List<ComponentName> admins = dpm.getActiveAdmins();
        int disabledKeyguardFeatures;
        for (ComponentName admin : admins) {
            disabledKeyguardFeatures = dpm.getKeyguardDisabledFeatures(admin);
            if ((disabledKeyguardFeatures & keyguardNotificationFeatures) != 0) {
                if (adminComponent == null) {
                    adminComponent = admin;
                } else {
                    isDisabledByMultipleAdmins = true;
                    break;
                }
            }
        }
        EnforcedAdmin enforcedAdmin = null;
        if (adminComponent != null) {
            if (!isDisabledByMultipleAdmins) {
                enforcedAdmin = new EnforcedAdmin(adminComponent, UserHandle.myUserId());
            } else {
                enforcedAdmin = new EnforcedAdmin();
            }
        }
        return enforcedAdmin;
    }

    /**
     * Set the menu item as disabled by admin by adding a restricted padlock at the end of the
     * text and set the click listener which will send an intent to show the admin support details
     * dialog.
     */
    public static void setMenuItemAsDisabledByAdmin(final Context context,
            final MenuItem item, final EnforcedAdmin admin) {
        SpannableStringBuilder sb = new SpannableStringBuilder(item.getTitle());
        removeExistingRestrictedSpans(sb);

        final int disabledColor = context.getColor(R.color.disabled_text_color);
        sb.setSpan(new ForegroundColorSpan(disabledColor), 0, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ImageSpan image = new RestrictedLockImageSpan(context);
        sb.append(" ", image, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        item.setTitle(sb);

        item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                sendShowAdminSupportDetailsIntent(context, admin);
                return true;
            }
        });
    }

    private static void removeExistingRestrictedSpans(SpannableStringBuilder sb) {
        final int length = sb.length();
        RestrictedLockImageSpan[] imageSpans = sb.getSpans(length - 1, length,
                RestrictedLockImageSpan.class);
        for (ImageSpan span : imageSpans) {
            sb.removeSpan(span);
        }
        ForegroundColorSpan[] colorSpans = sb.getSpans(0, length, ForegroundColorSpan.class);
        for (ForegroundColorSpan span : colorSpans) {
            sb.removeSpan(span);
        }
    }

    /**
     * Send the intent to trigger the {@link android.settings.ShowAdminSupportDetailsDialog}.
     */
    public static void sendShowAdminSupportDetailsIntent(Context context, EnforcedAdmin admin) {
        Intent intent = new Intent(Settings.ACTION_SHOW_ADMIN_SUPPORT_DETAILS);
        int adminUserId = UserHandle.myUserId();
        if (admin != null) {
            if (admin.component != null) {
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin.component);
            }
            if (admin.userId != UserHandle.USER_NULL) {
                adminUserId = admin.userId;
            }
            intent.putExtra(Intent.EXTRA_USER_ID, adminUserId);
        }
        context.startActivityAsUser(intent, new UserHandle(adminUserId));
    }

    public static void setTextViewPadlock(Context context,
            TextView textView, boolean showPadlock) {
        final SpannableStringBuilder sb = new SpannableStringBuilder(textView.getText());
        removeExistingRestrictedSpans(sb);
        if (showPadlock) {
            final ImageSpan image = new RestrictedLockImageSpan(context);
            sb.append(" ", image, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        textView.setText(sb);
    }

    public static class EnforcedAdmin {
        public ComponentName component = null;
        public int userId = UserHandle.USER_NULL;

        public EnforcedAdmin(ComponentName component, int userId) {
            this.component = component;
            this.userId = userId;
        }

        public EnforcedAdmin() {}
    }
}