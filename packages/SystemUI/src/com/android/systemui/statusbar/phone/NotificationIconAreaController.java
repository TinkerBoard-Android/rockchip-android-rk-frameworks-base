package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.android.internal.util.NotificationColorUtil;
import com.android.systemui.R;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.NotificationUtils;

import java.util.ArrayList;

/**
 * A controller for the space in the status bar to the left of the system icons. This area is
 * normally reserved for notifications.
 */
public class NotificationIconAreaController {
    private final NotificationColorUtil mNotificationColorUtil;

    private int mIconSize;
    private int mIconHPadding;
    private int mIconTint = Color.WHITE;

    private PhoneStatusBar mPhoneStatusBar;
    protected View mNotificationIconArea;
    private IconMerger mNotificationIcons;
    private ImageView mMoreIcon;

    public NotificationIconAreaController(Context context, PhoneStatusBar phoneStatusBar) {
        mPhoneStatusBar = phoneStatusBar;
        mNotificationColorUtil = NotificationColorUtil.getInstance(context);

        initializeNotificationAreaViews(context);
    }

    /**
     * Initializes the views that will represent the notification area.
     */
    protected void initializeNotificationAreaViews(Context context) {
        Resources res = context.getResources();
        mIconSize = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_icon_size);
        mIconHPadding = res.getDimensionPixelSize(R.dimen.status_bar_icon_padding);

        LayoutInflater layoutInflater = LayoutInflater.from(context);
        mNotificationIconArea = layoutInflater.inflate(R.layout.notification_icon_area, null);

        mMoreIcon = (ImageView) mNotificationIconArea.findViewById(R.id.moreIcon);
        mMoreIcon.setImageTintList(ColorStateList.valueOf(mIconTint));

        mNotificationIcons =
                (IconMerger) mNotificationIconArea.findViewById(R.id.notificationIcons);
        mNotificationIcons.setOverflowIndicator(mMoreIcon);
    }

    /**
     * Returns the view that represents the notification area.
     */
    public View getNotificationInnerAreaView() {
        return mNotificationIconArea;
    }

    /**
     * Sets the color that should be used to tint any icons in the notification area. If this
     * method is not called, the default tint is {@link Color#WHITE}.
     */
    public void setIconTint(int iconTint) {
        mIconTint = iconTint;
        mMoreIcon.setImageTintList(ColorStateList.valueOf(mIconTint));
        applyNotificationIconsTint();
    }

    /**
     * Updates the notifications with the given list of notifications to display.
     */
    public void updateNotificationIcons(NotificationData notificationData) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                mIconSize + 2 * mIconHPadding, mPhoneStatusBar.getStatusBarHeight());

        ArrayList<NotificationData.Entry> activeNotifications =
                notificationData.getActiveNotifications();
        final int size = activeNotifications.size();
        ArrayList<StatusBarIconView> toShow = new ArrayList<>(size);

        // Filter out ambient notifications and notification children.
        for (int i = 0; i < size; i++) {
            NotificationData.Entry ent = activeNotifications.get(i);
            if (notificationData.isAmbient(ent.key)
                    && !NotificationData.showNotificationEvenIfUnprovisioned(ent.notification)) {
                continue;
            }
            if (!PhoneStatusBar.isTopLevelChild(ent)) {
                continue;
            }
            toShow.add(ent.icon);
        }

        ArrayList<View> toRemove = new ArrayList<>();
        for (int i = 0; i < mNotificationIcons.getChildCount(); i++) {
            View child = mNotificationIcons.getChildAt(i);
            if (!toShow.contains(child)) {
                toRemove.add(child);
            }
        }

        final int toRemoveCount = toRemove.size();
        for (int i = 0; i < toRemoveCount; i++) {
            mNotificationIcons.removeView(toRemove.get(i));
        }

        for (int i = 0; i < toShow.size(); i++) {
            View v = toShow.get(i);
            if (v.getParent() == null) {
                mNotificationIcons.addView(v, i, params);
            }
        }

        // Re-sort notification icons
        final int childCount = mNotificationIcons.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View actual = mNotificationIcons.getChildAt(i);
            StatusBarIconView expected = toShow.get(i);
            if (actual == expected) {
                continue;
            }
            mNotificationIcons.removeView(expected);
            mNotificationIcons.addView(expected, i);
        }

        applyNotificationIconsTint();
    }

    /**
     * Applies {@link #mIconTint} to the notification icons.
     */
    private void applyNotificationIconsTint() {
        for (int i = 0; i < mNotificationIcons.getChildCount(); i++) {
            StatusBarIconView v = (StatusBarIconView) mNotificationIcons.getChildAt(i);
            boolean isPreL = Boolean.TRUE.equals(v.getTag(R.id.icon_is_pre_L));
            boolean colorize = !isPreL || NotificationUtils.isGrayscale(v, mNotificationColorUtil);
            if (colorize) {
                v.setImageTintList(ColorStateList.valueOf(mIconTint));
            }
        }
    }
}