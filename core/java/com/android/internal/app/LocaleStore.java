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

package com.android.internal.app;

import android.content.Context;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Set;

public class LocaleStore {
    private static final HashMap<String, LocaleInfo> sLocaleCache = new HashMap<>();
    private static boolean sFullyInitialized = false;

    public static class LocaleInfo {
        private static final int SUGGESTION_TYPE_NONE = 0x00;
        private static final int SUGGESTION_TYPE_SIM = 0x01;

        private final Locale mLocale;
        private final Locale mParent;
        private final String mId;
        private boolean mIsTranslated;
        private boolean mIsPseudo;
        private boolean mIsChecked; // Used by the LocaleListEditor to mark entries for deletion
        // Combination of flags for various reasons to show a locale as a suggestion.
        // Can be SIM, location, etc.
        private int mSuggestionFlags;

        private String mFullNameNative;
        private String mFullCountryNameNative;
        private String mLangScriptKey;

        private LocaleInfo(Locale locale) {
            this.mLocale = locale;
            this.mId = locale.toLanguageTag();
            this.mParent = getParent(locale);
            this.mIsChecked = false;
            this.mSuggestionFlags = SUGGESTION_TYPE_NONE;
            this.mIsTranslated = false;
            this.mIsPseudo = false;
        }

        private LocaleInfo(String localeId) {
            this(Locale.forLanguageTag(localeId));
        }

        private static Locale getParent(Locale locale) {
            if (locale.getCountry().isEmpty()) {
                return null;
            }
            return new Locale.Builder()
                    .setLocale(locale).setRegion("")
                    .build();
        }

        @Override
        public String toString() {
            return mId;
        }

        public Locale getLocale() {
            return mLocale;
        }

        public Locale getParent() {
            return mParent;
        }

        public String getId() {
            return mId;
        }

        public boolean isTranslated() {
            return mIsTranslated;
        }

        public void setTranslated(boolean isTranslated) {
            mIsTranslated = isTranslated;
        }

        /* package */ boolean isSuggested() {
            if (!mIsTranslated) { // Never suggest an untranslated locale
                return false;
            }
            return mSuggestionFlags != SUGGESTION_TYPE_NONE;
        }

        private boolean isSuggestionOfType(int suggestionMask) {
            return (mSuggestionFlags & suggestionMask) == suggestionMask;
        }

        public String getFullNameNative() {
            if (mFullNameNative == null) {
                mFullNameNative =
                        LocaleHelper.getDisplayName(mLocale, mLocale, true /* sentence case */);
            }
            return mFullNameNative;
        }

        String getFullCountryNameNative() {
            if (mFullCountryNameNative == null) {
                mFullCountryNameNative = LocaleHelper.getDisplayCountry(mLocale, mLocale);
            }
            return mFullCountryNameNative;
        }

        /** Returns the name of the locale in the language of the UI.
         * It is used for search, but never shown.
         * For instance German will show as "Deutsch" in the list, but we will also search for
         * "allemand" if the system UI is in French.
         */
        public String getFullNameInUiLanguage() {
            return LocaleHelper.getDisplayName(mLocale, true /* sentence case */);
        }

        private String getLangScriptKey() {
            if (mLangScriptKey == null) {
                Locale parentWithScript = getParent(LocaleHelper.addLikelySubtags(mLocale));
                mLangScriptKey =
                        (parentWithScript == null)
                        ? mLocale.toLanguageTag()
                        : parentWithScript.toLanguageTag();
            }
            return mLangScriptKey;
        }

        String getLabel() {
            if (getParent() == null || this.isSuggestionOfType(SUGGESTION_TYPE_SIM)) {
                return getFullNameNative();
            } else {
                return getFullCountryNameNative();
            }
        }

        public boolean getChecked() {
            return mIsChecked;
        }

        public void setChecked(boolean checked) {
            mIsChecked = checked;
        }
    }

