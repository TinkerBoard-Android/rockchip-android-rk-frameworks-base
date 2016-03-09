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
package android.content.pm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.UserHandle;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * TODO Enhance javadoc
 *
 * Represents a shortcut form an application.
 *
 * Notes...
 * - If an {@link Icon} is of a resource, then we'll just persist the package name and resource ID.
 *
 *   Otherwise, the bitmap will be fetched when it's registered to ShortcutManager, then *shrunk*
 *   if necessary, and persisted.
 *
 *   We will disallow byte[] icons, because they can easily go over binder size limit.
 *
 * TODO Move save/load to this class
 */
public class ShortcutInfo implements Parcelable {
    /* @hide */
    public static final int FLAG_DYNAMIC = 1 << 0;

    /* @hide */
    public static final int FLAG_PINNED = 1 << 1;

    /* @hide */
    public static final int FLAG_HAS_ICON_RES = 1 << 2;

    /* @hide */
    public static final int FLAG_HAS_ICON_FILE = 1 << 3;

    /** @hide */
    @IntDef(flag = true,
            value = {
            FLAG_DYNAMIC,
            FLAG_PINNED,
            FLAG_HAS_ICON_RES,
            FLAG_HAS_ICON_FILE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShortcutFlags {}

    // Cloning options.

    /* @hide */
    private static final int CLONE_REMOVE_ICON = 1 << 0;

    /* @hide */
    private static final int CLONE_REMOVE_INTENT = 1 << 1;

    /* @hide */
    public static final int CLONE_REMOVE_NON_KEY_INFO = 1 << 2;

    /* @hide */
    public static final int CLONE_REMOVE_FOR_CREATOR = CLONE_REMOVE_ICON;

    /* @hide */
    public static final int CLONE_REMOVE_FOR_LAUNCHER = CLONE_REMOVE_ICON | CLONE_REMOVE_INTENT;

    /** @hide */
    @IntDef(flag = true,
            value = {
                    CLONE_REMOVE_ICON,
                    CLONE_REMOVE_INTENT,
                    CLONE_REMOVE_NON_KEY_INFO,
                    CLONE_REMOVE_FOR_CREATOR,
                    CLONE_REMOVE_FOR_LAUNCHER
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CloneFlags {}

    private final String mId;

    @NonNull
    private final String mPackageName;

    @Nullable
    private ComponentName mActivityComponent;

    @Nullable
    private Icon mIcon;

    @NonNull
    private String mTitle;

    @NonNull
    private Intent mIntent;

    // Internal use only.
    @NonNull
    private PersistableBundle mIntentPersistableExtras;

    private int mWeight;

    @Nullable
    private PersistableBundle mExtras;

    private long mLastChangedTimestamp;

    // Internal use only.
    @ShortcutFlags
    private int mFlags;

    // Internal use only.
    private int mIconResourceId;

    // Internal use only.
    @Nullable
    private String mBitmapPath;

    private ShortcutInfo(Builder b) {
        mId = Preconditions.checkStringNotEmpty(b.mId, "Shortcut ID must be provided");

        // Note we can't do other null checks here because SM.updateShortcuts() takes partial
        // information.
        mPackageName = b.mContext.getPackageName();
        mActivityComponent = b.mActivityComponent;
        mIcon = b.mIcon;
        mTitle = b.mTitle;
        mIntent = b.mIntent;
        mWeight = b.mWeight;
        mExtras = b.mExtras;
        updateTimestamp();
    }

    /**
     * Throws if any of the mandatory fields is not set.
     *
     * @hide
     */
    public void enforceMandatoryFields() {
        Preconditions.checkStringNotEmpty(mTitle, "Shortcut title must be provided");
        Preconditions.checkNotNull(mIntent, "Shortcut Intent must be provided");
    }

    /**
     * Copy constructor.
     */
    private ShortcutInfo(ShortcutInfo source, @CloneFlags int cloneFlags) {
        mId = source.mId;
        mPackageName = source.mPackageName;
        mActivityComponent = source.mActivityComponent;
        mFlags = source.mFlags;
        mLastChangedTimestamp = source.mLastChangedTimestamp;

        if ((cloneFlags & CLONE_REMOVE_NON_KEY_INFO) == 0) {
            if ((cloneFlags & CLONE_REMOVE_ICON) == 0) {
                mIcon = source.mIcon;
            }

            mTitle = source.mTitle;
            if ((cloneFlags & CLONE_REMOVE_INTENT) == 0) {
                mIntent = source.mIntent;
                mIntentPersistableExtras = source.mIntentPersistableExtras;
            }
            mWeight = source.mWeight;
            mExtras = source.mExtras;
            mIconResourceId = source.mIconResourceId;
            mBitmapPath = source.mBitmapPath;
        }
    }

    /**
     * Copy a {@link ShortcutInfo}, optionally removing fields.
     * @hide
     */
    public ShortcutInfo clone(@CloneFlags int cloneFlags) {
        return new ShortcutInfo(this, cloneFlags);
    }

    /**
     * Copy non-null/zero fields from another {@link ShortcutInfo}.  Only "public" information
     * will be overwritten.  The timestamp will be updated.
     *
     * - Flags will not change
     * - mBitmapPath will not change
     * - Current time will be set to timestamp
     *
     * @hide
     */
    public void copyNonNullFieldsFrom(ShortcutInfo source) {
        Preconditions.checkState(mId == source.mId, "ID must match");
        Preconditions.checkState(mPackageName.equals(source.mPackageName),
                "Package namae must match");

        if (source.mActivityComponent != null) {
            mActivityComponent = source.mActivityComponent;
        }

        if (source.mIcon != null) {
            mIcon = source.mIcon;
        }
        if (source.mTitle != null) {
            mTitle = source.mTitle;
        }
        if (source.mIntent != null) {
            mIntent = source.mIntent;
            mIntentPersistableExtras = source.mIntentPersistableExtras;
        }
        if (source.mWeight != 0) {
            mWeight = source.mWeight;
        }
        if (source.mExtras != null) {
            mExtras = source.mExtras;
        }

        updateTimestamp();
    }

    /**
     * Builder class for {@link ShortcutInfo} objects.
     */
    public static class Builder {
        private final Context mContext;

        private String mId;

        private ComponentName mActivityComponent;

        private Icon mIcon;

        private String mTitle;

        private Intent mIntent;

        private int mWeight;

        private PersistableBundle mExtras;

        /** Constructor. */
        public Builder(Context context) {
            mContext = context;
        }

        /**
         * Sets the ID of the shortcut.  This is a mandatory field.
         */
        @NonNull
        public Builder setId(@NonNull String id) {
            mId = Preconditions.checkStringNotEmpty(id, "id");
            return this;
        }

        /**
         * Optionally sets the target activity.
         */
        @NonNull
        public Builder setActivityComponent(@NonNull ComponentName activityComponent) {
            mActivityComponent = Preconditions.checkNotNull(activityComponent, "activityComponent");
            return this;
        }

        /**
         * Optionally sets an icon.
         *
         * - Tint is not supported TODO Either check and throw, or support it.
         * - URI icons will be converted into Bitmap icons at the registration time.
         *
         * TODO Only allow Bitmap, Resource and URI types.  byte[] type can easily go over
         * binder size limit.
         */
        @NonNull
        public Builder setIcon(Icon icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets the title of a shortcut.  This is a mandatory field.
         */
        @NonNull
        public Builder setTitle(@NonNull String title) {
            mTitle = Preconditions.checkStringNotEmpty(title, "title");
            return this;
        }

        /**
         * Sets the intent of a shortcut.  This is a mandatory field.  The extras must only contain
         * persistable information.  (See {@link PersistableBundle}).
         */
        @NonNull
        public Builder setIntent(@NonNull Intent intent) {
            mIntent = Preconditions.checkNotNull(intent, "intent");
            return this;
        }

        /**
         * Optionally sets the weight of a shortcut, which will be used by Launcher for sorting.
         * The larger the weight, the more "important" a shortcut is.
         */
        @NonNull
        public Builder setWeight(int weight) {
            mWeight = weight;
            return this;
        }

        /**
         * Optional values that application can set.
         * TODO: reserve keys starting with "android."
         */
        @NonNull
        public Builder setExtras(@NonNull PersistableBundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Creates a {@link ShortcutInfo} instance.
         */
        @NonNull
        public ShortcutInfo build() {
            return new ShortcutInfo(this);
        }
    }

    /**
     * Return the ID of the shortcut.
     */
    @NonNull
    public String getId() {
        return mId;
    }

    /**
     * Return the ID of the shortcut.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Return the target activity, which may be null, in which case the shortcut is not associated
     * with a specific activity.
     */
    @Nullable
    public ComponentName getActivityComponent() {
        return mActivityComponent;
    }

    /**
     * Icon.
     *
     * For performance reasons, this will <b>NOT</b> be available when an instance is returned
     * by {@link ShortcutManager} or {@link LauncherApps}.  A launcher application needs to use
     * other APIs in LauncherApps to fetch the bitmap.  TODO Add a precondition for it.
     *
     * @hide
     */
    @Nullable
    public Icon getIcon() {
        return mIcon;
    }

    /**
     * Return the shortcut title.
     */
    @NonNull
    public String getTitle() {
        return mTitle;
    }

    /**
     * Return the intent.
     * TODO Set mIntentPersistableExtras and before returning.
     */
    @NonNull
    public Intent getIntent() {
        return mIntent;
    }

    /** @hide */
    @Nullable
    public PersistableBundle getIntentPersistableExtras() {
        return mIntentPersistableExtras;
    }

    /**
     * Return the weight of a shortcut, which will be used by Launcher for sorting.
     * The larger the weight, the more "important" a shortcut is.
     */
    public int getWeight() {
        return mWeight;
    }

    /**
     * Optional values that application can set.
     */
    @Nullable
    public PersistableBundle getExtras() {
        return mExtras;
    }

    /**
     * Last time when any of the fields was updated.
     */
    public long getLastChangedTimestamp() {
        return mLastChangedTimestamp;
    }

    /** @hide */
    @ShortcutFlags
    public int getFlags() {
        return mFlags;
    }

    /** @hide*/
    public void setFlags(@ShortcutFlags int flags) {
        mFlags = flags;
    }

    /** @hide*/
    public void addFlags(@ShortcutFlags int flags) {
        mFlags |= flags;
    }

    /** @hide*/
    public void clearFlags(@ShortcutFlags int flags) {
        mFlags &= ~flags;
    }

    /** @hide*/
    public boolean hasFlags(@ShortcutFlags int flags) {
        return (mFlags & flags) == flags;
    }

    /** Return whether a shortcut is dynamic. */
    public boolean isDynamic() {
        return hasFlags(FLAG_DYNAMIC);
    }

    /** Return whether a shortcut is pinned. */
    public boolean isPinned() {
        return hasFlags(FLAG_PINNED);
    }

    /**
     * Return whether a shortcut's icon is a resource in the owning package.
     *
     * @see LauncherApps#getShortcutIconResId(ShortcutInfo, UserHandle)
     */
    public boolean hasIconResource() {
        return hasFlags(FLAG_HAS_ICON_RES);
    }

    /**
     * Return whether a shortcut's icon is stored as a file.
     *
     * @see LauncherApps#getShortcutIconFd(ShortcutInfo, UserHandle)
     */
    public boolean hasIconFile() {
        return hasFlags(FLAG_HAS_ICON_FILE);
    }

    /** @hide */
    public void updateTimestamp() {
        mLastChangedTimestamp = System.currentTimeMillis();
    }

    /** @hide */
    // VisibleForTesting
    public void setTimestamp(long value) {
        mLastChangedTimestamp = value;
    }

    /** @hide */
    public void setIcon(Icon icon) {
        mIcon = icon;
    }

    /** @hide */
    public void setTitle(String title) {
        mTitle = title;
    }

    /** @hide */
    public void setIntent(Intent intent) {
        mIntent = intent;
    }

    /** @hide */
    public void setIntentPersistableExtras(PersistableBundle intentPersistableExtras) {
        mIntentPersistableExtras = intentPersistableExtras;
    }

    /** @hide */
    public void setWeight(int weight) {
        mWeight = weight;
    }

    /** @hide */
    public void setExtras(PersistableBundle extras) {
        mExtras = extras;
    }

    /** @hide */
    public int getIconResourceId() {
        return mIconResourceId;
    }

    /** @hide */
    public String getBitmapPath() {
        return mBitmapPath;
    }

    /** @hide */
    public void setBitmapPath(String bitmapPath) {
        mBitmapPath = bitmapPath;
    }

    private ShortcutInfo(Parcel source) {
        final ClassLoader cl = getClass().getClassLoader();

        mId = source.readString();
        mPackageName = source.readString();
        mActivityComponent = source.readParcelable(cl);
        mIcon = source.readParcelable(cl);
        mTitle = source.readString();
        mIntent = source.readParcelable(cl);
        mIntentPersistableExtras = source.readParcelable(cl);
        mWeight = source.readInt();
        mExtras = source.readParcelable(cl);
        mLastChangedTimestamp = source.readLong();
        mFlags = source.readInt();
        mIconResourceId = source.readInt();
        mBitmapPath = source.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mPackageName);
        dest.writeParcelable(mActivityComponent, flags);
        dest.writeParcelable(mIcon, flags);
        dest.writeString(mTitle);
        dest.writeParcelable(mIntent, flags);
        dest.writeParcelable(mIntentPersistableExtras, flags);
        dest.writeInt(mWeight);
        dest.writeParcelable(mExtras, flags);
        dest.writeLong(mLastChangedTimestamp);
        dest.writeInt(mFlags);
        dest.writeInt(mIconResourceId);
        dest.writeString(mBitmapPath);
    }

    public static final Creator<ShortcutInfo> CREATOR =
            new Creator<ShortcutInfo>() {
                public ShortcutInfo createFromParcel(Parcel source) {
                    return new ShortcutInfo(source);
                }
                public ShortcutInfo[] newArray(int size) {
                    return new ShortcutInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Return a string representation, intended for logging.  Some fields will be retracted.
     */
    @Override
    public String toString() {
        return toStringInner(/* secure =*/ true, /* includeInternalData =*/ false);
    }

    /** @hide */
    public String toInsecureString() {
        return toStringInner(/* secure =*/ false, /* includeInternalData =*/ true);
    }

    private String toStringInner(boolean secure, boolean includeInternalData) {
        final StringBuilder sb = new StringBuilder();
        sb.append("ShortcutInfo {");

        sb.append("id=");
        sb.append(secure ? "***" : mId);

        sb.append(", packageName=");
        sb.append(mPackageName);

        if (isDynamic()) {
            sb.append(", dynamic");
        }
        if (isPinned()) {
            sb.append(", pinned");
        }

        sb.append(", activity=");
        sb.append(mActivityComponent);

        sb.append(", title=");
        sb.append(secure ? "***" : mTitle);

        sb.append(", icon=");
        sb.append(mIcon);

        sb.append(", weight=");
        sb.append(mWeight);

        sb.append(", timestamp=");
        sb.append(mLastChangedTimestamp);

        sb.append(", intent=");
        sb.append(mIntent);

        sb.append(", intentExtras=");
        sb.append(secure ? "***" : mIntentPersistableExtras);

        sb.append(", extras=");
        sb.append(mExtras);

        if (includeInternalData) {
            sb.append(", flags=");
            sb.append(mFlags);

            sb.append(", iconRes=");
            sb.append(mIconResourceId);

            sb.append(", bitmapPath=");
            sb.append(mBitmapPath);
        }

        sb.append("}");
        return sb.toString();
    }

    /** @hide */
    public ShortcutInfo(String id, String packageName, ComponentName activityComponent,
            Icon icon, String title, Intent intent, PersistableBundle intentPersistableExtras,
            int weight, PersistableBundle extras, long lastChangedTimestamp,
            int flags, int iconResId, String bitmapPath) {
        mId = id;
        mPackageName = packageName;
        mActivityComponent = activityComponent;
        mIcon = icon;
        mTitle = title;
        mIntent = intent;
        mIntentPersistableExtras = intentPersistableExtras;
        mWeight = weight;
        mExtras = extras;
        mLastChangedTimestamp = lastChangedTimestamp;
        mFlags = flags;
        mIconResourceId = iconResId;
        mBitmapPath = bitmapPath;
    }
}