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

package com.android.internal.inputmethod;

import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.LocaleList;

import java.util.ArrayList;
import java.util.Locale;

public class LocaleUtilsTest extends InstrumentationTestCase {

    private static final LocaleUtils.LocaleExtractor<Locale> sIdentityMapper =
            new LocaleUtils.LocaleExtractor<Locale>() {
                @Override
                public Locale get(Locale source) {
                    return source;
                }
            };

    @SmallTest
    public void testFilterByLanguageEmptyLanguageList() throws Exception {
        final ArrayList<Locale> availableLocales = new ArrayList<>();
        availableLocales.add(Locale.forLanguageTag("en-US"));
        availableLocales.add(Locale.forLanguageTag("fr-CA"));
        availableLocales.add(Locale.forLanguageTag("in"));
        availableLocales.add(Locale.forLanguageTag("ja"));
        availableLocales.add(Locale.forLanguageTag("fil"));

        final LocaleList preferredLocales = LocaleList.getEmptyLocaleList();

        final ArrayList<Locale> dest = new ArrayList<>();
        LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
        assertEquals(0, dest.size());
    }

    @SmallTest
    public void testFilterByLanguageEmptySource() throws Exception {
        final ArrayList<Locale> availableLocales = new ArrayList<>();

        final LocaleList preferredLocales = LocaleList.forLanguageTags("fr,en-US,ja-JP");

        final ArrayList<Locale> dest = new ArrayList<>();
        LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
        assertEquals(0, dest.size());
    }

    @SmallTest
    public void testFilterByLanguageNullAvailableLocales() throws Exception {
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(null);
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(0, dest.size());
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(null);
            availableLocales.add(null);
            availableLocales.add(null);
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(0, dest.size());
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(null);
            availableLocales.add(Locale.forLanguageTag("en-US"));
            availableLocales.add(null);
            availableLocales.add(null);
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(1), dest.get(0));  // "en-US"
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(null);
            availableLocales.add(Locale.forLanguageTag("en"));
            availableLocales.add(null);
            availableLocales.add(null);
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(1), dest.get(0));  // "en"
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(null);
            availableLocales.add(Locale.forLanguageTag("ja-JP"));
            availableLocales.add(null);
            availableLocales.add(null);
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(0, dest.size());
        }
    }

    @SmallTest
    public void testFilterByLanguage() throws Exception {
        final ArrayList<Locale> availableLocales = new ArrayList<>();
        availableLocales.add(Locale.forLanguageTag("en-US"));
        availableLocales.add(Locale.forLanguageTag("fr-CA"));
        availableLocales.add(Locale.forLanguageTag("in"));
        availableLocales.add(Locale.forLanguageTag("ja"));
        availableLocales.add(Locale.forLanguageTag("fil"));

        final LocaleList preferredLocales = LocaleList.forLanguageTags("fr,en-US,ja-JP");

        final ArrayList<Locale> dest = new ArrayList<>();
        LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
        assertEquals(3, dest.size());
        assertEquals(availableLocales.get(1), dest.get(0));  // "fr-CA"
        assertEquals(availableLocales.get(0), dest.get(1));  // "en-US"
        assertEquals(availableLocales.get(3), dest.get(2));  // "ja"
    }

    @SmallTest
    public void testFilterByLanguageTheSameLanguage() throws Exception {
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("fr-CA"));
            availableLocales.add(Locale.forLanguageTag("en-US"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(1), dest.get(0));  // "en-US"
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("fr-CA"));
            availableLocales.add(Locale.forLanguageTag("en"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(1), dest.get(0));  // "en"
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("fr-CA"));
            availableLocales.add(Locale.forLanguageTag("en-CA"));
            availableLocales.add(Locale.forLanguageTag("en-IN"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(2), dest.get(0));  // "en-IN"
        }
        {
            final LocaleList preferredLocales =
                    LocaleList.forLanguageTags("en-AU,en-GB,en-US,en-IN");
            final ArrayList<Locale> availableLocales = new ArrayList<>();
            availableLocales.add(Locale.forLanguageTag("fr-CA"));
            availableLocales.add(Locale.forLanguageTag("en-CA"));
            availableLocales.add(Locale.forLanguageTag("en-NZ"));
            availableLocales.add(Locale.forLanguageTag("en-BZ"));
            final ArrayList<Locale> dest = new ArrayList<>();
            LocaleUtils.filterByLanguage(availableLocales, sIdentityMapper, preferredLocales, dest);
            assertEquals(1, dest.size());
            assertEquals(availableLocales.get(1), dest.get(0));  // "en-CA"
        }
    }
}