    private static Set<String> getSimCountries(Context context) {
        Set<String> result = new HashSet<>();

        TelephonyManager tm = TelephonyManager.from(context);

        if (tm != null) {
            String iso = tm.getSimCountryIso().toUpperCase(Locale.US);
            if (!iso.isEmpty()) {
                result.add(iso);
            }

            iso = tm.getNetworkCountryIso().toUpperCase(Locale.US);
            if (!iso.isEmpty()) {
                result.add(iso);
            }
        }

        return result;
    }

    public static void fillCache(Context context) {
        if (sFullyInitialized) {
            return;
        }

        Set<String> simCountries = getSimCountries(context);

        for (String localeId : LocalePicker.getSupportedLocales(context)) {
            if (localeId.isEmpty()) {
                throw new IllformedLocaleException("Bad locale entry in locale_config.xml");
            }
            LocaleInfo li = new LocaleInfo(localeId);
            if (simCountries.contains(li.getLocale().getCountry())) {
                li.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_SIM;
            }
            sLocaleCache.put(li.getId(), li);
            final Locale parent = li.getParent();
            if (parent != null) {
                String parentId = parent.toLanguageTag();
                if (!sLocaleCache.containsKey(parentId)) {
                    sLocaleCache.put(parentId, new LocaleInfo(parent));
                }
            }
        }

        boolean isInDeveloperMode = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        for (String localeId : LocalePicker.getPseudoLocales()) {
            LocaleInfo li = getLocaleInfo(Locale.forLanguageTag(localeId));
            if (isInDeveloperMode) {
                li.setTranslated(true);
                li.mIsPseudo = true;
                li.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_SIM;
            } else {
                sLocaleCache.remove(li.getId());
            }
        }

        // TODO: See if we can reuse what LocaleList.matchScore does
        final HashSet<String> localizedLocales = new HashSet<>();
        for (String localeId : LocalePicker.getSystemAssetLocales()) {
            LocaleInfo li = new LocaleInfo(localeId);
            localizedLocales.add(li.getLangScriptKey());
        }

        for (LocaleInfo li : sLocaleCache.values()) {
            li.setTranslated(localizedLocales.contains(li.getLangScriptKey()));
        }

        sFullyInitialized = true;
    }

    private static int getLevel(Set<String> ignorables, LocaleInfo li, boolean translatedOnly) {
        if (ignorables.contains(li.getId())) return 0;
        if (li.mIsPseudo) return 2;
        if (translatedOnly && !li.isTranslated()) return 0;
        if (li.getParent() != null) return 2;
        return 0;
    }

    /**
     * Returns a list of locales for language or region selection.
     * If the parent is null, then it is the language list.
     * If it is not null, then the list will contain all the locales that belong to that perent.
     * Example: if the parent is "ar", then the region list will contain all Arabic locales.
     * (this is not language based, but language-script, so that it works for zh-Hant and so on.
     */
    /* package */ static Set<LocaleInfo> getLevelLocales(Context context, Set<String> ignorables,
            LocaleInfo parent, boolean translatedOnly) {
        fillCache(context);
        String parentId = parent == null ? null : parent.getId();

        HashSet<LocaleInfo> result = new HashSet<>();
        for (LocaleStore.LocaleInfo li : sLocaleCache.values()) {
            int level = getLevel(ignorables, li, translatedOnly);
            if (level == 2) {
                if (parent != null) { // region selection
                    if (parentId.equals(li.getParent().toLanguageTag())) {
                        if (!li.isSuggestionOfType(LocaleInfo.SUGGESTION_TYPE_SIM)) {
                            result.add(li);
                        }
                    }
                } else { // language selection
                    if (li.isSuggestionOfType(LocaleInfo.SUGGESTION_TYPE_SIM)) {
                        result.add(li);
                    } else {
                        result.add(getLocaleInfo(li.getParent()));
                    }
                }
            }
        }
        return result;
    }

    public static LocaleInfo getLocaleInfo(Locale locale) {
        String id = locale.toLanguageTag();
        LocaleInfo result;
        if (!sLocaleCache.containsKey(id)) {
            result = new LocaleInfo(locale);
            sLocaleCache.put(id, result);
        } else {
            result = sLocaleCache.get(id);
        }
        return result;
    }
}