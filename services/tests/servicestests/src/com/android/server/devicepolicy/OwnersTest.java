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

package com.android.server.devicepolicy;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.FileUtils;
import android.os.UserHandle;
import android.test.AndroidTestCase;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;

import junit.framework.Assert;

import static org.mockito.Mockito.when;

/**
 * Tests for the DeviceOwner object that saves & loads device and policy owner information.
 * run this test with:
 m FrameworksServicesTests &&
 adb install \
   -r out/target/product/hammerhead/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.devicepolicy.OwnersTest \
   -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner

 (mmma frameworks/base/services/tests/servicestests/ for non-ninja build)
 */
public class OwnersTest extends DpmTestBase {
    private static final String TAG = "DeviceOwnerTest";

    private static final String LEGACY_FILE = "legacy.xml";
    private static final String DEVICE_OWNER_FILE = "device_owner2.xml";
    private static final String PROFILE_OWNER_FILE_BASE = "profile_owner.xml";

    private File mDataDir;

    private class OwnersSub extends Owners {
        final File mLegacyFile;
        final File mDeviceOwnerFile;
        final File mProfileOwnerBase;

        public OwnersSub() {
            super(getContext());
            mLegacyFile = new File(mDataDir, LEGACY_FILE);
            mDeviceOwnerFile = new File(mDataDir, DEVICE_OWNER_FILE);
            mProfileOwnerBase = new File(mDataDir, PROFILE_OWNER_FILE_BASE);
        }

        @Override
        File getLegacyConfigFileWithTestOverride() {
            return mLegacyFile;
        }

        @Override
        File getDeviceOwnerFileWithTestOverride() {
            return mDeviceOwnerFile;
        }

        @Override
        File getProfileOwnerFileWithTestOverride(int userId) {
            return new File(mDeviceOwnerFile.getAbsoluteFile() + "-" + userId);
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mDataDir = new File(getContext().getCacheDir(), "OwnersTest");
        if (mDataDir.exists()) {
            assertTrue("failed to delete dir", FileUtils.deleteContents(mDataDir));
        }
        mDataDir.mkdirs();
        Log.i(TAG, "Created " + mDataDir);
    }

    private String readAsset(String assetPath) throws IOException {
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader((getContext().getResources().getAssets().open(assetPath))))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
                sb.append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    private void createLegacyFile(File path, String content)
            throws IOException {
        path.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(path)) {
            Log.i(TAG, "Writing to " + path);
            Log.i(TAG, content);
            writer.write(content);
        }
    }

    private void addUsersToUserManager(int... userIds) {
        final ArrayList<UserInfo> userInfos = new ArrayList<>();
        for (int userId : userIds) {
            final UserInfo ui = new UserInfo();
            ui.id = userId;
            userInfos.add(ui);
        }
        when(getContext().getMockUserManager().getUsers()).thenReturn(userInfos);
    }

    public void testUpgrade01() throws Exception {
        addUsersToUserManager(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersSub owners = new OwnersSub();

            createLegacyFile(owners.mLegacyFile, readAsset("OwnersTest/test01/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertFalse(owners.getLegacyConfigFileWithTestOverride().exists());

            // File was empty, so no new files should be created.
            assertFalse(owners.getDeviceOwnerFileWithTestOverride().exists());

            assertFalse(owners.getProfileOwnerFileWithTestOverride(10).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(11).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(20).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(21).exists());

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());
            assertFalse(owners.hasDeviceInitializer());
            assertNull(owners.getSystemUpdatePolicy());
            assertEquals(0, owners.getProfileOwnerKeys().size());
        }

        // Then re-read and check.
        {
            final OwnersSub owners = new OwnersSub();
            owners.load();

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());
            assertFalse(owners.hasDeviceInitializer());
            assertNull(owners.getSystemUpdatePolicy());
            assertEquals(0, owners.getProfileOwnerKeys().size());
        }
    }

