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

package com.android.server;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.CpuUsageInfo;
import android.os.IHardwarePropertiesManager;

import java.util.Arrays;

/**
 * Service for {@link HardwarePropertiesManager}
 */
public class HardwarePropertiesManagerService extends IHardwarePropertiesManager.Stub {

    private static native void nativeInit();

    private static native float[] nativeGetFanSpeeds();
    private static native float[] nativeGetDeviceTemperatures(int type);
    private static native CpuUsageInfo[] nativeGetCpuUsages();

    private final Context mContext;
    private final Object mLock = new Object();

    public HardwarePropertiesManagerService(Context context) {
        mContext = context;
        synchronized (mLock) {
            nativeInit();
        }
    }

    @Override
    public float[] getDeviceTemperatures(String callingPackage, int type) throws SecurityException {
        enforceHardwarePropertiesRetrievalAllowed(callingPackage);
        synchronized (mLock) {
            return nativeGetDeviceTemperatures(type);
        }
    }

    @Override
    public CpuUsageInfo[] getCpuUsages(String callingPackage) throws SecurityException {
        enforceHardwarePropertiesRetrievalAllowed(callingPackage);
        synchronized (mLock) {
            return nativeGetCpuUsages();
        }
    }

    @Override
    public float[] getFanSpeeds(String callingPackage) throws SecurityException {
        enforceHardwarePropertiesRetrievalAllowed(callingPackage);
        synchronized (mLock) {
            return nativeGetFanSpeeds();
        }
    }

    /**
     * Throws SecurityException if the calling package is not allowed to retrieve information
     * provided by the service.
     *
     * @param callingPackage The calling package name.
     *
     * @throws SecurityException if a non profile or device owner tries to retrieve information
     * provided by the service.
     */
    private void enforceHardwarePropertiesRetrievalAllowed(String callingPackage)
            throws SecurityException {
        final PackageManager pm = mContext.getPackageManager();
        try {
            final int uid = pm.getPackageUid(callingPackage, 0);
            if (Binder.getCallingUid() != uid) {
                throw new SecurityException("The caller has faked the package name.");
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException("The caller has faked the package name.");
        }

        final DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        if (!dpm.isDeviceOwnerApp(callingPackage) && !dpm.isProfileOwnerApp(callingPackage)) {
            throw new SecurityException("The caller is not a device or profile owner.");
        }
    }
}