    public void testUpgrade02() throws Exception {
        addUsersToUserManager(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersSub owners = new OwnersSub();

            createLegacyFile(owners.mLegacyFile, readAsset("OwnersTest/test02/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertFalse(owners.getLegacyConfigFileWithTestOverride().exists());

            assertTrue(owners.getDeviceOwnerFileWithTestOverride().exists()); // TODO Check content

            assertFalse(owners.getProfileOwnerFileWithTestOverride(10).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(11).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(20).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(21).exists());

            assertTrue(owners.hasDeviceOwner());
            assertEquals(null, owners.getDeviceOwnerName());
            assertEquals("com.google.android.testdpc", owners.getDeviceOwnerPackageName());
            assertEquals(UserHandle.USER_SYSTEM, owners.getDeviceOwnerUserId());

            assertFalse(owners.hasDeviceInitializer());
            assertNull(owners.getSystemUpdatePolicy());
            assertEquals(0, owners.getProfileOwnerKeys().size());
        }

        // Then re-read and check.
        {
            final OwnersSub owners = new OwnersSub();
            owners.load();

            assertTrue(owners.hasDeviceOwner());
            assertEquals(null, owners.getDeviceOwnerName());
            assertEquals("com.google.android.testdpc", owners.getDeviceOwnerPackageName());
            assertEquals(UserHandle.USER_SYSTEM, owners.getDeviceOwnerUserId());

            assertFalse(owners.hasDeviceInitializer());
            assertNull(owners.getSystemUpdatePolicy());
            assertEquals(0, owners.getProfileOwnerKeys().size());
        }
    }

    public void testUpgrade03() throws Exception {
        addUsersToUserManager(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersSub owners = new OwnersSub();

            createLegacyFile(owners.mLegacyFile, readAsset("OwnersTest/test03/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertFalse(owners.getLegacyConfigFileWithTestOverride().exists());

            assertFalse(owners.getDeviceOwnerFileWithTestOverride().exists());

            assertTrue(owners.getProfileOwnerFileWithTestOverride(10).exists());
            assertTrue(owners.getProfileOwnerFileWithTestOverride(11).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(20).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(21).exists());

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());
            assertFalse(owners.hasDeviceInitializer());
            assertNull(owners.getSystemUpdatePolicy());

            assertEquals(2, owners.getProfileOwnerKeys().size());
            assertEquals(new ComponentName("com.google.android.testdpc",
                            "com.google.android.testdpc.DeviceAdminReceiver0"),
                    owners.getProfileOwnerComponent(10));
            assertEquals("0", owners.getProfileOwnerName(10));
            assertEquals("com.google.android.testdpc", owners.getProfileOwnerPackage(10));

            assertEquals(new ComponentName("com.google.android.testdpc1", ""),
                    owners.getProfileOwnerComponent(11));
            assertEquals("1", owners.getProfileOwnerName(11));
            assertEquals("com.google.android.testdpc1", owners.getProfileOwnerPackage(11));
        }

        // Then re-read and check.
        {
            final OwnersSub owners = new OwnersSub();
            owners.load();

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());
            assertFalse(owners.hasDeviceInitializer());
            assertNull(owners.getSystemUpdatePolicy());

            assertEquals(2, owners.getProfileOwnerKeys().size());
            assertEquals(new ComponentName("com.google.android.testdpc",
                            "com.google.android.testdpc.DeviceAdminReceiver0"),
                    owners.getProfileOwnerComponent(10));
            assertEquals("0", owners.getProfileOwnerName(10));
            assertEquals("com.google.android.testdpc", owners.getProfileOwnerPackage(10));

            assertEquals(new ComponentName("com.google.android.testdpc1", ""),
                    owners.getProfileOwnerComponent(11));
            assertEquals("1", owners.getProfileOwnerName(11));
            assertEquals("com.google.android.testdpc1", owners.getProfileOwnerPackage(11));
        }
    }

    public void testUpgrade04() throws Exception {
        addUsersToUserManager(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersSub owners = new OwnersSub();

            createLegacyFile(owners.mLegacyFile, readAsset("OwnersTest/test04/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertFalse(owners.getLegacyConfigFileWithTestOverride().exists());

            assertTrue(owners.getDeviceOwnerFileWithTestOverride().exists());

            assertTrue(owners.getProfileOwnerFileWithTestOverride(10).exists());
            assertTrue(owners.getProfileOwnerFileWithTestOverride(11).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(20).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(21).exists());

            assertTrue(owners.hasDeviceOwner());
            assertEquals(null, owners.getDeviceOwnerName());
            assertEquals("com.google.android.testdpc", owners.getDeviceOwnerPackageName());
            assertEquals(UserHandle.USER_SYSTEM, owners.getDeviceOwnerUserId());

            assertTrue(owners.hasDeviceInitializer());
            assertEquals("com.google.android.testdpcx", owners.getDeviceInitializerPackageName());
            assertNotNull(owners.getSystemUpdatePolicy());
            assertEquals(5, owners.getSystemUpdatePolicy().getPolicyType());

            assertEquals(2, owners.getProfileOwnerKeys().size());
            assertEquals(new ComponentName("com.google.android.testdpc",
                            "com.google.android.testdpc.DeviceAdminReceiver0"),
                    owners.getProfileOwnerComponent(10));
            assertEquals("0", owners.getProfileOwnerName(10));
            assertEquals("com.google.android.testdpc", owners.getProfileOwnerPackage(10));

            assertEquals(new ComponentName("com.google.android.testdpc1", ""),
                    owners.getProfileOwnerComponent(11));
            assertEquals("1", owners.getProfileOwnerName(11));
            assertEquals("com.google.android.testdpc1", owners.getProfileOwnerPackage(11));
        }

        // Then re-read and check.
        {
            final OwnersSub owners = new OwnersSub();
            owners.load();

            assertTrue(owners.hasDeviceOwner());
            assertEquals(null, owners.getDeviceOwnerName());
            assertEquals("com.google.android.testdpc", owners.getDeviceOwnerPackageName());
            assertEquals(UserHandle.USER_SYSTEM, owners.getDeviceOwnerUserId());

            assertTrue(owners.hasDeviceInitializer());
            assertEquals("com.google.android.testdpcx", owners.getDeviceInitializerPackageName());
            assertNotNull(owners.getSystemUpdatePolicy());
            assertEquals(5, owners.getSystemUpdatePolicy().getPolicyType());

            assertEquals(2, owners.getProfileOwnerKeys().size());
            assertEquals(new ComponentName("com.google.android.testdpc",
                            "com.google.android.testdpc.DeviceAdminReceiver0"),
                    owners.getProfileOwnerComponent(10));
            assertEquals("0", owners.getProfileOwnerName(10));
            assertEquals("com.google.android.testdpc", owners.getProfileOwnerPackage(10));

            assertEquals(new ComponentName("com.google.android.testdpc1", ""),
                    owners.getProfileOwnerComponent(11));
            assertEquals("1", owners.getProfileOwnerName(11));
            assertEquals("com.google.android.testdpc1", owners.getProfileOwnerPackage(11));
        }
    }

    public void testUpgrade05() throws Exception {
        addUsersToUserManager(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersSub owners = new OwnersSub();

            createLegacyFile(owners.mLegacyFile, readAsset("OwnersTest/test05/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertFalse(owners.getLegacyConfigFileWithTestOverride().exists());

            assertTrue(owners.getDeviceOwnerFileWithTestOverride().exists());

            assertFalse(owners.getProfileOwnerFileWithTestOverride(10).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(11).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(20).exists());

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());

            assertTrue(owners.hasDeviceInitializer());
            assertEquals("com.google.android.testdpcx", owners.getDeviceInitializerPackageName());

            assertNull(owners.getSystemUpdatePolicy());
            assertEquals(0, owners.getProfileOwnerKeys().size());
        }

        // Then re-read and check.
        {
            final OwnersSub owners = new OwnersSub();
            owners.load();

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());

            assertTrue(owners.hasDeviceInitializer());
            assertEquals("com.google.android.testdpcx", owners.getDeviceInitializerPackageName());

            assertNull(owners.getSystemUpdatePolicy());
            assertEquals(0, owners.getProfileOwnerKeys().size());
        }
    }

    public void testUpgrade06() throws Exception {
        addUsersToUserManager(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersSub owners = new OwnersSub();

            createLegacyFile(owners.mLegacyFile, readAsset("OwnersTest/test06/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertFalse(owners.getLegacyConfigFileWithTestOverride().exists());

            assertTrue(owners.getDeviceOwnerFileWithTestOverride().exists());

            assertFalse(owners.getProfileOwnerFileWithTestOverride(10).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(11).exists());
            assertFalse(owners.getProfileOwnerFileWithTestOverride(20).exists());

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());
            assertFalse(owners.hasDeviceInitializer());
            assertEquals(0, owners.getProfileOwnerKeys().size());

            assertNotNull(owners.getSystemUpdatePolicy());
            assertEquals(5, owners.getSystemUpdatePolicy().getPolicyType());
        }

        // Then re-read and check.
        {
            final OwnersSub owners = new OwnersSub();
            owners.load();

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());
            assertFalse(owners.hasDeviceInitializer());
            assertEquals(0, owners.getProfileOwnerKeys().size());

            assertNotNull(owners.getSystemUpdatePolicy());
            assertEquals(5, owners.getSystemUpdatePolicy().getPolicyType());
        }
    }

    public void testRemoveExistingFiles() throws Exception {
        addUsersToUserManager(10, 11, 20, 21);

        final OwnersSub owners = new OwnersSub();

        // First, migrate to create new-style config files.
        createLegacyFile(owners.mLegacyFile, readAsset("OwnersTest/test04/input.xml"));

        owners.load();

        assertFalse(owners.getLegacyConfigFileWithTestOverride().exists());

        assertTrue(owners.getDeviceOwnerFileWithTestOverride().exists());
        assertTrue(owners.getProfileOwnerFileWithTestOverride(10).exists());
        assertTrue(owners.getProfileOwnerFileWithTestOverride(11).exists());

        // Then clear all information and save.
        owners.clearDeviceInitializer();
        owners.clearDeviceOwner();
        owners.clearSystemUpdatePolicy();
        owners.removeProfileOwner(10);
        owners.removeProfileOwner(11);

        owners.writeDeviceOwner();
        owners.writeProfileOwner(10);
        owners.writeProfileOwner(11);
        owners.writeProfileOwner(20);
        owners.writeProfileOwner(21);

        // Now all files should be removed.
        assertFalse(owners.getDeviceOwnerFileWithTestOverride().exists());
        assertFalse(owners.getProfileOwnerFileWithTestOverride(10).exists());
        assertFalse(owners.getProfileOwnerFileWithTestOverride(11).exists());
    }
}